package com.qtekfun.ultimatevideoeditor.ui.export

import com.qtekfun.ultimatevideoeditor.data.VideoFacts
import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto
import com.qtekfun.ultimatevideoeditor.domain.FrameRate
import com.qtekfun.ultimatevideoeditor.domain.StillKind
import com.qtekfun.ultimatevideoeditor.domain.Timeline
import com.qtekfun.ultimatevideoeditor.domain.TrackType
import com.qtekfun.ultimatevideoeditor.domain.clip
import com.qtekfun.ultimatevideoeditor.domain.timeline
import com.qtekfun.ultimatevideoeditor.domain.track
import com.qtekfun.ultimatevideoeditor.engine.export.ExportCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import com.qtekfun.ultimatevideoeditor.ui.text.english

class ExportDefaultsTest {

    private fun video(
        id: String,
        mbps: Double? = null,
        w: Int? = 3840,
        h: Int? = 2160,
        codec: String? = "avc",
        tenBit: Boolean? = false,
        color: String = "Rec709-SDR",
    ) = MediaAssetDto(
        id, "content://$id", 600, 30, 1, color,
        videoWidth = w, videoHeight = h, videoBitrate = mbps?.let { (it * 1_000_000).toLong() }, videoCodec = codec, tenBit = tenBit,
    )

    private fun used(vararg assets: MediaAssetDto): UsedSources =
        usedSources(timeline(track("v1", *assets.mapIndexed { i, a -> clip("c$i", i * 100L, 90, asset = a.id) }.toTypedArray())), assets.toList())

    private val fps30 = FrameRate(30, 1)

    private fun recommend(sources: UsedSources, w: Int = 3840, h: Int = 2160, fps: FrameRate = fps30, codec: ExportCodec = ExportCodec.H264) =
        recommendExport(w, h, fps, sources, codec)

    @Test
    fun `bit rate is the smallest choice that covers the best source`() {
        // (source Mbit/s, expected choice)
        val table = listOf(80.0 to 80, 52.0 to 80, 50.0 to 50, 40.0 to 50, 35.0 to 35, 20.0 to 20, 12.0 to 20, 3.0 to 20)
        for ((source, expected) in table) {
            assertEquals("source $source", expected, recommend(used(video("a", source))).bitrateMbps)
        }
    }

    @Test
    fun `unknown bit rate keeps today's default`() {
        val r = recommend(used(video("a", mbps = null, codec = null, tenBit = null, w = null, h = null)))
        assertEquals(suggestedBitrateMbps(3840, 2160, fps30, ExportCodec.H264), r.bitrateMbps)
        assertEquals(ExportCodec.H264, r.codec)
        assertFalse(r.fromSources)
        assertNull(r.sourceMbps)
    }

    @Test
    fun `an empty or photo only timeline keeps today's default`() {
        val photo = video("p").copy(hasVideo = false, isImage = true, videoBitrate = 90_000_000L)
        val tl = timeline(track("v1", clip("c", 0, 90, asset = "p")))
        val sources = usedSources(tl, listOf(photo))
        assertTrue(sources.isEmpty)
        assertEquals(suggestedBitrateMbps(1920, 1080, fps30, ExportCodec.H264), recommend(sources, 1920, 1080).bitrateMbps)
        assertTrue(usedSources(Timeline(), emptyList()).isEmpty)
    }

    @Test
    fun `a still on a video track does not count`() {
        val a = video("a", 80.0)
        val still = clip("s", 0, 90, asset = "a").copy(still = StillKind.PHOTO)
        val sources = usedSources(timeline(track("v1", still)), listOf(a))
        assertTrue(sources.isEmpty)
        assertNull(sources.maxBitrateMbps)
    }

    @Test
    fun `unused library clips are ignored`() {
        val big = video("big", 80.0)
        val small = video("small", 20.0)
        val tl = timeline(track("v1", clip("c", 0, 90, asset = "small")))
        val sources = usedSources(tl, listOf(big, small))
        assertEquals(20.0, sources.maxBitrateMbps!!, 0.001)
        assertEquals(20, recommend(sources).bitrateMbps)
    }

