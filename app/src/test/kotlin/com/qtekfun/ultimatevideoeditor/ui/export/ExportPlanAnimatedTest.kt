package com.qtekfun.ultimatevideoeditor.ui.export

import com.qtekfun.ultimatevideoeditor.data.animationTiming
import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto
import com.qtekfun.ultimatevideoeditor.domain.AnimationTiming
import com.qtekfun.ultimatevideoeditor.domain.Clip
import com.qtekfun.ultimatevideoeditor.domain.FrameIndex
import com.qtekfun.ultimatevideoeditor.domain.FrameRate
import com.qtekfun.ultimatevideoeditor.domain.StillKind
import com.qtekfun.ultimatevideoeditor.domain.timeline
import com.qtekfun.ultimatevideoeditor.domain.track
import com.qtekfun.ultimatevideoeditor.engine.still.StillRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportPlanAnimatedTest {
    private val fps = FrameRate(30, 1)
    private val animated = MediaAssetDto(
        "gif", "content://pic/anim", 18, 30, 1, "Rec709-SDR", hasVideo = false, hasAudio = false, isImage = true,
        animationDelaysMs = listOf(100, 200, 300),
    )
    private val photo = MediaAssetDto("img", "content://pic/1", 150, 30, 1, "Rec709-SDR", hasVideo = false, hasAudio = false, isImage = true)

    private fun photoClip(id: String, start: Long, len: Long, asset: String) =
        Clip(id, asset, FrameIndex(start), FrameIndex.ZERO, FrameIndex(len), still = StillKind.PHOTO)

    @Test
    fun `an animated picture is one spec per stretch of the same animation frame`() {
        val plan = buildExportPlan(timeline(track("v1", photoClip("g", 0, 19, "gif"))), listOf(animated), fps)!!

        assertEquals(
            listOf(0L to 3L, 3L to 6L, 9L to 9L, 18L to 1L),
            plan.videoClips.map { it.startFrame to it.durationFrames },
        )
        // Contiguous, covering the clip exactly.
        assertEquals(19L, plan.videoClips.sumOf { it.durationFrames })
        assertEquals(
            setOf(
                StillRef(StillKind.PHOTO, "content://pic/anim", 0),
                StillRef(StillKind.PHOTO, "content://pic/anim", 1),
                StillRef(StillKind.PHOTO, "content://pic/anim", 2),
            ),
            plan.stills.values.toSet(),
        )
        // The looped first stretch and the last one share a picture, so it is rasterised once.
        assertEquals(3, plan.stills.size)
        assertEquals(plan.videoClips.first().titleKey, plan.videoClips.last().titleKey)
        assertTrue(plan.videoClips.all { it.titleKey != 0 && it.assetKey == 0L })
    }

    @Test
    fun `the export picks the same animation frame as the timing gives at every project frame`() {
        val timing = animated.animationTiming()!!
        val clip = photoClip("g", 40, 100, "gif")
        val plan = buildExportPlan(timeline(track("v1", clip)), listOf(animated), fps)!!
        val refByKey = plan.stills
        for (spec in plan.videoClips) {
            val expected = refByKey.getValue(spec.titleKey).frame
            for (f in spec.startFrame until spec.startFrame + spec.durationFrames) {
                assertEquals("frame $f", timing.frameIndexAt(f - 40, fps), expected)
            }
        }
    }

    @Test
    fun `a photo and a clip of an asset with one frame stay one spec`() {
        val one = animated.copy(id = "one", animationDelaysMs = listOf(500))
        val plan = buildExportPlan(
            timeline(track("v2", photoClip("p", 0, 60, "img")), track("v1", photoClip("o", 0, 60, "one"))),
            listOf(photo, one),
            fps,
        )!!
        assertEquals(2, plan.videoClips.size)
        assertEquals(setOf(0), plan.stills.values.map { it.frame }.toSet())
    }

    @Test
    fun `timing is read from the asset and only for valid pictures`() {
        assertEquals(AnimationTiming(listOf(100, 200, 300)), animated.animationTiming())
        assertNull(photo.animationTiming())
        assertNull(animated.copy(isImage = false).animationTiming())
        assertNull(animated.copy(animationDelaysMs = listOf(100)).animationTiming())
        assertNull(animated.copy(animationDelaysMs = listOf(100, 0)).animationTiming())
        assertNull(animated.copy(animationDelaysMs = null).animationTiming())
    }

    @Test
    fun `a clip starting later on the timeline starts its animation at its own first frame`() {
        val plan = buildExportPlan(timeline(track("v1", photoClip("g", 100, 19, "gif"))), listOf(animated), fps)!!
        assertEquals(
            listOf(100L to 3L, 103L to 6L, 109L to 9L, 118L to 1L),
            plan.videoClips.map { it.startFrame to it.durationFrames },
        )
        assertEquals(listOf(0, 1, 2, 0), plan.videoClips.map { plan.stills.getValue(it.titleKey).frame })
    }
}
