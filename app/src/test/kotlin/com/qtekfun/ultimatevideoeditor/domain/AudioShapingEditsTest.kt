package com.qtekfun.ultimatevideoeditor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fades and the volume curve (the keyframed `audio.gainDb` track) through the clip operations: a trim never leaves a point
 * outside the clip, a split divides the curve with the same gain on both sides of the cut, and a fade handle never outgrows
 * its clip or crosses a cut.
 */
class AudioShapingEditsTest {
    private val gain = ParamIds.GAIN_DB

    /** A 100 frame audio clip at 0 with a fade-in of 20, a fade-out of 30 and a curve 0 dB, -12 dB at 40, -6 dB at 70, 0 dB at 99. */
    private fun shaped(): Timeline {
        val keys = listOf(ParamKey(0, 0.0), ParamKey(40, -12.0), ParamKey(70, -6.0), ParamKey(99, 0.0))
        val c = clip("c", 0, 100).copy(
            params = listOf(ParamTrack(gain, keys)),
            audio = ClipAudio(fadeInFrames = 20, fadeOutFrames = 30, fadeShape = FadeShape.LOGARITHMIC),
        )
        return timeline(track("a", c, type = TrackType.AUDIO))
    }

    private fun Timeline.clipById(id: String) = trackOfClip(id)!!.clip(id)!!

    private fun Clip.keys() = paramKeys(gain)

    private fun assertWithinClip(clip: Clip) {
        val keys = clip.keys()
        assertTrue("no key outside ${clip.id}: $keys", keys.all { it.frame in 0 until clip.durationFrames })
        if (keys.isNotEmpty()) assertNull(ParamTracks.problem(clip.paramSpec(gain)!!, keys, clip.durationFrames))
        assertNull(clip.audio.problem(clip.durationFrames))
    }

    // region split

    @Test
    fun `a split divides the curve and the gain is continuous across the cut`() {
        val before = shaped()
        val original = before.clipById("c")
        val split = TimelineOps.split(before, "a", f(55), "c2").getOrFail()
        val left = split.clipById("c")
        val right = split.clipById("c2")

        assertWithinClip(left)
        assertWithinClip(right)
        // The last frame of the left half and the first of the right half have the gain the whole clip had there.
        val lastLeft = left.durationFrames - 1
        assertEquals(ParamTracks.evaluate(original.keys(), lastLeft, 0.0), ParamTracks.evaluate(left.keys(), lastLeft, 0.0), 1e-9)
        assertEquals(ParamTracks.evaluate(original.keys(), 55, 0.0), ParamTracks.evaluate(right.keys(), 0, 0.0), 1e-9)
        // And every frame of both halves matches the original curve.
        for (frame in 0 until 55L) assertEquals(ParamTracks.evaluate(original.keys(), frame, 0.0), ParamTracks.evaluate(left.keys(), frame, 0.0), 1e-9)
        for (frame in 0 until 45L) assertEquals(ParamTracks.evaluate(original.keys(), frame + 55, 0.0), ParamTracks.evaluate(right.keys(), frame, 0.0), 1e-9)
    }

    @Test
    fun `a split keeps each fade on the side that has the original edge and the shape on both`() {
        val split = TimelineOps.split(shaped(), "a", f(55), "c2").getOrFail()
        val left = split.clipById("c").audio
        val right = split.clipById("c2").audio
        assertEquals(20L, left.fadeInFrames)
        assertEquals(0L, left.fadeOutFrames)
        assertEquals(0L, right.fadeInFrames)
        assertEquals(30L, right.fadeOutFrames)
        assertEquals(FadeShape.LOGARITHMIC, left.fadeShape)
        assertEquals(FadeShape.LOGARITHMIC, right.fadeShape)
    }

    @Test
    fun `a cut inside a fade shortens it to the half and does not restart it`() {
        val split = TimelineOps.split(shaped(), "a", f(10), "c2").getOrFail()
        assertEquals(10L, split.clipById("c").audio.fadeInFrames) // clamped to the 10 frame half
        assertEquals(0L, split.clipById("c2").audio.fadeInFrames)
        assertEquals(30L, split.clipById("c2").audio.fadeOutFrames)
    }