    @Test
    fun `audio only tracks do not count as video`() {
        val a = video("a", 80.0)
        val tl = timeline(track("a1", clip("c", 0, 90, asset = "a"), type = TrackType.AUDIO))
        assertTrue(usedSources(tl, listOf(a)).isEmpty)
    }

    @Test
    fun `mixed 1080p and 4K sources on a 4K canvas follow the best one`() {
        val sources = used(video("hd", 17.0, 1920, 1080), video("uhd", 52.0))
        assertEquals(2160, sources.maxShortSide)
        val r = recommend(sources)
        assertEquals(80, r.bitrateMbps)
        assertEquals(52.0, r.sourceMbps!!, 0.001)
    }

    @Test
    fun `a canvas smaller than the sources scales the rate by pixel ratio`() {
        // 80 Mbit/s of 4K is about 20 Mbit/s of 1080p.
        val r = recommend(used(video("a", 80.0)), 1920, 1080)
        assertEquals(20, r.bitrateMbps)
        assertEquals(20.0, r.sourceMbps!!, 0.01)
    }

    @Test
    fun `the rate never goes below today's default for that size`() {
        val r = recommend(used(video("a", 2.0, 1920, 1080, codec = "avc")), 3840, 2160, FrameRate(60, 1))
        assertEquals(suggestedBitrateMbps(3840, 2160, FrameRate(60, 1), ExportCodec.H264), r.bitrateMbps)
    }

    @Test
    fun `sources above the highest choice select it and say so`() {
        val sources = used(video("a", 120.0))
        val r = recommend(sources)
        assertEquals(80, r.bitrateMbps)
        assertTrue(r.capped)
        assertEquals("Your clips go up to 120 Mbps; the highest choice is 80 Mbps", bitrateAdvice(r, sources, 80)?.english())
    }

    @Test
    fun `advice names the clips' rate and says whether the choice keeps quality`() {
        val sources = used(video("a", 52.0))
        val r = recommend(sources)
        assertEquals("Your clips go up to 52 Mbps: 80 Mbps keeps their quality", bitrateAdvice(r, sources, 80)?.english())
        assertEquals("Your clips go up to 52 Mbps: at 20 Mbps some of their quality is lost", bitrateAdvice(r, sources, 20)?.english())
        assertNull(bitrateAdvice(recommend(UsedSources()), UsedSources(), 20))
    }

    @Test
    fun `codec is HEVC for HEVC, 10 bit and HDR sources and for rates H264 should not carry`() {
        assertEquals(ExportCodec.HEVC, recommend(used(video("a", 20.0, codec = "hevc"))).codec)
        assertEquals(ExportCodec.HEVC, recommend(used(video("a", 20.0, tenBit = true))).codec)
        assertEquals(ExportCodec.HEVC, recommend(used(video("a", 20.0, color = "Rec2020-HLG"))).codec)
        assertEquals(ExportCodec.HEVC, recommend(used(video("a", 20.0, color = "Rec2020-PQ"))).codec)
        // 4K30 H.264 gets 20 by default; 52 is beyond twice that.
        assertEquals(ExportCodec.HEVC, recommend(used(video("a", 52.0))).codec)
        assertEquals(ExportCodec.H264, recommend(used(video("a", 20.0))).codec)
        // The previous default is kept when nothing asks for HEVC.
        assertEquals(ExportCodec.HEVC, recommend(used(video("a", 20.0)), codec = ExportCodec.HEVC).codec)
    }

    @Test
    fun `a codec the user picked is respected when the rate is recomputed`() {
        val sources = used(video("a", 30.0))
        assertEquals(35, recommendedBitrateMbps(3840, 2160, fps30, ExportCodec.H264, sources))
        assertEquals(35, recommendedBitrateMbps(3840, 2160, fps30, ExportCodec.HEVC, sources))
    }

