package com.qtekfun.ultimatevideoeditor.ui.export

import com.qtekfun.ultimatevideoeditor.domain.FrameRate
import com.qtekfun.ultimatevideoeditor.engine.export.ExportCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportOptionsTest {

    @Test
    fun `a 4k project offers every standard size down to 480p`() {
        val options = resolutionOptions(3840, 2160)

        assertEquals(listOf(2160, 1440, 1080, 720, 480), options.map { it.shortSide })
        assertEquals(3840 to 2160, options.first().width to options.first().height)
        assertEquals(1920 to 1080, options[2].width to options[2].height)
        assertEquals(854 to 480, options.last().width to options.last().height)
    }

    @Test
    fun `a portrait project keeps its aspect ratio`() {
        val options = resolutionOptions(1080, 1920)

        assertEquals(listOf(1080, 720, 480), options.map { it.shortSide })
        assertEquals(720 to 1280, options[1].width to options[1].height)
    }

    @Test
    fun `an unusual project size is offered as it is and every size is even`() {
        val options = resolutionOptions(1000, 562)

        assertEquals(562, options.first().shortSide)
        assertTrue(options.all { it.width % 2 == 0 && it.height % 2 == 0 })
    }

    @Test
    fun `frame rate options start with the project rate and never exceed it`() {
        assertEquals(
            listOf(FrameRate(60000, 1001), FrameRate(50, 1), FrameRate(30, 1), FrameRate(25, 1), FrameRate(24, 1)),
            frameRateOptions(FrameRate(60000, 1001)),
        )
        assertEquals(listOf(FrameRate(30, 1), FrameRate(25, 1), FrameRate(24, 1)), frameRateOptions(FrameRate(30, 1)))
        assertEquals(listOf(FrameRate(30000, 1001), FrameRate(25, 1), FrameRate(24, 1)), frameRateOptions(FrameRate(30000, 1001)))
    }

    @Test
    fun `suggested bitrate grows with size and rate and is lower for hevc`() {
        val h264 = suggestedBitrateMbps(1920, 1080, FrameRate(30, 1), ExportCodec.H264)
        val hevc = suggestedBitrateMbps(1920, 1080, FrameRate(30, 1), ExportCodec.HEVC)
        val uhd60 = suggestedBitrateMbps(3840, 2160, FrameRate(60, 1), ExportCodec.H264)

        assertEquals(8, h264)
        assertEquals(4, hevc)
        assertEquals(35, uhd60)
        assertTrue(uhd60 > h264)
    }

    @Test
    fun `suggested bitrate is capped at the largest choice`() {
        assertEquals(bitrateChoicesMbps().last(), suggestedBitrateMbps(7680, 4320, FrameRate(120, 1), ExportCodec.H264))
    }

    @Test
    fun `file names drop characters that file systems reject`() {
        assertEquals("My_movie_.mp4", suggestedFileName("My/movie:"))
        assertEquals("ultimateVE.mp4", suggestedFileName("  ..  "))
        assertEquals("Tech_Review_01.mp4", suggestedFileName("Tech_Review_01"))
        assertEquals(80 + ".mp4".length, suggestedFileName("x".repeat(200)).length)
    }
}