    // endregion

    // region trim

    @Test
    fun `trimming the end drops the points past it and holds the gain the clip had at the new end`() {
        val before = shaped()
        val original = before.clipById("c")
        val trimmed = TimelineOps.trim(before, "c", TrimEdge.END, f(60)).getOrFail().clipById("c")
        assertEquals(60L, trimmed.durationFrames)
        assertWithinClip(trimmed)
        assertTrue(trimmed.keys().none { it.frame >= 60 })
        for (frame in 0 until 60L) assertEquals(ParamTracks.evaluate(original.keys(), frame, 0.0), ParamTracks.evaluate(trimmed.keys(), frame, 0.0), 1e-9)
    }

    @Test
    fun `trimming the start shifts the points and pins the gain at the new first frame`() {
        val before = shaped()
        val original = before.clipById("c")
        val trimmed = TimelineOps.trim(before, "c", TrimEdge.START, f(50)).getOrFail().clipById("c")
        assertEquals(50L, trimmed.durationFrames)
        assertWithinClip(trimmed)
        assertEquals(0L, trimmed.keys().first().frame)
        for (frame in 0 until 50L) assertEquals(ParamTracks.evaluate(original.keys(), frame + 50, 0.0), ParamTracks.evaluate(trimmed.keys(), frame, 0.0), 1e-9)
    }

    @Test
    fun `trimming a clip shorter than its fades clamps them and never leaves a bad value`() {
        val trimmed = TimelineOps.trim(shaped(), "c", TrimEdge.END, f(12)).getOrFail().clipById("c")
        assertEquals(12L, trimmed.durationFrames)
        assertEquals(12L, trimmed.audio.fadeOutFrames) // 30 clamped to the new length
        assertEquals(12L, trimmed.audio.fadeInFrames)
        assertWithinClip(trimmed)
        // Trimming the start past the fade-in takes the fade-in away with the part it was on.
        val front = TimelineOps.trim(shaped(), "c", TrimEdge.START, f(25)).getOrFail().clipById("c")
        assertWithinClip(front)
        assertTrue(front.audio.fadeInFrames <= front.durationFrames && front.audio.fadeOutFrames <= front.durationFrames)
    }

    // endregion

    // region fade values and ripple

    @Test
    fun `a fade that does not fit its clip is rejected`() {
        val t = shaped()
        assertTrue(TimelineOps.setClipAudio(t, "c", ClipAudio(fadeInFrames = 101)) is EditResult.Failure)
        assertTrue(TimelineOps.setClipAudio(t, "c", ClipAudio(fadeOutFrames = -1)) is EditResult.Failure)
        assertTrue(TimelineOps.setClipAudio(t, "c", ClipAudio(fadeInFrames = 100, fadeShape = FadeShape.LINEAR)) is EditResult.Success)
    }

    @Test
    fun `deleting a neighbour with a ripple leaves the fades and the curve of the clips that move`() {
        val c1 = clip("c1", 0, 50)
        val c2 = shaped().clipById("c").copy(id = "c2", timelineStart = f(50))
        val t = timeline(track("a", c1, c2, type = TrackType.AUDIO))
        val moved = TimelineOps.rippleDelete(t, "c1").getOrFail().clipById("c2")
        assertEquals(f(0), moved.timelineStart)
        assertEquals(c2.audio, moved.audio)
        assertEquals(c2.keys(), moved.keys())
    }

    @Test
    fun `the fade curve gain of a clip is the mirror of its fade-out`() {
        // The domain value the mixer applies (FadeCurve mirrors core/fade_math.h): silence at the ends, unity in between.
        val a = shaped().clipById("c").audio
        assertEquals(1.0, FadeCurve.gainAt(a.fadeShape, a.fadeInFrames, a.fadeOutFrames, 100, 50), 1e-12)
        assertTrue(FadeCurve.gainAt(a.fadeShape, a.fadeInFrames, a.fadeOutFrames, 100, 0) < 0.01)
        assertTrue(FadeCurve.gainAt(a.fadeShape, a.fadeInFrames, a.fadeOutFrames, 100, 99) < 0.01)
    }

    // endregion
}
