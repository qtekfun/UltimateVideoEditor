package com.qtekfun.ultimatevideoeditor.engine.still

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GainmapMathTest {
    private fun params(ratioMin: Float = 1f, ratioMax: Float = 4f, gamma: Float = 1f, epsilon: Float = 0f) = GainmapParams(
        FloatArray(3) { ratioMin }, FloatArray(3) { ratioMax }, FloatArray(3) { gamma }, FloatArray(3) { epsilon }, FloatArray(3) { epsilon }, 4f,
    )

    private fun signal(r: Float, g: Float, b: Float): FloatArray = FloatArray(3).also { GainmapMath.hlgSignal(r, g, b, it) }

    @Test
    fun `a map of zero leaves the SDR picture as it is`() {
        for (v in listOf(0f, 0.1f, 0.5f, 1f)) assertEquals(v, GainmapMath.hdrLinear(v, 0f, params(), 0), 1e-6f)
    }

    @Test
    fun `a map of one boosts by the maximum ratio and one half by the geometric middle`() {
        assertEquals(4f, GainmapMath.hdrLinear(1f, 1f, params(), 1), 1e-5f)
        assertEquals(2f, GainmapMath.hdrLinear(1f, 0.5f, params(), 2), 1e-5f) // sqrt(1 * 4)
    }

    @Test
    fun `the gamma bends the map and the offsets are applied around the boost`() {
        // pow(0.25, 1/2) = 0.5 -> boost 2.
        assertEquals(2f, GainmapMath.hdrLinear(1f, 0.25f, params(gamma = 2f), 0), 1e-5f)
        // (0.5 + 0.1) * 2 - 0.1 = 1.1
        val offset = GainmapParams(floatArrayOf(2f, 2f, 2f), floatArrayOf(2f, 2f, 2f), floatArrayOf(1f, 1f, 1f), floatArrayOf(0.1f, 0.1f, 0.1f), floatArrayOf(0.1f, 0.1f, 0.1f), 2f)
        assertEquals(1.1f, GainmapMath.hdrLinear(0.5f, 0.7f, offset, 0), 1e-5f)
        assertEquals(0f, GainmapMath.hdrLinear(0f, 0f, GainmapParams(FloatArray(3) { 1f }, FloatArray(3) { 1f }, FloatArray(3) { 1f }, FloatArray(3), FloatArray(3) { 0.5f }, 1f), 0), 0f)
    }

    @Test
    fun `SDR white is reference white and darker values are monotonic in the HLG signal`() {
        val white = signal(1f, 1f, 1f)
        // 203 nit is HLG signal 0.75 on the 1000 nit system (BT.2408).
        for (c in white) assertEquals(0.75f, c, 0.003f)
        var previous = -1f
        for (v in 0..20) {
            val s = signal(v / 20f, v / 20f, v / 20f)[0]
            assertTrue(s >= previous)
            previous = s
        }
        assertEquals(0f, signal(0f, 0f, 0f)[0], 1e-6f)
    }

    @Test
    fun `a highlight at twice SDR white is 406 nit`() {
        val s = signal(2f, 2f, 2f)
        // 406 nit: scene light 0.406^(1/1.2) = 0.4719, HLG OETF -> 0.8608.
        for (c in s) assertEquals(0.8608f, c, 0.004f)
    }

    @Test
    fun `light above the peak clips instead of overflowing`() {
        val s = signal(40f, 40f, 40f)
        for (c in s) assertEquals(1f, c, 1e-6f)
    }

    @Test
    fun `primaries are converted so a saturated Rec709 red stays inside the Rec2020 signal`() {
        val red = signal(1f, 0f, 0f)
        assertTrue(red[0] > red[1] && red[0] > red[2])
        assertTrue(red.all { it in 0f..1f })
    }

    @Test
    fun `the SDR decode is the sRGB curve`() {
        assertEquals(0f, GainmapMath.SRGB_TO_LINEAR[0], 0f)
        assertEquals(1f, GainmapMath.SRGB_TO_LINEAR[255], 1e-6f)
        assertEquals(0.2159f, GainmapMath.SRGB_TO_LINEAR[128], 0.001f)
    }

    @Test
    fun `dithering is a fixed pattern that averages to the value`() {
        for (v in listOf(0.123f, 0.5f, 0.75f, 0.9f)) {
            var sum = 0.0
            for (y in 0 until 4) for (x in 0 until 4) sum += GainmapMath.quantise(v, x, y)
            assertEquals(v * 255.0, sum / 16, 0.1)
        }
        assertEquals(GainmapMath.quantise(0.4f, 5, 9), GainmapMath.quantise(0.4f, 5, 9))
        assertEquals(0, GainmapMath.quantise(0f, 3, 3))
        assertEquals(255, GainmapMath.quantise(1f, 0, 0))
    }

    @Test
    fun `the gain map is sampled with bilinear filtering and clamps at the edges`() {
        val planes = GainmapPlanes.gray(2, 1, floatArrayOf(0f, 1f))
        assertEquals(0f, planes.at(0f, 0.5f, 0), 1e-6f)
        assertEquals(1f, planes.at(1f, 0.5f, 1), 1e-6f)
        assertEquals(0.5f, planes.at(0.5f, 0.5f, 2), 1e-6f)
        assertTrue(abs(planes.at(0.25f, 0.5f, 0) - 0f) < 1e-6f)
    }
}
