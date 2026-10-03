package com.ultimatevideo.uveditor.ui.export

import com.ultimatevideo.uveditor.data.model.MediaAssetDto
import com.ultimatevideo.uveditor.domain.FrameRate
import com.ultimatevideo.uveditor.domain.TrackType
import com.ultimatevideo.uveditor.domain.clip
import com.ultimatevideo.uveditor.domain.timeline
import com.ultimatevideo.uveditor.domain.track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ExportPlanTest {

    private val fps = FrameRate(30, 1)

    private fun asset(id: String, colorSpace: String = "Rec709-SDR", hasVideo: Boolean = true, hasAudio: Boolean = true) =
        MediaAssetDto(id, "content://$id", 600, 30, 1, colorSpace, hasVideo = hasVideo, hasAudio = hasAudio)

    @Test
    fun `an empty timeline has nothing to export`() {
        assertNull(buildExportPlan(timeline(track("v1")), listOf(asset("a")), fps))
    }

    @Test
    fun `the first video track is the topmost layer`() {
        val tl = timeline(
            track("v2", clip("top", 0, 100, asset = "b")),
            track("v1", clip("bottom", 0, 100, asset = "a")),
        )

        val plan = buildExportPlan(tl, listOf(asset("a"), asset("b")), fps)!!

        val byAsset = plan.videoClips.associateBy { plan.assetKeys.entries.first { e -> e.value == it.assetKey }.key }
        assertEquals(0, byAsset.getValue("b").layer)
        assertEquals(1, byAsset.getValue("a").layer)
    }

    @Test
    fun `source ranges and placement are copied in project frames`() {
        val tl = timeline(track("v1", clip("c", 40, 60, srcIn = 25, asset = "a")))

        val spec = buildExportPlan(tl, listOf(asset("a")), fps)!!.videoClips.single()

        assertEquals(40L, spec.startFrame)
        assertEquals(60L, spec.durationFrames)
        assertEquals(25L, spec.sourceInFrame)
    }

    @Test
    fun `hlg sources are tone mapped and sdr sources are not`() {
        val tl = timeline(
            track("v1", clip("c1", 0, 10, asset = "hlg"), clip("c2", 10, 10, asset = "sdr")),
        )

        val plan = buildExportPlan(tl, listOf(asset("hlg", "Rec2020-HLG"), asset("sdr")), fps)!!

        val keyOf = plan.assetKeys
        assertEquals(1, plan.videoClips.single { it.assetKey == keyOf.getValue("hlg") }.colorMode)
        assertEquals(0, plan.videoClips.single { it.assetKey == keyOf.getValue("sdr") }.colorMode)
    }

    @Test
    fun `movie length is the end of the last clip on any track`() {
        val tl = timeline(
            track("v1", clip("c1", 0, 100, asset = "a")),
            track("a1", clip("c2", 80, 120, asset = "b"), type = TrackType.AUDIO),
        )

        assertEquals(200L, buildExportPlan(tl, listOf(asset("a"), asset("b", hasVideo = false)), fps)!!.projectFrames)
    }

    @Test
    fun `audio is planned for audio tracks and for video clips that have sound`() {
        val tl = timeline(
            track("v1", clip("c1", 0, 100, asset = "a")),
            track("a1", clip("c2", 0, 100, asset = "music"), type = TrackType.AUDIO),
        )

        val plan = buildExportPlan(tl, listOf(asset("a"), asset("music", hasVideo = false)), fps)!!

        assertEquals(2, plan.audio!!.clips.size)
        assertEquals(1, plan.videoClips.size) // the audio-only asset is never rendered as video
    }

    @Test
    fun `a movie without any sound has no audio track`() {
        val tl = timeline(track("v1", clip("c1", 0, 100, asset = "mute")))

        val plan = buildExportPlan(tl, listOf(asset("mute", hasAudio = false)), fps)!!

        assertNull(plan.audio)
        assertEquals(1, plan.videoClips.size)
    }

    @Test
    fun `video and audio of one asset share a native key`() {
        val tl = timeline(track("v1", clip("c1", 0, 100, asset = "a")))

        val plan = buildExportPlan(tl, listOf(asset("a")), fps)!!

        assertEquals(1, plan.assetKeys.size)
        assertEquals(plan.videoClips.single().assetKey, plan.audio!!.clips.single().assetKey)
    }

    @Test
    fun `clips whose media is missing from the library are skipped`() {
        val tl = timeline(track("v1", clip("c1", 0, 100, asset = "gone"), clip("c2", 100, 50, asset = "a")))

        val plan = buildExportPlan(tl, listOf(asset("a")), fps)!!

        assertEquals(1, plan.videoClips.size)
        assertNotNull(plan.assetKeys["a"])
        assertNull(plan.assetKeys["gone"])
    }

    @Test
    fun `output frame count keeps the duration and rounds up`() {
        assertEquals(100L, outputFrameCount(100, FrameRate(30, 1), FrameRate(30, 1)))
        assertEquals(50L, outputFrameCount(100, FrameRate(60, 1), FrameRate(30, 1)))
        assertEquals(200L, outputFrameCount(100, FrameRate(30, 1), FrameRate(60, 1)))
        assertEquals(34L, outputFrameCount(100, FrameRate(60, 1), FrameRate(20, 1))) // 33.33 rounds up
        assertEquals(30000L, outputFrameCount(60000, FrameRate(60000, 1001), FrameRate(30000, 1001)))
        assertEquals(0L, outputFrameCount(0, FrameRate(30, 1), FrameRate(30, 1)))
    }

    @Test
    fun `output frame count does not overflow on very long projects`() {
        val frames = 1L shl 40
        assertEquals(frames * 2, outputFrameCount(frames, FrameRate(30, 1), FrameRate(60, 1)))
    }
}
