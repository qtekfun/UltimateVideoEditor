package com.ultimatevideo.uveditor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ParamTracksTest {

    private fun k(frame: Long, value: Double, mode: Interpolation = Interpolation.LINEAR, out: BezierHandle? = null, inn: BezierHandle? = null) =
        ParamKey(frame, value, mode, out, inn)

    // region interpolation vectors

    @Test
    fun `no keys gives the base and holds outside the keys`() {
        assertEquals(7.0, ParamTracks.evaluate(emptyList(), 5, 7.0), 0.0)
        val keys = listOf(k(10, 1.0), k(20, 3.0))
        assertEquals(1.0, ParamTracks.evaluate(keys, 0, 9.0), 0.0)
        assertEquals(1.0, ParamTracks.evaluate(keys, 10, 9.0), 0.0)
        assertEquals(3.0, ParamTracks.evaluate(keys, 20, 9.0), 0.0)
        assertEquals(3.0, ParamTracks.evaluate(keys, 99, 9.0), 0.0)
    }

    @Test
    fun `linear ease and hold segments`() {
        val linear = listOf(k(0, 0.0), k(10, 10.0))
        assertEquals(3.0, ParamTracks.evaluate(linear, 3, 0.0), 1e-12)
        assertEquals(5.0, ParamTracks.evaluate(linear, 5, 0.0), 1e-12)
        val ease = listOf(k(0, 0.0, Interpolation.EASE), k(10, 10.0))
        assertEquals(5.0, ParamTracks.evaluate(ease, 5, 0.0), 1e-12)
        assertEquals(10.0 * (0.04 * (3.0 - 0.4)), ParamTracks.evaluate(ease, 2, 0.0), 1e-12) // smoothstep at t = 0.2
        val hold = listOf(k(0, 4.0, Interpolation.HOLD), k(10, 8.0))
        assertEquals(4.0, ParamTracks.evaluate(hold, 9, 0.0), 0.0)
        assertEquals(8.0, ParamTracks.evaluate(hold, 10, 0.0), 0.0)
    }

    @Test
    fun `the mode of the earlier key of a pair applies`() {
        val keys = listOf(k(0, 0.0, Interpolation.HOLD), k(10, 10.0, Interpolation.LINEAR), k(20, 30.0))
        assertEquals(0.0, ParamTracks.evaluate(keys, 5, 0.0), 0.0)
        assertEquals(20.0, ParamTracks.evaluate(keys, 15, 0.0), 1e-12)
    }

    // endregion

    // region Bezier

    @Test
    fun `a Bezier with the linear handles is linear`() {
        val keys = listOf(
            k(0, 0.0, Interpolation.BEZIER, out = BezierHandle(1.0 / 3.0, 1.0 / 3.0)),
            k(30, 3.0, inn = BezierHandle(1.0 / 3.0, 1.0 / 3.0)),
        )
        for (frame in 0L..30L) assertEquals(frame / 10.0, ParamTracks.evaluate(keys, frame, 0.0), 1e-9)
    }

    @Test
    fun `Bezier with the CSS ease handles matches known vectors`() {
        // cubic-bezier(0.25, 0.1, 0.25, 1): the CSS "ease" curve. Reference values from the curve itself.
        val keys = listOf(
            k(0, 0.0, Interpolation.BEZIER, out = BezierHandle(0.25, 0.1)),
            k(100, 1.0, inn = BezierHandle(0.75, 0.0)),
        )
        assertEquals(0.0, ParamTracks.evaluate(keys, 0, 0.0), 1e-12)
        assertEquals(1.0, ParamTracks.evaluate(keys, 100, 0.0), 1e-12)
        // Reference values computed independently (bisection in Python) for cubic-bezier(0.25, 0.1, 0.25, 1).
        assertEquals(0.802403, ParamTracks.evaluate(keys, 50, 0.0), 1e-5)
        assertEquals(0.408511, ParamTracks.evaluate(keys, 25, 0.0), 1e-5)
        assertEquals(0.295244, ParamTracks.evaluate(keys, 20, 0.0), 1e-5)
        // Monotonic for handles inside the unit square.
        var previous = -1.0
        for (frame in 0L..100L) {
            val v = ParamTracks.evaluate(keys, frame, 0.0)
            assertTrue(v >= previous)
            previous = v
        }
    }

    @Test
    fun `Bezier handles may overshoot`() {
        val keys = listOf(
            k(0, 0.0, Interpolation.BEZIER, out = BezierHandle(0.3, 1.8)),
            k(100, 1.0, inn = BezierHandle(0.3, 0.0)),
        )
        assertTrue((1L until 100L).any { ParamTracks.evaluate(keys, it, 0.0) > 1.0 })
    }

    @Test
    fun `missing Bezier handles default to an ease in out`() {
        val keys = listOf(k(0, 0.0, Interpolation.BEZIER), k(100, 1.0))
        assertEquals(0.5, ParamTracks.evaluate(keys, 50, 0.0), 1e-9) // symmetric default handles
        assertTrue(ParamTracks.evaluate(keys, 10, 0.0) < 0.1)       // slow start
    }

    @Test
    fun `handle validation`() {
        assertNull(BezierHandle(0.5, 0.5).problem())
        assertTrue(BezierHandle(1.5, 0.0).problem() != null)
        assertTrue(BezierHandle(-0.1, 0.0).problem() != null)
        assertTrue(BezierHandle(0.5, 9.0).problem() != null)
        assertTrue(BezierHandle(Double.NaN, 0.0).problem() != null)
    }

    // endregion

    // region cropping and scaling

    @Test
    fun `cropping keeps the animation over the kept range`() {
        val keys = listOf(k(0, 0.0), k(100, 100.0))
        val tail = ParamTracks.cropped(keys, 40, 100)
        assertEquals(listOf(0L, 59L), tail.map { it.frame }) // a key at the new start holds value 40, the end key is added
        assertEquals(40.0, tail.first().value, 1e-12)
        for (frame in 0L until 60L) assertEquals(40.0 + frame, ParamTracks.evaluate(tail, frame, 0.0), 1e-9)
        val head = ParamTracks.cropped(keys, 0, 30)
        for (frame in 0L until 30L) assertEquals(frame.toDouble(), ParamTracks.evaluate(head, frame, 0.0), 1e-9)
    }

    @Test
    fun `cropping keeps keys inside and shifts them`() {
        val keys = listOf(k(10, 1.0), k(50, 5.0), k(90, 9.0))
        val cropped = ParamTracks.cropped(keys, 20, 70)
        assertEquals(30L, cropped.first { it.value == 5.0 }.frame)
        for (frame in 0L until 50L) {
            assertEquals(ParamTracks.evaluate(keys, frame + 20, 0.0), ParamTracks.evaluate(cropped, frame, 0.0), 1e-9)
        }
    }

    @Test
    fun `cropping a clip that grows holds the edge values`() {
        val keys = listOf(k(0, 2.0), k(10, 4.0))
        val grown = ParamTracks.cropped(keys, -5, 30)
        for (frame in 0L until 35L) {
            assertEquals(ParamTracks.evaluate(keys, frame - 5, 0.0), ParamTracks.evaluate(grown, frame, 0.0), 1e-9)
        }
    }

    @Test
    fun `a Bezier segment cut in the middle keeps its handles on the remaining span`() {
        val out = BezierHandle(0.4, 0.0)
        val inn = BezierHandle(0.4, 0.0)
        val keys = listOf(k(0, 0.0, Interpolation.BEZIER, out = out), k(100, 10.0, inn = inn))
        val cropped = ParamTracks.cropped(keys, 40, 100)
        assertEquals(Interpolation.BEZIER, cropped.first().interpolation)
        assertEquals(out, cropped.first().out)
        assertEquals(ParamTracks.evaluate(keys, 40, 0.0), cropped.first().value, 1e-9)
        assertEquals(inn, cropped.last().inn)
    }

    @Test
    fun `scaling stretches key frames and drops collisions`() {
        val keys = listOf(k(0, 0.0), k(50, 5.0), k(99, 9.0))
        val doubled = ParamTracks.scaled(keys, 100, 200)
        assertEquals(listOf(0L, 100L, 198L), doubled.map { it.frame })
        val squashed = ParamTracks.scaled(listOf(k(0, 0.0), k(1, 1.0), k(2, 2.0), k(3, 3.0)), 4, 2)
        assertEquals(listOf(0L, 1L), squashed.map { it.frame })
    }

    // endregion

    // region audio points parity

    @Test
    fun `audio points interpolated linearly reproduce the curve at every frame`() {
        val keys = listOf(
            k(0, -6.0, Interpolation.EASE),
            k(20, 0.0, Interpolation.BEZIER, out = BezierHandle(0.2, 0.0), inn = BezierHandle(0.3, 0.1)),
            k(44, 3.0, Interpolation.LINEAR),
            k(60, -12.0, Interpolation.HOLD),
            k(80, 0.0),
        )
        val points = ParamTracks.audioPoints(keys)
        assertTrue(points.zipWithNext().all { (a, b) -> b.first > a.first })
        fun linearAt(frame: Long): Double {
            val hi = points.indexOfFirst { it.first >= frame }
            if (hi <= 0) return points.first().second
            val a = points[hi - 1]
            val b = points[hi]
            if (b.first == frame) return b.second
            val t = (frame - a.first).toDouble() / (b.first - a.first).toDouble()
            return a.second + (b.second - a.second) * t
        }
        // Exact at every integer frame; between frames a hold ramps over its last frame (a click-free step).
        for (frame in 0L..80L) assertEquals("frame $frame", ParamTracks.evaluate(keys, frame, 0.0), linearAt(frame), 1e-9)
    }

    @Test
    fun `audio points of one key and of linear keys stay sparse`() {
        assertEquals(listOf(5L to 2.0), ParamTracks.audioPoints(listOf(k(5, 2.0))))
        assertEquals(listOf(0L to 0.0, 99L to 1.0), ParamTracks.audioPoints(listOf(k(0, 0.0), k(99, 1.0))))
    }

    // endregion

    // region pasting

    @Test
    fun `shifted keys land at the paste frame and are clipped to the clip`() {
        val source = listOf(k(10, 1.0), k(20, 2.0), k(30, 3.0))
        val shifted = ParamTracks.shiftedTo(source, 40, 65)
        assertEquals(listOf(40L, 50L, 60L), shifted.map { it.frame })
        assertEquals(listOf(40L, 50L), ParamTracks.shiftedTo(source, 40, 60).map { it.frame })
        assertTrue(abs(ParamTracks.shiftedTo(emptyList(), 3, 10).size) == 0)
    }

    // endregion
}
