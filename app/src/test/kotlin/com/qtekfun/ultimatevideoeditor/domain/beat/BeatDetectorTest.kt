package com.qtekfun.ultimatevideoeditor.domain.beat

import kotlin.math.abs
import kotlin.math.exp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BeatDetectorTest {

    /** A click every [period] seconds from [first], each decaying over ~50 ms on a quiet floor. */
    private fun clicks(seconds: Double, bpm: Double, first: Double, binsPerSecond: Double = 750.0, floor: Float = 0.02f): PeakEnvelope {
        val values = FloatArray((seconds * binsPerSecond).toInt())
        val period = 60.0 / bpm
        for (i in values.indices) {
            val t = i / binsPerSecond
            val since = if (t < first) Double.MAX_VALUE else (t - first) % period
            values[i] = floor + (0.9 * exp(-since / 0.05)).toFloat()
        }
        return PeakEnvelope(binsPerSecond, values)
    }

    private fun assertBeatsOn(grid: BeatGrid, bpm: Double, first: Double, toleranceMillis: Long = 25) {
        val period = 60.0 / bpm
        for (micros in grid.beatsMicros) {
            val seconds = micros / 1_000_000.0
            val beatIndex = Math.round((seconds - first) / period)
            val expected = first + beatIndex * period
            assertTrue("beat at $seconds s is ${abs(seconds - expected) * 1000} ms off the grid", abs(seconds - expected) * 1000 <= toleranceMillis)
        }
    }

    @Test
    fun `finds a 120 BPM click track and places beats on the clicks`() {
        val grid = assertNotNull(BeatDetector.analyze(clicks(30.0, 120.0, 0.25)))
        assertEquals(120.0, grid.bpm, 2.0)
        assertBeatsOn(grid, 120.0, 0.25)
        assertTrue("found ${grid.beatsMicros.size} beats", grid.beatsMicros.size in 55..60)
        assertEquals(250_000.0, grid.beatsMicros.first().toDouble(), 25_000.0)
        assertTrue(grid.confidence >= BeatDetector.MIN_CONFIDENCE)
    }

    @Test
    fun `finds a 90 BPM track that starts off the bar`() {
        val grid = assertNotNull(BeatDetector.analyze(clicks(40.0, 90.0, 0.13)))
        assertEquals(90.0, grid.bpm, 2.0)
        assertBeatsOn(grid, 90.0, 0.13)
    }

    @Test
    fun `a 75 BPM track is found at its own tempo`() {
        val grid = assertNotNull(BeatDetector.analyze(clicks(40.0, 75.0, 0.4)))
        assertEquals(75.0, grid.bpm, 2.0)
        assertBeatsOn(grid, 75.0, 0.4)
    }

    @Test
    fun `beats are spaced one period apart and strictly increasing`() {
        val grid = assertNotNull(BeatDetector.analyze(clicks(20.0, 100.0, 0.1)))
        val gaps = grid.beatsMicros.zipWithNext { a, b -> b - a }
        assertTrue(gaps.all { it > 0 })
        assertTrue(gaps.all { abs(it - 600_000L) <= 40_000L })
    }

    @Test
    fun `silence has no beat`() {
        assertNull(BeatDetector.analyze(PeakEnvelope(750.0, FloatArray(750 * 30))))
    }

    @Test
    fun `steady noise has no clear beat`() {
        var seed = 12345L
        val values = FloatArray(750 * 30) {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            0.3f + ((seed ushr 40) % 100).toFloat() / 2000f
        }
        assertNull(BeatDetector.analyze(PeakEnvelope(750.0, values)))
    }

    @Test
    fun `audio shorter than a few seconds is not analysed`() {
        assertNull(BeatDetector.analyze(clicks(2.0, 120.0, 0.0)))
    }

    @Test
    fun `works with other envelope resolutions`() {
        val grid = assertNotNull(BeatDetector.analyze(clicks(30.0, 120.0, 0.25, binsPerSecond = 375.0)))
        assertEquals(120.0, grid.bpm, 2.0)
    }

    private fun <T : Any> assertNotNull(value: T?): T {
        org.junit.Assert.assertNotNull("expected a beat grid", value)
        return value!!
    }
}
