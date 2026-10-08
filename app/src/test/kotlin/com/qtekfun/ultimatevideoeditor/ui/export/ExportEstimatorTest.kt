package com.qtekfun.ultimatevideoeditor.ui.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ExportEstimatorTest {
    private fun estimator() = ExportEstimator(totalFrames = 1800, movieSeconds = 60.0, startedAtMs = 0)

    /** Feeds a steady rate: [permillePerSecond] for [seconds], one sample per 0.5 s. */
    private fun ExportEstimator.run(permillePerSecond: Int, seconds: Int, from: Int = 0, startSecond: Int = 0): ExportEstimate {
        var last = ExportEstimate()
        for (step in 1..seconds * 2) {
            val t = (startSecond * 1000L) + step * 500L
            last = onProgress(from + (permillePerSecond * step / 2), t)
        }
        return last
    }

    @Test
    fun `nothing is shown at the start`() {
        val e = estimator()
        assertEquals(ExportEstimate(), e.onProgress(0, 0))
        assertEquals(ExportEstimate(), e.onProgress(10, 500))
        assertEquals(ExportEstimate(), e.estimate(1_000))
    }

    @Test
    fun `a steady rate gives an accurate remaining time and throughput`() {
        val e = estimator()
        val result = e.run(permillePerSecond = 50, seconds = 10) // 500 permille after 10 s
        val remaining = checkNotNull(result.remainingMs)
        assertTrue("remaining was $remaining", abs(remaining - 10_000) < 500)
        // 900 frames in 10 s, and 30 s of movie in 10 s.
        assertEquals(90.0, checkNotNull(result.framesPerSecond), 0.5)
        assertEquals(3.0, checkNotNull(result.speedFactor), 0.05)
        assertFalse(result.stalled)
    }

    @Test
    fun `a speed-up pulls the estimate down smoothly`() {
        val e = estimator()
        e.run(permillePerSecond = 20, seconds = 5) // 100 permille
        val before = checkNotNull(e.estimate(5_000).remainingMs)
        val after = checkNotNull(e.run(permillePerSecond = 100, seconds = 3, from = 100, startSecond = 5).remainingMs)
        assertTrue("$after < $before", after < before)
        assertTrue("the estimate must not collapse: $after", after > 1_000)
    }

    @Test
    fun `a stall hides the remaining time instead of guessing`() {
        val e = estimator()
        e.run(permillePerSecond = 50, seconds = 6) // 300 permille at 6 s
        val stalled = e.estimate(6_000 + ExportEstimator.STALL_MS + 1)
        assertTrue(stalled.stalled)
        assertNull(stalled.remainingMs)
        val resumed = e.onProgress(320, 6_000 + ExportEstimator.STALL_MS + 1_000)
        assertFalse(resumed.stalled)
    }

    @Test
    fun `no stall is reported before any progress`() {
        assertFalse(estimator().estimate(60_000).stalled)
    }

    @Test
    fun `the end reports zero remaining`() {
        val e = estimator()
        e.run(permillePerSecond = 100, seconds = 10)
        assertEquals(0L, e.onProgress(1000, 10_500).remainingMs)
    }

    @Test
    fun `progress going backwards is ignored and out of range values are clamped`() {
        val e = estimator()
        e.run(permillePerSecond = 50, seconds = 5)
        e.onProgress(100, 6_000) // a glitch: never lowers the high-water mark
        assertTrue(checkNotNull(e.estimate(6_000).remainingMs) > 0)
        assertEquals(0L, e.onProgress(5000, 6_500).remainingMs)
    }

    @Test
    fun `a very slow export is capped at a day`() {
        val e = ExportEstimator(totalFrames = 10, movieSeconds = 1.0, startedAtMs = 0)
        for (i in 1..8) e.onProgress(i, i * 10_000_000L)
        assertEquals(ExportEstimator.MAX_REMAINING_MS, e.estimate(80_000_000L).remainingMs)
    }

    @Test
    fun `durations are rounded so they do not flicker`() {
        assertEquals("8 s", formatDuration(7_600))
        assertEquals("1:05", formatDuration(65_000))
        assertEquals("2:00", formatDuration(122_900))
        assertEquals("2:05", formatDuration(126_000))
        assertEquals("1:02:05", formatDuration(3_725_000))
    }
}
