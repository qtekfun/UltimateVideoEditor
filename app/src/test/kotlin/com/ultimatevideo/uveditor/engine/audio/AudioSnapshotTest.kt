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

    /** The voice blocks and voice lanes at the end (no voice lanes): a block and a lane count per clip, then the lane byte size. */
    private fun voiceTail(clips: Int) = clips * (AudioSnapshot.VOICE_BYTES + 4) + 4

    /** Byte offset of the first clip record for a snapshot with [tracks] tracks. */
    private fun clipsAt(tracks: Int = 1) = AudioSnapshot.HEADER_BYTES + tracks * AudioSnapshot.TRACK_BYTES + AudioSnapshot.DUCKING_BYTES

    @Test
    fun `encodes the layout the native parser expects`() {
        val buffer = AudioSnapshot(30000, 1001, listOf(clip(7, start = 10, duration = 20, sourceIn = 5, gainDb = -6f))).encode()

        assertEquals(ByteOrder.LITTLE_ENDIAN, buffer.order())
        assertEquals(clipsAt() + AudioSnapshot.CLIP_BYTES + voiceTail(1), buffer.remaining())
        assertEquals(AudioSnapshot.MAGIC, buffer.getInt(0))
        assertEquals(AudioSnapshot.VERSION, buffer.getInt(4))
        assertEquals(30000, buffer.getInt(8))
        assertEquals(1001, buffer.getInt(12))
        assertEquals(1, buffer.getInt(16))
        assertEquals(1, buffer.getInt(20)) // one default track
        assertEquals(0, buffer.getInt(24)) // limiter on
        val base = clipsAt()
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

        assertEquals(12, buffer.getInt(clipsAt() + 52))
        assertEquals(40, buffer.getInt(clipsAt() + 56))
    }

    @Test
    fun `empty snapshot is the header, the default track and the ducking block`() {
        assertEquals(clipsAt() + voiceTail(0), AudioSnapshot(30, 1, emptyList()).encode().remaining())
    }

    @Test
    fun `track records and the ducking block are encoded`() {
        val tracks = listOf(
            AudioTrackSpec(trackKey = 77, gainDb = -3f, muted = true, role = TrackRole.VOICE),
            AudioTrackSpec(trackKey = 78, role = TrackRole.MUSIC, compressor = CompressorSpec(-24f, 4f, 5f, 80f, 2f)),
        )
        val buffer = AudioSnapshot(30, 1, listOf(clip().copy(trackIndex = 1)), tracks, DuckingSpec(9f, -40f, 15f, 250f), limiterOn = false).encode()

        assertEquals(2, buffer.getInt(20))
        assertEquals(1, buffer.getInt(24)) // limiter off
        val t0 = AudioSnapshot.HEADER_BYTES
        assertEquals(77L, buffer.getLong(t0))
        assertEquals(-3f, buffer.getFloat(t0 + 8), 0f)
        assertEquals(1, buffer.getInt(t0 + 12)) // muted, no compressor
        assertEquals(1, buffer.getInt(t0 + 16)) // voice
        val t1 = t0 + AudioSnapshot.TRACK_BYTES
        assertEquals(78L, buffer.getLong(t1))
        assertEquals(2, buffer.getInt(t1 + 12)) // compressor on
        assertEquals(2, buffer.getInt(t1 + 16)) // music
        assertEquals(-24f, buffer.getFloat(t1 + 20), 0f)
        assertEquals(4f, buffer.getFloat(t1 + 24), 0f)
        assertEquals(5f, buffer.getFloat(t1 + 28), 0f)
        assertEquals(80f, buffer.getFloat(t1 + 32), 0f)
        assertEquals(2f, buffer.getFloat(t1 + 36), 0f)
        val duck = t0 + 2 * AudioSnapshot.TRACK_BYTES
        assertEquals(9f, buffer.getFloat(duck), 0f)
        assertEquals(-40f, buffer.getFloat(duck + 4), 0f)
        assertEquals(15f, buffer.getFloat(duck + 8), 0f)
        assertEquals(250f, buffer.getFloat(duck + 12), 0f)
        assertEquals(1, buffer.getInt(clipsAt(2) + 64)) // the clip's track index
    }

    @Test
    fun `the clip audio block carries pan, handles, EQ and noise suppression`() {
        val eq = EqSpec(
            highPassHz = 80f,
            lowPassHz = 12000f,
            bands = EqSpec.DEFAULT_BANDS.mapIndexed { i, b -> if (i == 2) b.copy(freqHz = 3000f, gainDb = 4.5f, q = 2f) else b },
        )
        val profile = List(AudioClipSpec.NOISE_PROFILE_BINS) { it / 1000f }
        val spec = clip(duration = 60).copy(pan = -0.25f, userFadeInFrames = 5, userFadeOutFrames = 60, eq = eq, denoiseStrength = 0.6f, noiseProfile = profile)
        val buffer = AudioSnapshot(30, 1, listOf(spec)).encode()

        val a = clipsAt() + 64
        assertEquals(0, buffer.getInt(a))
        assertEquals(-0.25f, buffer.getFloat(a + 4), 0f)
        assertEquals(5, buffer.getInt(a + 8))
        assertEquals(60, buffer.getInt(a + 12))
        assertEquals(80f, buffer.getFloat(a + 16), 0f)
        assertEquals(12000f, buffer.getFloat(a + 20), 0f)
        val band2 = a + 24 + 2 * 12
        assertEquals(3000f, buffer.getFloat(band2), 0f)
        assertEquals(4.5f, buffer.getFloat(band2 + 4), 0f)
        assertEquals(2f, buffer.getFloat(band2 + 8), 0f)
        assertEquals(0.6f, buffer.getFloat(a + 84), 0f)
        assertEquals(AudioClipSpec.NOISE_PROFILE_BINS, buffer.getInt(a + 88))
        // The profile follows the clip table (no knots here): 513 floats at the very end.
        val profileAt = clipsAt() + AudioSnapshot.CLIP_BYTES
        assertEquals(profileAt + AudioClipSpec.NOISE_PROFILE_BINS * 4 + voiceTail(1), buffer.remaining())
        assertEquals(0.512f, buffer.getFloat(profileAt + 512 * 4), 0f)
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
    fun `rejects audio tool values the native side would refuse`() {
        assertThrows(IllegalArgumentException::class.java) { clip().copy(pan = 1.5f) }
        assertThrows(IllegalArgumentException::class.java) { clip().copy(pan = Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { clip(duration = 10).copy(userFadeInFrames = 11) }
        assertThrows(IllegalArgumentException::class.java) { clip(duration = 10).copy(userFadeOutFrames = -1) }
        assertThrows(IllegalArgumentException::class.java) { clip().copy(trackIndex = -1) }
        assertThrows(IllegalArgumentException::class.java) { clip().copy(denoiseStrength = 2f) }
        assertThrows(IllegalArgumentException::class.java) { clip().copy(denoiseStrength = 0.5f) } // no profile
        assertThrows(IllegalArgumentException::class.java) { clip().copy(noiseProfile = List(AudioClipSpec.NOISE_PROFILE_BINS) { 0.1f }) } // profile without strength
        assertThrows(IllegalArgumentException::class.java) { clip().copy(denoiseStrength = 0.5f, noiseProfile = List(10) { 0.1f }) }
        assertThrows(IllegalArgumentException::class.java) { clip().copy(denoiseStrength = 0.5f, noiseProfile = List(AudioClipSpec.NOISE_PROFILE_BINS) { -1f }) }
        assertThrows(IllegalArgumentException::class.java) { EqBandSpec(5f) }
        assertThrows(IllegalArgumentException::class.java) { EqBandSpec(1000f, gainDb = 30f) }
        assertThrows(IllegalArgumentException::class.java) { EqBandSpec(1000f, q = 0f) }
        assertThrows(IllegalArgumentException::class.java) { EqSpec(highPassHz = 5f) }
        assertThrows(IllegalArgumentException::class.java) { EqSpec(bands = EqSpec.DEFAULT_BANDS.take(3)) }
        assertThrows(IllegalArgumentException::class.java) { CompressorSpec(ratio = 0.5f) }
        assertThrows(IllegalArgumentException::class.java) { DuckingSpec(amountDb = 60f) }
        assertThrows(IllegalArgumentException::class.java) { DuckingSpec(amountDb = 6f, attackMs = 0f) }
        assertThrows(IllegalArgumentException::class.java) { AudioTrackSpec(1, gainDb = 90f) }
        // A clip pointing at a track that is not listed.
        assertThrows(IllegalArgumentException::class.java) { AudioSnapshot(30, 1, listOf(clip().copy(trackIndex = 1))) }
        assertThrows(IllegalArgumentException::class.java) { AudioSnapshot(30, 1, emptyList(), tracks = emptyList()) }
    }

    @Test
    fun `retime knots follow the clips after the clip table`() {
        val plain = clip(1, start = 0, duration = 30)
        val fast = clip(2, start = 40, duration = 30).copy(retimeKnots = listOf(RetimeKnot(0, 10.0), RetimeKnot(30, 70.5)))
        val buffer = AudioSnapshot(30, 1, listOf(plain, fast)).encode()

        assertEquals(clipsAt() + 2 * AudioSnapshot.CLIP_BYTES + 2 * AudioSnapshot.KNOT_BYTES + voiceTail(2), buffer.remaining())
        val reservedAt = { index: Int -> clipsAt() + index * AudioSnapshot.CLIP_BYTES + 60 }
        assertEquals(0, buffer.getInt(reservedAt(0)))
        assertEquals(2, buffer.getInt(reservedAt(1)))
        val knots = clipsAt() + 2 * AudioSnapshot.CLIP_BYTES
        assertEquals(0L, buffer.getLong(knots))
        assertEquals(10.0, buffer.getDouble(knots + 8), 0.0)
        assertEquals(30L, buffer.getLong(knots + 16))
        assertEquals(70.5, buffer.getDouble(knots + 24), 0.0)
    }

    @Test
    fun `retime knots are validated`() {
        fun withKnots(duration: Long, vararg knots: RetimeKnot) = clip(duration = duration).copy(retimeKnots = knots.toList())
        withKnots(30, RetimeKnot(0, 0.0), RetimeKnot(30, 60.0)) // valid
        assertThrows(IllegalArgumentException::class.java) { withKnots(30, RetimeKnot(0, 0.0)) }
        assertThrows(IllegalArgumentException::class.java) { withKnots(30, RetimeKnot(1, 0.0), RetimeKnot(30, 60.0)) }
        assertThrows(IllegalArgumentException::class.java) { withKnots(30, RetimeKnot(0, 0.0), RetimeKnot(20, 60.0)) }
        assertThrows(IllegalArgumentException::class.java) { withKnots(30, RetimeKnot(0, 0.0), RetimeKnot(15, 1.0), RetimeKnot(15, 2.0), RetimeKnot(30, 3.0)) }
        assertThrows(IllegalArgumentException::class.java) { withKnots(30, RetimeKnot(0, 0.0), RetimeKnot(30, Double.NaN)) }
    }

    @Test
    fun `error codes map back from native values`() {
        assertEquals(AudioErrorCode.BadSnapshot, AudioErrorCode.fromValue(2))
        assertEquals(AudioErrorCode.DeviceError, AudioErrorCode.fromValue(100))
        assertEquals(AudioErrorCode.Unknown, AudioErrorCode.fromValue(12345))
        assertEquals(AudioErrorCode.CodecError, AudioException(5, "x").errorCode)
    }

    @Test
    fun `the fade shape rides in bits 16 and 17 of the lane count word`() {
        val lane = AutomationLane(AutoParam.GAIN_DB, listOf(AutoPoint(0, 0f), AutoPoint(10, -6f)))
        val spec = clip(duration = 60).copy(userFadeInFrames = 5, fadeShape = 2, automation = listOf(lane))
        val buffer = AudioSnapshot(30, 1, listOf(spec)).encode()
        val word = buffer.getInt(clipsAt() + 64 + 92)
        assertEquals(1, word and 0xFFFF) // one lane
        assertEquals(2, (word ushr AudioSnapshot.FADE_SHAPE_SHIFT) and 3)
        // The default shape leaves the word as it always was.
        assertEquals(0, AudioSnapshot(30, 1, listOf(clip(duration = 60))).encode().getInt(clipsAt() + 64 + 92))
        assertThrows(IllegalArgumentException::class.java) { clip(duration = 10).copy(fadeShape = 4) }
    }
}