    @Test
    fun `usedSources reads rate, codec, size and audio from the clips`() {
        val a = video("a", 52.0, codec = "hevc", tenBit = true).copy(hasAudio = true)
        val s = used(a)
        assertEquals(1, s.videoClips)
        assertTrue(s.anyHevc)
        assertTrue(s.anyTenBitOrHdr)
        assertTrue(s.hasAudio)
        assertEquals(2160, s.maxShortSide)
    }

    // region size

    @Test
    fun `the measured 35 Mbps export of 714 seconds comes out at 3_1 GB`() {
        // 714.85 s at 30 fps is 21445.5 frames; 21446 frames is the same within a frame.
        val e = estimateExportSize(21_446, fps30, 35)!!
        // The real file was 3.13 GB, audio and container included: the estimate (audio and 1% container on top) is about 1.5% high.
        assertEquals("3.2 GB", formatBytes(e.bytes, Locale.US))
        assertEquals(3.13e9, e.bytes.toDouble(), 0.06e9)
    }

    @Test
    fun `80 Mbps for 714 seconds with audio`() {
        val e = estimateExportSize(21_446, fps30, 80)!!
        // (80 + 0.192) Mbit/s * 714.87 s / 8 * 1.01
        assertEquals(7.24e9, e.bytes.toDouble(), 0.03e9)
        assertEquals(e.bytes * 0.8, e.lowBytes.toDouble(), e.bytes * 0.001)
        assertEquals(e.bytes * 1.1, e.highBytes.toDouble(), e.bytes * 0.001)
    }

    @Test
    fun `no audio track means no audio bytes, and an empty movie has no estimate`() {
        val with = estimateExportSize(3000, fps30, 8)!!.bytes
        val without = estimateExportSize(3000, fps30, 8, audioBitrate = 0)!!.bytes
        assertTrue(with > without)
        // 100 s at 8 Mbit/s is 100 MB, plus 1%.
        assertEquals(101_000_000L, without)
        assertNull(estimateExportSize(0, fps30, 8))
    }

    @Test
    fun `fractional frame rates use integer maths`() {
        val e = estimateExportSize(30_000, FrameRate(30000, 1001), 10, audioBitrate = 0)!!
        // 30000 frames at 29.97 fps is exactly 1001 s: 1.25 MB per second.
        assertEquals(1_251_250_000L * 101 / 100, e.bytes)
    }

    @Test
    fun `sizes are formatted with one decimal in GB and whole MB`() {
        assertEquals("3.1 GB", formatBytes(3_130_000_000, Locale.US))
        assertEquals("640 MB", formatBytes(640_000_000, Locale.US))
        assertEquals("4.5 MB", formatBytes(4_500_000, Locale.US))
    }

    @Test
    fun `the free space warning starts above 90 percent`() {
        val e = SizeEstimate(9_000_000_000, 0, 0)
        assertFalse(exceedsFreeSpace(e, 10_000_000_000))
        assertTrue(exceedsFreeSpace(e.copy(bytes = 9_100_000_000), 10_000_000_000))
        assertFalse(exceedsFreeSpace(e, null))
    }

    // endregion

    @Test
    fun `bit rate falls back to the file average less the audio track`() {
        assertEquals(52_000_000L, VideoFacts.bitrate(52_000_000, 0, 1, 1))
        // 100 MB over 10 s is 80 Mbit/s overall; 192 kbit/s of it is audio.
        assertEquals(79_808_000L, VideoFacts.bitrate(null, 192_000, 100_000_000, 10_000_000))
        assertNull(VideoFacts.bitrate(null, 0, null, 10_000_000))
        assertNull(VideoFacts.bitrate(null, 0, 100, 0))
        assertNull(VideoFacts.bitrate(null, 200_000_000, 100, 10_000_000))
    }
}
