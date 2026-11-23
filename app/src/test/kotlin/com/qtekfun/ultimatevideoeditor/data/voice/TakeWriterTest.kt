package com.qtekfun.ultimatevideoeditor.data.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException

class TakeWriterTest {
    private class Rig {
        val out = ByteArrayOutputStream()
        val writer = TakeWriter(sampleRate = 1_000, channels = 1) { data, length -> out.write(data, 0, length) }
    }

    @Test
    fun `samples become little-endian 16-bit bytes and the clock counts them`() {
        val r = Rig()
        assertTrue(r.writer.write(shortArrayOf(1, -2, 0x1234), 3))
        assertArrayEquals(byteArrayOf(1, 0, -2, -1, 0x34, 0x12), r.out.toByteArray())
        assertEquals(6L, r.writer.bytes)
        // 1000 Hz mono: 3 samples are 3 ms.
        assertEquals(3L, r.writer.elapsedMs)
    }

    @Test
    fun `while paused the sound is dropped, the clock holds still and the meter is quiet`() {
        val r = Rig()
        r.writer.write(ShortArray(500) { 16384 }, 500)
        assertEquals(0.5f, r.writer.peak, 0.001f)
        r.writer.paused = true
        assertFalse(r.writer.write(ShortArray(300) { 32767 }, 300))
        assertEquals(500L, r.writer.elapsedMs)
        assertEquals(1_000, r.out.size())
        assertEquals(0f, r.writer.peak, 0f)
    }

    @Test
    fun `after a resume the sound is appended to the same take, with no gap in it`() {
        val r = Rig()
        r.writer.write(shortArrayOf(10, 11), 2)
        r.writer.paused = true
        r.writer.write(shortArrayOf(99, 99, 99), 3)
        r.writer.paused = false
        r.writer.write(shortArrayOf(12, 13), 2)
        val expected = byteArrayOf(10, 0, 11, 0, 12, 0, 13, 0)
        assertArrayEquals(expected, r.out.toByteArray())
        assertEquals(4L, r.writer.elapsedMs)
    }

    @Test
    fun `a count larger than the buffer is cut to the buffer`() {
        val r = Rig()
        r.writer.write(shortArrayOf(1, 2), 10)
        assertEquals(4L, r.writer.bytes)
    }

    @Test
    fun `a sink that fails leaves the clock where it was`() {
        val writer = TakeWriter(1_000, 1) { _, _ -> throw IOException("full") }
        try {
            writer.write(shortArrayOf(1, 2), 2)
        } catch (_: IOException) {
        }
        assertEquals(0L, writer.bytes)
    }
}
