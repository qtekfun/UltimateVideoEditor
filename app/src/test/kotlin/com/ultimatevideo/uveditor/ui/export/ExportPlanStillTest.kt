package com.ultimatevideo.uveditor.ui.export

import com.ultimatevideo.uveditor.data.model.MediaAssetDto
import com.ultimatevideo.uveditor.domain.Clip
import com.ultimatevideo.uveditor.domain.ClipTransform
import com.ultimatevideo.uveditor.domain.FrameIndex
import com.ultimatevideo.uveditor.domain.FrameRate
import com.ultimatevideo.uveditor.domain.StillKind
import com.ultimatevideo.uveditor.domain.TitleContent
import com.ultimatevideo.uveditor.domain.TrackType
import com.ultimatevideo.uveditor.domain.clip
import com.ultimatevideo.uveditor.domain.timeline
import com.ultimatevideo.uveditor.domain.track
import com.ultimatevideo.uveditor.engine.still.StillRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportPlanStillTest {

    private val fps = FrameRate(30, 1)
    private val image = MediaAssetDto("img", "content://pic/1", 150, 30, 1, "Rec709-SDR", hasVideo = false, hasAudio = false, isImage = true)
    private val video = MediaAssetDto("a1", "content://a1", 600, 30, 1, "Rec709-SDR", hasVideo = true, hasAudio = false)

    private fun still(id: String, start: Long, len: Long, kind: StillKind, asset: String) =
        Clip(id, asset, FrameIndex(start), FrameIndex.ZERO, FrameIndex(len), still = kind)

    @Test
    fun `photos and stickers become picture clips with keys, not decoder assets`() {
        val tl = timeline(
            track("v2", still("s", 0, 60, StillKind.STICKER, "shape:heart")),
            track("v1", still("p", 0, 150, StillKind.PHOTO, "img")),
        )

        val plan = buildExportPlan(tl, listOf(image), fps)!!

        assertEquals(2, plan.videoClips.size)
        assertTrue(plan.videoClips.all { it.titleKey != 0 && it.assetKey == 0L })
        assertEquals(emptyMap<String, Long>(), plan.assetKeys)
        assertEquals(
            setOf(StillRef(StillKind.PHOTO, "content://pic/1"), StillRef(StillKind.STICKER, "shape:heart")),
            plan.stills.values.toSet(),
        )
        assertEquals(150L, plan.projectFrames)
    }

    @Test
    fun `the same picture used twice is rasterised once`() {
        val tl = timeline(track("v1", still("p1", 0, 50, StillKind.PHOTO, "img"), still("p2", 50, 50, StillKind.PHOTO, "img")))

        val plan = buildExportPlan(tl, listOf(image), fps)!!

        assertEquals(1, plan.stills.size)
        assertEquals(1, plan.videoClips.map { it.titleKey }.toSet().size)
    }

    @Test
    fun `titles and stills never share a key`() {
        val title = Clip("t", null, FrameIndex(0), FrameIndex.ZERO, FrameIndex(30), title = TitleContent("Hi"))
        val tl = timeline(
            track("t1", title, type = TrackType.TITLE),
            track("v2", still("s", 0, 30, StillKind.STICKER, "shape:star")),
            track("v1", clip("c", 0, 30, asset = "a1")),
        )

        val plan = buildExportPlan(tl, listOf(video), fps)!!

        val keys = plan.titles.keys + plan.stills.keys
        assertEquals(2, keys.size)
        assertNotEquals(plan.titles.keys.single(), plan.stills.keys.single())
        assertEquals(setOf(1, 2), keys)
    }

    @Test
    fun `a photo whose file is no longer in the library is skipped`() {
        val tl = timeline(
            track("v2", still("p", 0, 30, StillKind.PHOTO, "gone")),
            track("v1", clip("c", 0, 30, asset = "a1")),
        )

        val plan = buildExportPlan(tl, listOf(video), fps)!!

        assertEquals(1, plan.videoClips.size)
        assertTrue(plan.stills.isEmpty())
    }

    @Test
    fun `stills keep their transform and opacity`() {
        val s = still("s", 0, 30, StillKind.STICKER, "shape:ring")
            .copy(transform = ClipTransform(positionX = 100.0, scaleX = 1.5, scaleY = 1.5, opacity = 0.5))
        val plan = buildExportPlan(timeline(track("v1", s)), emptyList(), fps)!!

        val spec = plan.videoClips.single()
        assertEquals(100.0, spec.positionX, 0.0)
        assertEquals(1.5, spec.scaleX, 0.0)
        assertEquals(0.5, spec.opacity, 0.0)
    }
}
