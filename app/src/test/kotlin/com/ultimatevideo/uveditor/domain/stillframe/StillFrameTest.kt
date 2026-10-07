package com.ultimatevideo.uveditor.domain.stillframe

import com.ultimatevideo.uveditor.domain.FrameRate
import com.ultimatevideo.uveditor.domain.TrackType
import com.ultimatevideo.uveditor.domain.clip
import com.ultimatevideo.uveditor.domain.timeline
import com.ultimatevideo.uveditor.domain.track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.time.LocalDateTime

class StillFrameTest {

    // ---- size ----

    @Test
    fun `the project size is saved as it is, odd sizes included`() {
        assertEquals(FrameTarget(FrameSize(3840, 2160), false), frameTarget(3840, 2160))
        assertEquals(FrameTarget(FrameSize(1081, 607), false), frameTarget(1081, 607))
        assertEquals(FrameTarget(FrameSize(4096, 4096), false), frameTarget(4096, 4096))
    }

    @Test
    fun `a project larger than the limit is scaled down keeping its shape and says so`() {
        assertEquals(FrameTarget(FrameSize(4096, 2304), true), frameTarget(7680, 4320))
        assertEquals(FrameTarget(FrameSize(2304, 4096), true), frameTarget(4320, 7680))
        assertEquals(1, frameTarget(40_000, 1).size.height)
    }

    // ---- names ----

    private val fps60 = FrameRate(60, 1)
    private val at = LocalDateTime.of(2026, 10, 7, 9, 5, 3)

    @Test
    fun `the timecode is hours minutes seconds and frames`() {
        assertEquals("00h00m00s00f", frameTimecode(0, fps60))
        assertEquals("00h01m23s12f", frameTimecode(83L * 60 + 12, fps60))
        assertEquals("01h00m00s59f", frameTimecode(3600L * 60 + 59, fps60))
        // 29.97 fps counts frames in a second as 30.
        assertEquals("00h00m01s00f", frameTimecode(30, FrameRate(30000, 1001)))
    }

    @Test
    fun `the file name is project, timecode and the time the frame was saved`() {
        assertEquals("My_movie_00h01m23s12f_20261007-090503.jpg", frameFileName("My movie", 83L * 60 + 12, fps60, at))
    }

    @Test
    fun `the project name is cleaned for the gallery`() {
        assertEquals("a_b_00h00m00s00f_20261007-090503.jpg", frameFileName(" a/b ", 0, fps60, at))
        assertEquals("Review_IPhone_18_Pro_Max_00h00m00s00f_20261007-090503.jpg", frameFileName("Review IPhone 18 Pro Max", 0, fps60, at))
        assertEquals("a_b_c_d_00h00m00s00f_20261007-090503.jpg", frameFileName("a:b*c?\"d", 0, fps60, at))
        assertEquals("ultimateVE_00h00m00s00f_20261007-090503.jpg", frameFileName("  ", 0, fps60, at))
        assertEquals("ultimateVE_00h00m00s00f_20261007-090503.jpg", frameFileName("...", 0, fps60, at))
        assertEquals("Café_ñ_00h00m00s00f_20261007-090503.jpg", frameFileName("Café ñ", 0, fps60, at))
    }

    @Test
    fun `a long project name is cut`() {
        val name = frameFileName("x".repeat(200), 0, fps60, at)

        assertEquals(60 + "_00h00m00s00f_20261007-090503.jpg".length, name.length)
    }

    // ---- what the frame shows ----

    private val twoClips = timeline(
        track("v1", clip("c1", 0, 30), clip("c2", 60, 30)), // a gap at frames 30..59
        track("a1", clip("au", 0, 200), type = TrackType.AUDIO),
    )

    @Test
    fun `frame zero and the first and last frame of a clip are pictures`() {
        assertEquals(FrameContent.PICTURE, frameContent(twoClips, 0))
        assertEquals(FrameContent.PICTURE, frameContent(twoClips, 29))
        assertEquals(FrameContent.PICTURE, frameContent(twoClips, 60))
        assertEquals(FrameContent.PICTURE, frameContent(twoClips, 89))
    }

    @Test
    fun `a gap is a black frame and so is anything past the end`() {
        assertEquals(FrameContent.GAP, frameContent(twoClips, 30))
        assertEquals(FrameContent.GAP, frameContent(twoClips, 59))
        assertEquals(FrameContent.GAP, frameContent(twoClips, 90))
        assertEquals(FrameContent.PAST_END, frameContent(twoClips, 200))
        assertEquals(FrameContent.PAST_END, frameContent(twoClips, 10_000))
    }

    @Test
    fun `a timeline without pictures is empty`() {
        assertEquals(FrameContent.EMPTY, frameContent(timeline(), 0))
        assertEquals(FrameContent.EMPTY, frameContent(timeline(track("a1", clip("au", 0, 100), type = TrackType.AUDIO)), 5))
    }

    @Test
    fun `only a black frame carries a notice`() {
        assertNull(frameNotice(FrameContent.PICTURE))
        assertNotNull(frameNotice(FrameContent.GAP))
        assertNotNull(frameNotice(FrameContent.PAST_END))
        assertNotNull(frameNotice(FrameContent.EMPTY))
    }

    // ---- the guard against a flat picture ----

    private fun picture(w: Int, h: Int, fill: (Int) -> Int): ByteBuffer {
        val b = ByteBuffer.allocateDirect(w * h * 4)
        for (i in 0 until w * h) b.putInt(i * 4, fill(i))
        return b
    }

    @Test
    fun `an all zero picture is uniform`() {
        assertTrue(isUniformPicture(ByteBuffer.allocateDirect(64 * 4), 8, 8))
    }

    @Test
    fun `black with opaque alpha is uniform too`() {
        assertTrue(isUniformPicture(picture(16, 9) { 0x000000FF }, 16, 9))
    }

    @Test
    fun `one different pixel anywhere makes it a picture`() {
        assertFalse(isUniformPicture(picture(16, 9) { if (it == 0) 1 else 0 }, 16, 9))
        assertFalse(isUniformPicture(picture(16, 9) { if (it == 16 * 9 - 1) 1 else 0 }, 16, 9))
        assertFalse(isUniformPicture(picture(16, 9) { it }, 16, 9))
    }

    @Test
    fun `a single flat colour that is not black is uniform as well`() {
        assertTrue(isUniformPicture(picture(4, 4) { 0x7F7F7FFF }, 4, 4))
    }

    @Test
    fun `a one pixel picture is uniform and a short buffer is refused`() {
        assertTrue(isUniformPicture(picture(1, 1) { 5 }, 1, 1))
        val short = runCatching { isUniformPicture(ByteBuffer.allocateDirect(8), 4, 4) }
        assertTrue(short.isFailure)
    }
}
