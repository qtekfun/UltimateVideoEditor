package com.ultimatevideo.uveditor.engine.audio

import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSnapshotTest {

    private fun clip(
        key: Long = 1,
        start: Long = 0,
        duration: Long = 30,
        sourceIn: Long = 0,
        gainDb: Float = 0f,
        fadeIn: Long = 0,
        fadeOut: Long = 0,
    ) = AudioClipSpec(
        key,
        assetKey = 9,
        start,
        duration,
        sourceIn,
        sourceFpsNum = 30000,
        sourceFpsDen = 1001,
        gainDb = gainDb,
        fadeInFrames = fadeIn,
        fadeOutFrames = fadeOut,
    )

    @Test
    fun `encodes the layout the native parser expects`() {
        val buffer = AudioSnapshot(30000, 1001, listOf(clip(7, start = 10, duration = 20, sourceIn = 5, gainDb = -6f))).encode()

        assertEquals(ByteOrder.LITTLE_ENDIAN, buffer.order())
        assertEquals(AudioSnapshot.HEADER_BYTES + AudioSnapshot.CLIP_BYTES, buffer.remaining())
        assertEquals(AudioSnapshot.MAGIC, buffer.getInt(0))
        assertEquals(AudioSnapshot.VERSION, buffer.getInt(4))
        assertEquals(30000, buffer.getInt(8))
        assertEquals(1001, buffer.getInt(12))
        assertEquals(1, buffer.getInt(16))
        val base = AudioSnapshot.HEADER_BYTES
        assertEquals(7L, buffer.getLong(base))
        assertEquals(9L, buffer.getLong(base + 8))
        assertEquals(10L, buffer.getLong(base + 16))
        assertEquals(20L, buffer.getLong(base + 24))
        assertEquals(5L, buffer.getLong(base + 32))
        assertEquals(30000, buffer.getInt(base + 40))
        assertEquals(1001, buffer.getInt(base + 44))
        assertEquals(-6f, buffer.getFloat(base + 48), 0f)
        assertEquals(0, buffer.getInt(base + 52))
        assertEquals(0, buffer.getInt(base + 56))
        assertTrue(buffer.isDirect)
    }

    @Test
    fun `crossfade lengths are encoded after the gain`() {
        val buffer = AudioSnapshot(30, 1, listOf(clip(duration = 40, fadeIn = 12, fadeOut = 40))).encode()

        assertEquals(12, buffer.getInt(AudioSnapshot.HEADER_BYTES + 52))
        assertEquals(40, buffer.getInt(AudioSnapshot.HEADER_BYTES + 56))
    }

    @Test
    fun `empty snapshot is just the header`() {
        assertEquals(AudioSnapshot.HEADER_BYTES, AudioSnapshot(30, 1, emptyList()).encode().remaining())
    }

    @Test
    fun `rejects values the native side would refuse`() {
        assertThrows(IllegalArgumentException::class.java) { clip(duration = 0) }
        assertThrows(IllegalArgumentException::class.java) { clip(start = -1) }
        assertThrows(IllegalArgumentException::class.java) { clip(sourceIn = -1) }
        assertThrows(IllegalArgumentException::class.java) { clip(gainDb = 60f) }
        assertThrows(IllegalArgumentException::class.java) { clip(gainDb = Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { clip(duration = 10, fadeIn = 11) }
        assertThrows(IllegalArgumentException::class.java) { clip(duration = 10, fadeOut = 11) }
        assertThrows(IllegalArgumentException::class.java) { clip(fadeIn = -1) }
        assertThrows(IllegalArgumentException::class.java) { AudioSnapshot(0, 1, emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { AudioSnapshot(30, 1, listOf(clip(key = 1), clip(key = 1, start = 40))) }
    }

    @Test
    fun `error codes map back from native values`() {
        assertEquals(AudioErrorCode.BadSnapshot, AudioErrorCode.fromValue(2))
        assertEquals(AudioErrorCode.DeviceError, AudioErrorCode.fromValue(100))
        assertEquals(AudioErrorCode.Unknown, AudioErrorCode.fromValue(12345))
        assertEquals(AudioErrorCode.CodecError, AudioException(5, "x").errorCode)
    }
}
