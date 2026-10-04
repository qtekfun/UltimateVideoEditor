package com.ultimatevideo.uveditor.engine.sample

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PatchAverageTest {
    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun `a flat patch gives its colour as straight 0 to 1 values`() {
        val pixels = IntArray(25) { argb(255, 51, 0) }
        val c = PatchAverage.of(pixels, 5, 5, 2, 2, 1)!!
        assertEquals(1.0, c.r, 1e-9)
        assertEquals(0.2, c.g, 1e-9)
        assertEquals(0.0, c.b, 1e-9)
    }

    @Test
    fun `the patch is a mean, so one noisy pixel does not set the colour`() {
        val pixels = IntArray(9) { argb(100, 100, 100) }
        pixels[4] = argb(255, 255, 255) // the centre pixel
        val c = PatchAverage.of(pixels, 3, 3, 1, 1, 1)!!
        assertEquals((8 * 100 + 255) / (9 * 255.0), c.r, 1e-9)
        // A radius of zero reads just the pixel.
        assertEquals(1.0, PatchAverage.of(pixels, 3, 3, 1, 1, 0)!!.r, 1e-9)
    }

    @Test
    fun `a patch at a corner or edge is clipped to the picture`() {
        val pixels = IntArray(4) { argb(0, 255, 0) }
        val c = PatchAverage.of(pixels, 2, 2, 0, 0, 3)!!
        assertEquals(1.0, c.g, 1e-9)
        assertEquals(0.0, c.r, 1e-9)
    }

    @Test
    fun `an empty or too short picture gives nothing`() {
        assertNull(PatchAverage.of(IntArray(0), 0, 0, 0, 0, 1))
        assertNull(PatchAverage.of(IntArray(3), 2, 2, 0, 0, 1))
        // A centre far outside the picture has no pixels in its patch.
        assertNull(PatchAverage.of(IntArray(4), 2, 2, 50, 50, 1))
    }

    @Test
    fun `picture positions map to the nearest pixel and stay inside the picture`() {
        assertEquals(0 to 0, PatchAverage.pixelOf(0.0, 0.0, 100, 50))
        assertEquals(50 to 25, PatchAverage.pixelOf(0.5, 0.5, 100, 50))
        assertEquals(99 to 49, PatchAverage.pixelOf(1.0, 1.0, 100, 50)) // the edge belongs to the last pixel
        assertEquals(0 to 49, PatchAverage.pixelOf(-3.0, 9.0, 100, 50))
    }

    @Test
    fun `a sampled colour must be in range`() {
        assertThrows(IllegalArgumentException::class.java) { SampledColor(1.2, 0.0, 0.0) }
        assertThrows(IllegalArgumentException::class.java) { SampledColor(0.0, -0.1, 0.0) }
    }
}
