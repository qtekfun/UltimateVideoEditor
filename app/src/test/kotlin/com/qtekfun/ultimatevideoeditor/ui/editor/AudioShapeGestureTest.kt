package com.qtekfun.ultimatevideoeditor.ui.editor

import com.qtekfun.ultimatevideoeditor.domain.Clip
import com.qtekfun.ultimatevideoeditor.domain.ClipAudio
import com.qtekfun.ultimatevideoeditor.domain.FadeShape
import com.qtekfun.ultimatevideoeditor.domain.FrameIndex
import com.qtekfun.ultimatevideoeditor.domain.ParamIds
import com.qtekfun.ultimatevideoeditor.domain.ParamKey
import com.qtekfun.ultimatevideoeditor.domain.ParamTrack
import com.qtekfun.ultimatevideoeditor.domain.TrackType
import com.qtekfun.ultimatevideoeditor.engine.timeline.HitKind
import com.qtekfun.ultimatevideoeditor.engine.timeline.TimelineHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The decisions behind the fade circles and volume points on the canvas; pure, so no timeline view is needed. */
class AudioShapeGestureTest {
    /** A 200 frame clip starting at project frame 100, with a fade-in of 20 and a fade-out of 40. */
    private fun clip(keys: List<ParamKey> = emptyList(), fadeIn: Long = 20, fadeOut: Long = 40): Clip =
        Clip("c", "a", FrameIndex(100), FrameIndex(0), FrameIndex(200)).copy(
            audio = ClipAudio(fadeInFrames = fadeIn, fadeOutFrames = fadeOut),
            params = if (keys.isEmpty()) emptyList() else listOf(ParamTrack(ParamIds.GAIN_DB, keys)),
        )

    private fun hit(kind: HitKind, frame: Long, db: Double? = null, index: Int = -1) =
        TimelineHit(kind, 0, 1, frame, index, db?.let { Math.round(it * 10) })

    // region fades

    @Test
    fun `the fade-in follows the finger and stops at the clip start and at the fade-out`() {
        assertEquals(30L, AudioShapeGesture.fadeIn(100, 200, 40, 130, 0))
        assertEquals(0L, AudioShapeGesture.fadeIn(100, 200, 40, 60, 0))      // left of the clip
        assertEquals(160L, AudioShapeGesture.fadeIn(100, 200, 40, 290, 0))  // cannot cross the fade-out of 40
        assertEquals(30L, AudioShapeGesture.fadeIn(100, 200, 40, 135, 5))   // the grab offset is kept
    }

    @Test
    fun `the fade-out is measured from the clip end`() {
        assertEquals(50L, AudioShapeGesture.fadeOut(100, 200, 20, 250, 0))
        assertEquals(0L, AudioShapeGesture.fadeOut(100, 200, 20, 330, 0))   // right of the clip
        assertEquals(180L, AudioShapeGesture.fadeOut(100, 200, 20, 10, 0))  // cannot cross the fade-in of 20
    }

    @Test
    fun `dragging a fade handle yields the new pair of lengths and leaves the other one alone`() {
        val c = clip()
        val inDrag = AudioShapeGesture.Drag.begin(hit(HitKind.FADE_IN_HANDLE, 120), c)!!
        assertEquals(AudioShapeGesture.Action.SetFades(35, 40), inDrag.move(hit(HitKind.NONE, 135)))
        val outDrag = AudioShapeGesture.Drag.begin(hit(HitKind.FADE_OUT_HANDLE, 260), c)!!
        assertEquals(AudioShapeGesture.Action.SetFades(20, 55), outDrag.move(hit(HitKind.NONE, 245)))
        // A handle with no fade yet is grabbed without a jump: the first move measures from the clip edge.
        val none = AudioShapeGesture.Drag.begin(hit(HitKind.FADE_IN_HANDLE, 106), clip(fadeIn = 0))!!
        assertEquals(AudioShapeGesture.Action.SetFades(14, 40), none.move(hit(HitKind.NONE, 114)))
    }

    // endregion

    // region points

    private val keys = listOf(ParamKey(0, 0.0), ParamKey(60, -10.0), ParamKey(120, -3.0), ParamKey(199, 0.0))

    @Test
    fun `a point moves only between its neighbours and takes the gain under the finger`() {
        val drag = AudioShapeGesture.Drag.begin(hit(HitKind.VOLUME_POINT, 160, -10.0, index = 1), clip(keys))!!
        assertEquals(AudioShapeGesture.Action.MovePoint(60, 80, -6.0), drag.move(hit(HitKind.NONE, 180, -6.04)))
        assertEquals(AudioShapeGesture.Action.MovePoint(60, 119, -6.0), drag.move(hit(HitKind.NONE, 900)))   // stops short of the next point
        assertEquals(AudioShapeGesture.Action.MovePoint(60, 1, -6.0), drag.move(hit(HitKind.NONE, 0)))      // and of the previous one
        // Outside the lanes there is no gain reading: the last one stays.
        assertEquals(AudioShapeGesture.Action.MovePoint(60, 50, -6.0), drag.move(hit(HitKind.NONE, 150)))
    }

