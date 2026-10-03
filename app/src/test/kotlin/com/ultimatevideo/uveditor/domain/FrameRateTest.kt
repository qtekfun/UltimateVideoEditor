package com.ultimatevideo.uveditor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FrameRateTest {
    private val ntsc60 = FrameRate(60000, 1001)
    private val fps25 = FrameRate(25, 1)

    @Test
    fun `frames to micros rounds half up`() {
        assertEquals(0L, ntsc60.framesToMicros(0))
        assertEquals(16_683L, ntsc60.framesToMicros(1)) // 16683.33
        assertEquals(1_001_000L, ntsc60.framesToMicros(60)) // exact
        assertEquals(40_000L, fps25.framesToMicros(1))
    }

    @Test
    fun `micros to frames floors to the containing frame`() {
        assertEquals(0L, ntsc60.microsToFrames(16_683))
        assertEquals(1L, ntsc60.microsToFrames(16_684))
        assertEquals(60L, ntsc60.microsToFrames(1_001_000))
        assertEquals(59L, ntsc60.microsToFrames(1_000_999))
    }

    @Test
    fun `micros round trip stays within one frame and is exact on whole seconds`() {
        // Rounded microseconds can land just below the frame boundary, so the floor may be one frame short.
        for (frame in 0L..216_000L step 7) {
            val back = ntsc60.microsToFrames(ntsc60.framesToMicros(frame))
            assertEquals("frame $frame", true, back == frame || back == frame - 1)
        }
        for (frame in 0L..216_000L step 60) {
            assertEquals(frame, ntsc60.microsToFrames(ntsc60.framesToMicros(frame)))
        }
    }

    @Test
    fun `audio samples follow the rational rate without drift`() {
        // 60 NTSC frames are exactly 1.001 s = 48048 samples at 48 kHz.
        assertEquals(48_048L, ntsc60.framesToSamples(60, 48_000))
        assertEquals(60L, ntsc60.samplesToFrames(48_048, 48_000))
        assertEquals(59L, ntsc60.samplesToFrames(48_047, 48_000))
        // 10 hours of NTSC video stays exact: 2_160_000 frames -> 1_729_728_000 / ... compare via round trip.
        val tenHours = 10L * 3600 * 60
        assertEquals(tenHours, ntsc60.samplesToFrames(ntsc60.framesToSamples(tenHours, 48_000), 48_000))
    }

    @Test
    fun `negative frames use floor semantics`() {
        assertEquals(-16_683L, ntsc60.framesToMicros(-1))
        assertEquals(-1L, ntsc60.microsToFrames(-1))
    }

    @Test
    fun `large values widen instead of overflowing`() {
        val big = 4_000_000_000_000L
        assertEquals(big * 40_000, fps25.framesToMicros(big))
    }

    @Test
    fun `result that cannot fit in Long throws`() {
        assertThrows(ArithmeticException::class.java) { fps25.framesToMicros(Long.MAX_VALUE) }
    }

    @Test
    fun `invalid rates and sample rates are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { FrameRate(0, 1) }
        assertThrows(IllegalArgumentException::class.java) { FrameRate(30, 0) }
        assertThrows(IllegalArgumentException::class.java) { fps25.framesToSamples(1, 0) }
    }

    @Test
    fun `frame index arithmetic is exact`() {
        assertEquals(f(15), f(10) + 5)
        assertEquals(f(5), f(10) - 5)
        assertEquals(7L, f(10) - f(3))
        assertThrows(ArithmeticException::class.java) { f(Long.MAX_VALUE) + 1 }
    }
}
