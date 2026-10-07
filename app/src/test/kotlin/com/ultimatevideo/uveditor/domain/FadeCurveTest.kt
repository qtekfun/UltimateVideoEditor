package com.ultimatevideo.uveditor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * The Kotlin mirror of `core/fade_math.h`. The golden values below are the ones `audio_host_tests.cpp` (testFadeShapeMath)
 * checks the C++ against, so the preview, the export and the editor agree on the gain of a fade at every step.
 */
class FadeCurveTest {
    @Test
    fun `golden values match the native curves`() {
        assertEquals(0.70710678, FadeCurve.shapeGain(FadeShape.EQUAL_POWER, 0.5), 1e-6)
        assertEquals(0.25, FadeCurve.shapeGain(FadeShape.LINEAR, 0.25), 1e-7)
        assertEquals((10.0.pow(-1.5) - 0.001) / 0.999, FadeCurve.shapeGain(FadeShape.LOGARITHMIC, 0.5), 1e-6)
        assertEquals(0.5007, FadeCurve.shapeGain(FadeShape.LOGARITHMIC, 0.9), 1e-3)
        // The combined factor on a clip 100 steps long, fade-in 10 and fade-out 20 (linear).
        assertEquals(1.0, FadeCurve.gainAt(FadeShape.LINEAR, 10, 20, 100, 50), 0.0)
        assertEquals(0.45, FadeCurve.gainAt(FadeShape.LINEAR, 10, 20, 100, 4), 1e-6)
        assertEquals(0.475, FadeCurve.gainAt(FadeShape.LINEAR, 10, 20, 100, 90), 1e-6)
        assertEquals(1.0, FadeCurve.gainAt(FadeShape.LINEAR, 0, 0, 100, 0), 0.0)
    }

    @Test
    fun `every shape runs from silence to unity strictly rising`() {
        for (shape in FadeShape.entries) {
            assertEquals(0.0, FadeCurve.shapeGain(shape, 0.0), 0.0)
            assertEquals(1.0, FadeCurve.shapeGain(shape, 1.0), 0.0)
            var previous = 0.0
            for (i in 1 until 100) {
                val g = FadeCurve.shapeGain(shape, i / 100.0)
                assertTrue("$shape at $i", g > previous && g < 1.0)
                previous = g
            }
        }
    }

    @Test
    fun `a fade-out is the fade-in backwards and is silent past its end`() {
        for (shape in FadeShape.entries) {
            for (k in 0 until 40L) {
                assertEquals(FadeCurve.fadeInGain(shape, 39 - k, 40), FadeCurve.fadeOutGain(shape, k, 40), 1e-12)
            }
            assertEquals(0.0, FadeCurve.fadeOutGain(shape, 40, 40), 0.0)
            assertEquals(1.0, FadeCurve.fadeOutGain(shape, 3, 0), 0.0)
            assertEquals(1.0, FadeCurve.fadeInGain(shape, 40, 40), 0.0)
        }
    }

    @Test
    fun `equal power fades match the transition ramps`() {
        for (k in listOf(0L, 5L, 17L, 39L)) {
            assertEquals(CrossfadeCurve.fadeInGain(k, 40), FadeCurve.fadeInGain(FadeShape.EQUAL_POWER, k, 40), 1e-9)
            assertEquals(CrossfadeCurve.fadeOutGain(k, 40), FadeCurve.fadeOutGain(FadeShape.EQUAL_POWER, k, 40), 1e-9)
        }
    }

    @Test
    fun `overlapping fades multiply and never exceed unity`() {
        for (s in 0 until 10L) assertTrue(FadeCurve.gainAt(FadeShape.EQUAL_POWER, 10, 10, 10, s) <= 1.0)
    }

    @Test
    fun `wire codes and file names round trip, unknown ones read as the default`() {
        for (shape in FadeShape.entries) {
            assertEquals(shape, FadeShape.fromCode(shape.code))
            assertEquals(shape, FadeShape.fromId(shape.id))
        }
        assertEquals(FadeShape.EQUAL_POWER, FadeShape.fromCode(3))
        assertEquals(FadeShape.EQUAL_POWER, FadeShape.fromId(null))
        assertEquals(FadeShape.EQUAL_POWER, FadeShape.fromId("s-curve-from-a-newer-build"))
    }
}
