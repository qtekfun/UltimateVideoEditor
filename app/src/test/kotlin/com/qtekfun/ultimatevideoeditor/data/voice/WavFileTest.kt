package com.qtekfun.ultimatevideoeditor.data.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Test

class WavFileTest {
    private fun header(bytes: ByteArray) = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    @Test
    fun `the header says what a player needs to read a mono 48 kHz take`() {
        val bytes = WavHeader.build(48_000, 1, 96_000)
        assertEquals(WavHeader.SIZE, bytes.size)
        val h = header(bytes)
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals(36 + 96_000, h.getInt(4))
        assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(bytes, 12, 4, Charsets.US_ASCII))
        assertEquals(16, h.getInt(16))
        assertEquals(1, h.getShort(20).toInt())
        assertEquals(1, h.getShort(22).toInt())
        assertEquals(48_000, h.getInt(24))
        assertEquals(96_000, h.getInt(28))
        assertEquals(2, h.getShort(32).toInt())
        assertEquals(16, h.getShort(34).toInt())
        assertEquals("data", String(bytes, 36, 4, Charsets.US_ASCII))
        assertEquals(96_000, h.getInt(40))
    }

    @Test
    fun `stereo doubles the block size and the byte rate`() {
        val h = header(WavHeader.build(44_100, 2, 0))
        assertEquals(4, h.getShort(32).toInt())
        assertEquals(176_400, h.getInt(28))
    }

    @Test
    fun `an empty take and a take too long for a wav stay valid`() {
        assertEquals(0, header(WavHeader.build(48_000, 1, -5)).getInt(40))
        assertEquals(WavHeader.MAX_DATA.toInt(), header(WavHeader.build(48_000, 1, Long.MAX_VALUE)).getInt(40))
        assertArrayEquals(WavHeader.build(48_000, 1, 10), WavHeader.build(48_000, 1, 10))
    }

    @Test
    fun `the duration follows the byte count`() {
        assertEquals(1000, WavHeader.durationMs(96_000, 48_000, 1))
        assertEquals(500, WavHeader.durationMs(96_000, 48_000, 2))
        assertEquals(0, WavHeader.durationMs(0, 48_000, 1))
        assertEquals(0, WavHeader.durationMs(10, 0, 1))
        // Twenty minutes still fits an Int of bytes? No: it must not overflow in the arithmetic.
        assertEquals(20 * 60 * 1000L, WavHeader.durationMs(20L * 60 * 96_000, 48_000, 1))
    }

    @Test
    fun `the meter reads the highest peak and treats the most negative sample as full scale`() {
        assertEquals(0f, PeakMeter.peakOf(shortArrayOf(), 0), 0f)
        assertEquals(0.5f, PeakMeter.peakOf(shortArrayOf(100, -16384, 200), 3), 0.0001f)
        assertEquals(1f, PeakMeter.peakOf(shortArrayOf(Short.MIN_VALUE), 1), 0.0001f)
        // Only the samples counted are read.
        assertEquals(0f, PeakMeter.peakOf(shortArrayOf(0, 30000), 1), 0f)
        assertEquals(0f, PeakMeter.peakOf(shortArrayOf(0), 99), 0f)
    }
}
