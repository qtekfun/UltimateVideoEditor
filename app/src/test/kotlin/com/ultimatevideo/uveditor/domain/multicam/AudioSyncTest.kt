package com.ultimatevideo.uveditor.domain.multicam

import com.ultimatevideo.uveditor.domain.beat.PeakEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

class AudioSyncTest {
    /** Speech-like loudness: random bursts of random length and level over a quiet floor. */
    private fun scene(seconds: Int, rate: Double, seed: Long): FloatArray {
        val random = Random(seed)
        val out = FloatArray((seconds * rate).toInt()) { 0.02f + random.nextFloat() * 0.02f }
        var t = 1.0
        while (t < seconds - 1) {
            val length = 0.1 + random.nextDouble() * 0.6
            val level = 0.2f + random.nextFloat() * 0.8f
            val from = (t * rate).toInt()
            val to = minOf(out.size, ((t + length) * rate).toInt())
            for (i in from until to) out[i] = level * (0.8f + random.nextFloat() * 0.2f)
            t += length + 0.2 + random.nextDouble() * 1.5
        }
        return out
    }

    /** What a second recorder hears of [scene] starting [offset] bins later (earlier if negative), quieter and noisier. */
    private fun recording(scene: FloatArray, offset: Int, length: Int, gain: Float, noise: Float, seed: Long): FloatArray {
        val random = Random(seed)
        return FloatArray(length) { m ->
            val source = m + offset
            val heard = if (source in scene.indices) scene[source] * gain else 0f
            (heard + noise * random.nextFloat()).coerceIn(0f, 1f)
        }
    }

    private fun syncSteps(offsetBins: Int, rate: Double, referenceSeconds: Int, otherSeconds: Int, noise: Float, seed: Long = 7): SyncResult {
        val reference = scene(referenceSeconds, rate, seed)
        val other = recording(reference, offsetBins, (otherSeconds * rate).toInt(), gain = 0.6f, noise = noise, seed = seed + 1)
        return checkNotNull(AudioSync.offsetOf(PeakEnvelope(rate, reference), PeakEnvelope(rate, other), 30, 1))
    }

    @Test
    fun `a later start is recovered exactly with noise and a different gain`() {
        val result = syncSteps(offsetBins = 237, rate = 100.0, referenceSeconds = 60, otherSeconds = 40, noise = 0.05f)
        assertEquals(237, result.offsetSteps)
        assertEquals(71L, result.offsetFrames) // 2.37 s at 30 fps
        assertTrue(result.isConfident)
        assertTrue(result.ncc > 0.6)
    }

    @Test
    fun `an earlier start gives a negative offset`() {
        // The other recorder was already rolling 3.1 s before the reference started.
        val reference = scene(50, 100.0, 11)
        val other = recording(reference, offset = -310, length = 4000, gain = 0.5f, noise = 0.05f, seed = 12)
        val result = checkNotNull(AudioSync.offsetOf(PeakEnvelope(100.0, reference), PeakEnvelope(100.0, other), 30, 1))
        assertEquals(-310, result.offsetSteps)
        assertEquals(-93L, result.offsetFrames)
        assertTrue(result.isConfident)
    }

    @Test
    fun `the same recording syncs at zero`() {
        val reference = scene(30, 100.0, 3)
        val result = checkNotNull(AudioSync.offsetOf(PeakEnvelope(100.0, reference), PeakEnvelope(100.0, reference.copyOf()), 30, 1))
        assertEquals(0, result.offsetSteps)
        assertEquals(0L, result.offsetFrames)
        assertTrue(result.ncc > 0.99)
    }

    @Test
    fun `an offset within a frame at 60 fps and at 29_97`() {
        val reference = scene(60, 100.0, 21)
        val other = recording(reference, offset = 1234, length = 3000, gain = 0.7f, noise = 0.04f, seed = 22)
        val a = checkNotNull(AudioSync.offsetOf(PeakEnvelope(100.0, reference), PeakEnvelope(100.0, other), 60, 1))
        val b = checkNotNull(AudioSync.offsetOf(PeakEnvelope(100.0, reference), PeakEnvelope(100.0, other), 30000, 1001))
        assertEquals(1234, a.offsetSteps)
        assertEquals(740L, a.offsetFrames) // 12.34 s * 60
        assertTrue(abs(b.offsetFrames - 12.34 * 30000.0 / 1001.0) <= 0.5) // within one frame of the true offset
    }

    @Test
    fun `envelopes at an awkward bin rate are resampled and recovered within a step or two`() {
        val rate = 44100.0 / 256.0
        val offsetBins = 800 // 4.644 s
        val result = syncSteps(offsetBins, rate, referenceSeconds = 60, otherSeconds = 45, noise = 0.04f, seed = 31)
        val expectedSteps = offsetBins * 100.0 / rate
        assertTrue("${result.offsetSteps} vs $expectedSteps", abs(result.offsetSteps - expectedSteps) <= 2.0)
        assertTrue(result.isConfident)
    }

    @Test
    fun `heavy noise lowers the confidence but keeps the offset`() {
        val clean = syncSteps(500, 100.0, 60, 40, noise = 0.02f, seed = 41)
        val noisy = syncSteps(500, 100.0, 60, 40, noise = 0.35f, seed = 41)
        assertEquals(500, noisy.offsetSteps)
        assertTrue(noisy.ncc < clean.ncc)
    }

    @Test
    fun `unrelated recordings are not trusted`() {
        val a = scene(60, 100.0, 51)
        val b = scene(60, 100.0, 52)
        val result = AudioSync.offsetOf(PeakEnvelope(100.0, a), PeakEnvelope(100.0, b), 30, 1)
        assertNotNull(result)
        assertFalse("unrelated audio must not look confident: ${result!!.confidence}", result.isConfident)
    }

    @Test
    fun `silence and very short audio have no answer`() {
        val loud = scene(30, 100.0, 61)
        assertNull(AudioSync.offsetOf(PeakEnvelope(100.0, loud), PeakEnvelope(100.0, FloatArray(3000)), 30, 1))
        assertNull(AudioSync.offsetOf(PeakEnvelope(100.0, loud), PeakEnvelope(100.0, FloatArray(100) { 0.5f }), 30, 1))
    }

    @Test
    fun `steps convert to frames with exact rounding`() {
        assertEquals(0L, AudioSync.stepsToFrames(0, 30, 1))
        assertEquals(3L, AudioSync.stepsToFrames(10, 30, 1))
        assertEquals(-3L, AudioSync.stepsToFrames(-10, 30, 1))
        assertEquals(1L, AudioSync.stepsToFrames(2, 25, 1)) // 0.5 frame rounds up
        assertEquals(0L, AudioSync.stepsToFrames(-2, 25, 1)) // -0.5 rounds up to 0
        assertEquals(60L, AudioSync.stepsToFrames(100, 60, 1))
    }

    @Test
    fun `the fft round trip returns the input`() {
        val re = DoubleArray(64) { Math.sin(it * 0.3) + (it % 5) }
        val im = DoubleArray(64)
        val original = re.copyOf()
        AudioSync.fft(re, im, false)
        AudioSync.fft(re, im, true)
        for (i in re.indices) assertEquals(original[i], re[i], 1e-9)
    }
}
