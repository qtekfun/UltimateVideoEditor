package com.ultimatevideo.uveditor.data

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamBytesTest {
    @Test
    fun `reads everything when the stream is shorter than the limit`() {
        val data = ByteArray(1000) { it.toByte() }
        assertArrayEquals(data, ByteArrayInputStream(data).readAtMost(5000))
    }

    @Test
    fun `stops at the limit`() {
        val data = ByteArray(20_000) { it.toByte() }
        val read = ByteArrayInputStream(data).readAtMost(10_001)
        assertEquals(10_001, read.size)
        assertArrayEquals(data.copyOf(10_001), read)
    }

    @Test
    fun `an empty stream and a zero limit give nothing`() {
        assertEquals(0, ByteArrayInputStream(ByteArray(0)).readAtMost(10).size)
        assertEquals(0, ByteArrayInputStream(ByteArray(10)).readAtMost(0).size)
    }

    @Test
    fun `short reads from the stream are accumulated`() {
        val data = ByteArray(5000) { (it % 251).toByte() }
        val stingy = object : InputStream() {
            private var pos = 0
            override fun read(): Int = if (pos < data.size) data[pos++].toInt() and 0xFF else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (pos >= data.size) return -1
                val n = minOf(3, len, data.size - pos)
                System.arraycopy(data, pos, b, off, n)
                pos += n
                return n
            }
        }
        assertArrayEquals(data, stingy.readAtMost(8000))
    }
}
