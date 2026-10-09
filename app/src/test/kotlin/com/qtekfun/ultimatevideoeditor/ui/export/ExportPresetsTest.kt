package com.qtekfun.ultimatevideoeditor.ui.export

import com.qtekfun.ultimatevideoeditor.domain.FrameRate
import com.qtekfun.ultimatevideoeditor.engine.export.ExportCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportPresetsTest {

    private fun preset(id: String) = ExportPresets.all.first { it.id == id }

    @Test
    fun `every preset is distinct and has sane numbers`() {
        assertEquals(ExportPresets.all.size, ExportPresets.all.map { it.id }.toSet().size)
        for (p in ExportPresets.all) {
            assertTrue(p.id, p.shortSide in listOf(720, 1080, 1440, 2160))
            assertTrue(p.id, p.maxFps in 24..60)
            assertTrue(p.id, p.bitrateMbps in bitrateChoicesMbps())
            assertTrue(p.id, Regex("\\d+:\\d+").matches(p.aspect))
        }
    }

    @Test
    fun `a vertical project on tiktok gets 1080 by 1920 at 30 fps`() {
        val choice = resolvePreset(
            preset("tiktok"),
            resolutionOptions(1080, 1920),
            frameRateOptions(FrameRate(60, 1)),
        )

        assertEquals(1080 to 1920, choice.resolution.width to choice.resolution.height)
        assertEquals(FrameRate(30, 1), choice.frameRate)
        assertEquals(ExportCodec.H264, choice.codec)
        assertEquals(8, choice.bitrateMbps)
    }

    @Test
    fun `a project slower than the preset keeps its own rate`() {
        val choice = resolvePreset(preset("youtube-1080"), resolutionOptions(1920, 1080), frameRateOptions(FrameRate(24, 1)))

        assertEquals(FrameRate(24, 1), choice.frameRate)
    }

    @Test
    fun `ntsc rates above the cap fall to the next standard rate`() {
        val choice = resolvePreset(preset("instagram-reels"), resolutionOptions(1080, 1920), frameRateOptions(FrameRate(60000, 1001)))

        assertEquals(FrameRate(30, 1), choice.frameRate)
        // 29.97 is within the cap, so a 29.97 project keeps it.
        val keep = resolvePreset(preset("instagram-reels"), resolutionOptions(1080, 1920), frameRateOptions(FrameRate(30000, 1001)))
        assertEquals(FrameRate(30000, 1001), keep.frameRate)
    }

    @Test
    fun `a preset never upscales past the project`() {
        val choice = resolvePreset(preset("youtube-4k"), resolutionOptions(1920, 1080), frameRateOptions(FrameRate(30, 1)))

        assertEquals(1080, choice.resolution.shortSide)
        assertEquals(ExportCodec.HEVC, choice.codec)
        assertEquals(35, choice.bitrateMbps)
    }

    @Test
    fun `a preset smaller than the project picks the largest size that does not exceed it`() {
        val choice = resolvePreset(preset("youtube-1080"), resolutionOptions(3840, 2160), frameRateOptions(FrameRate(30, 1)))

        assertEquals(1080, choice.resolution.shortSide)
        assertEquals(1920 to 1080, choice.resolution.width to choice.resolution.height)
    }

    @Test
    fun `a project smaller than every size the preset allows falls back to its own`() {
        val choice = resolvePreset(preset("youtube-1080"), resolutionOptions(640, 360), frameRateOptions(FrameRate(30, 1)))

        assertEquals(360, choice.resolution.shortSide)
    }
}