    @Test
    fun `the first and last points keep to the clip`() {
        val first = AudioShapeGesture.Drag.begin(hit(HitKind.VOLUME_POINT, 100, 0.0, index = 0), clip(keys))!!
        assertEquals(0L, (first.move(hit(HitKind.NONE, 0)) as AudioShapeGesture.Action.MovePoint).toFrame)
        val last = AudioShapeGesture.Drag.begin(hit(HitKind.VOLUME_POINT, 299, 0.0, index = 3), clip(keys))!!
        assertEquals(199L, (last.move(hit(HitKind.NONE, 900)) as AudioShapeGesture.Action.MovePoint).toFrame)
    }

    @Test
    fun `values stick to unity, stay on the scale and the bottom edge is silence`() {
        assertEquals(0.0, AudioShapeGesture.snapDb(0.6), 0.0)
        assertEquals(0.0, AudioShapeGesture.snapDb(-0.79), 0.0)
        assertEquals(-1.0, AudioShapeGesture.snapDb(-0.96), 0.0)
        assertEquals(6.3, AudioShapeGesture.snapDb(6.26), 0.0)
        assertEquals(12.0, AudioShapeGesture.snapDb(40.0), 0.0)
        assertEquals(-30.5, AudioShapeGesture.snapDb(-30.46), 0.0)
        assertEquals(AudioShapeGesture.SILENT_DB, AudioShapeGesture.snapDb(-48.0), 0.0)
        assertEquals(AudioShapeGesture.SILENT_DB, AudioShapeGesture.snapDb(-300.0), 0.0)
        assertEquals(0.0, AudioShapeGesture.snapDb(Double.NaN), 0.0)
    }

    @Test
    fun `a first volume point pins the fixed volume at both ends so only the part around it changes`() {
        val c = clip().copy(gainDb = -2.0)
        val added = AudioShapeGesture.keysForNewPoint(c, 80, -20.0)
        assertEquals(listOf(0L, 80L, 199L), added.map { it.frame })
        assertEquals(listOf(-2.0, -20.0, -2.0), added.map { it.value })
        // A point on the first frame replaces the pin there instead of doubling the key.
        assertEquals(listOf(0L, 199L), AudioShapeGesture.keysForNewPoint(c, 0, -20.0).map { it.frame })
        assertEquals(-20.0, AudioShapeGesture.keysForNewPoint(c, 0, -20.0).first().value, 0.0)
        // With a curve already there only the new point is added; a frame outside the clip is pulled inside.
        assertEquals(listOf(199L), AudioShapeGesture.keysForNewPoint(clip(keys), 5000, 3.0).map { it.frame })
        // The playhead variant keeps the exact value rather than rounding it to the editor's unit.
        assertEquals(-7.123, AudioShapeGesture.keysForNewPoint(clip(keys), 30, -7.123, snap = false).single().value, 0.0)
    }

    // endregion

    // region what is grabbable and drawn

    @Test
    fun `only the selected clip of an audio lane is editable and only shape hits start a drag`() {
        val c = clip()
        assertTrue(AudioShapeGesture.isEditable(c, TrackType.AUDIO, isPrimary = true))
        assertFalse(AudioShapeGesture.isEditable(c, TrackType.AUDIO, isPrimary = false))
        assertFalse(AudioShapeGesture.isEditable(c, TrackType.VIDEO, isPrimary = true))
        assertTrue(AudioShapeGesture.isShapeHit(HitKind.VOLUME_POINT) && AudioShapeGesture.isShapeHit(HitKind.FADE_IN_HANDLE))
        assertFalse(AudioShapeGesture.isShapeHit(HitKind.CLIP) || AudioShapeGesture.isShapeHit(HitKind.CLIP_LEFT_EDGE))
        assertNull(AudioShapeGesture.Drag.begin(hit(HitKind.CLIP, 150), c))
        assertNull(AudioShapeGesture.Drag.begin(hit(HitKind.VOLUME_POINT, 150, index = 7), clip(keys)))
    }

    @Test
    fun `the canvas entry carries the fades, the shape, the static gain and the points`() {
        val c = clip(keys).copy(gainDb = -2.0, audio = ClipAudio(fadeInFrames = 20, fadeOutFrames = 40, fadeShape = FadeShape.LINEAR))
        val s = AudioShapeGesture.snapshotOf(c, 9, editable = true)!!
        assertEquals(9L, s.clipKey)
        assertEquals(20L to 40L, s.fadeInFrames to s.fadeOutFrames)
        assertEquals(FadeShape.LINEAR.code, s.fadeShape)
        assertEquals(-2f, s.baseDb, 0f)
        assertEquals(listOf(0L, 60L, 120L, 199L), s.points.map { it.frame })
        assertTrue(s.editable)
        assertEquals(FadeShape.LINEAR.code or 4, s.flags)
        // A clip with nothing to show and nothing to edit is left out; a title has no sound at all.
        assertNull(AudioShapeGesture.snapshotOf(clip(fadeIn = 0, fadeOut = 0), 9, editable = false))
        assertNotNull(AudioShapeGesture.snapshotOf(clip(fadeIn = 0, fadeOut = 0), 9, editable = true))
        assertNotNull(AudioShapeGesture.snapshotOf(clip(), 9, editable = false))
    }
    // endregion
}
