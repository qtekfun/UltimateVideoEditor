package com.qtekfun.ultimatevideoeditor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class HdrLevelTest {
    // region gain

    @Test
    fun `content at or below reference white is not touched`() {
        assertEquals(1.0, HdrLevel.gainFor(0.4), 0.0)
        assertEquals(1.0, HdrLevel.gainFor(1.0), 0.0)
        assertEquals(1.0, HdrLevel.gainFor(0.0), 0.0)
    }

    @Test
    fun `brighter content is dimmed to reference white`() {
        assertEquals(1.0 / 1.5, HdrLevel.gainFor(1.5), 1e-12)
        assertEquals(0.5, HdrLevel.gainFor(2.0), 1e-12)
    }

    @Test
    fun `the gain never brightens and never dims by more than one and a half stops`() {
        val floor = 2.0.pow(-1.5)
        for (level in listOf(0.0, 0.5, 1.0, 1.01, 1.3, 2.0, 2.83, 3.0, 10.0, 1000.0)) {
            val gain = HdrLevel.gainFor(level)
            assertTrue("level $level gave $gain", gain <= 1.0 && gain >= floor - 1e-12)
        }
        assertEquals(floor, HdrLevel.gainFor(100.0), 1e-12)
        assertEquals(-1.5, HdrLevel.stepsOf(HdrLevel.gainFor(100.0)), 1e-12)
    }

    @Test
    fun `a negligible excess is not worth a gain`() {
        assertEquals(1.0, HdrLevel.gainFor(1.003), 0.0)
        assertTrue(HdrLevel.gainFor(1.02) < 1.0)
    }

    @Test
    fun `non finite levels give no gain`() {
        assertEquals(1.0, HdrLevel.gainFor(Double.NaN), 0.0)
        assertEquals(1.0, HdrLevel.gainFor(Double.POSITIVE_INFINITY), 0.0)
    }

    @Test
    fun `stops are the base two logarithm of the gain`() {
        assertEquals(0.0, HdrLevel.stepsOf(1.0), 0.0)
        assertEquals(-1.0, HdrLevel.stepsOf(0.5), 1e-12)
        assertEquals(-0.8, HdrLevel.stepsOf(2.0.pow(-0.8)), 1e-12)
    }

    // endregion

    // region diffuse level of measured cells

    @Test
    fun `nothing measured gives no level`() {
        assertNull(HdrLevel.diffuseLevel(DoubleArray(0)))
    }

    @Test
    fun `a uniform picture has that level`() {
        assertEquals(150.0 / 203.0, HdrLevel.diffuseLevel(DoubleArray(500) { 150.0 })!!, 1e-12)
    }

    @Test
    fun `specular highlights and lamps do not set the level`() {
        // 97 cells of ordinary content at 100 nit and 3 of a lamp at 1000 nit: the brightest two percent are dropped, then the
        // 90th percentile of the rest is taken, which is ordinary content.
        val cells = DoubleArray(100) { if (it < 97) 100.0 else 1000.0 }
        assertEquals(100.0 / 203.0, HdrLevel.diffuseLevel(cells)!!, 1e-12)
        // Without them the answer is the same: the lamp did not move it.
        assertEquals(HdrLevel.diffuseLevel(DoubleArray(97) { 100.0 })!!, HdrLevel.diffuseLevel(cells)!!, 1e-12)
    }

    @Test
    fun `a bright subject on a dark background sets the level`() {
        // A face filling a quarter of the picture at 320 nit over a dark room: the 90th percentile is the face, a median would not be.
        val cells = DoubleArray(400) { if (it % 4 == 0) 320.0 else 12.0 }
        assertEquals(320.0 / 203.0, HdrLevel.diffuseLevel(cells)!!, 1e-12)
    }

    @Test
    fun `a dark clip gets no gain`() {
        val level = HdrLevel.diffuseLevel(DoubleArray(600) { 18.0 })!!
        assertEquals(1.0, HdrLevel.gainFor(level), 0.0)
    }

    @Test
    fun `a single cell still has a level`() {
        assertEquals(50.0 / 203.0, HdrLevel.diffuseLevel(doubleArrayOf(50.0))!!, 1e-12)
    }

    // endregion

    // region nits of a signature cell

    private fun grey(signal: Double, transfer: SourceColorSpace): Double {
        // A limited-range grey in signature units: luma 0..1, chroma at one half.
        val scale = 65535
        val y = intArrayOf((signal * scale).toInt())
        val mid = intArrayOf(scale / 2 + 1)
        return HdrLevel.cellNits(y, mid, mid, scale, transfer)[0]
    }

    @Test
    fun `an HLG grey at signal 0_75 is reference white`() {
        assertEquals(203.0, grey(0.75, SourceColorSpace.HLG), 2.0)
    }

    @Test
    fun `an HLG grey at full signal is the 1000 nit peak`() {
        assertEquals(1000.0, grey(1.0, SourceColorSpace.HLG), 15.0)
        assertEquals(0.0, grey(0.0, SourceColorSpace.HLG), 0.5)
    }

    @Test
    fun `a PQ grey at signal 0_5807 is reference white`() {
        assertEquals(203.0, grey(0.5807, SourceColorSpace.PQ), 2.0)
    }

    @Test
    fun `a saturated cell stays inside the display range`() {
        val scale = 65535
        for (cr in listOf(0, scale / 4, scale / 2, scale * 3 / 4, scale)) {
            val nits = HdrLevel.cellNits(intArrayOf(40000), intArrayOf(scale / 2), intArrayOf(cr), scale, SourceColorSpace.HLG)[0]
            assertTrue("cr $cr gave $nits", nits in 0.0..1000.0)
        }
    }

    @Test
    fun `an SDR source has no nits`() {
        assertEquals(0.0, HdrLevel.nitsOf(1.0, 1.0, 1.0, SourceColorSpace.SDR), 0.0)
    }

    // endregion

    // region deciding the gain of a clip

    @Test
    fun `a clip follows the project unless it says otherwise`() {
        val bright = 2.0
        assertEquals(0.5, HdrLevel.gain(HdrBrightness.COMFORTABLE, null, SourceColorSpace.HLG, bright), 1e-12)
        assertEquals(1.0, HdrLevel.gain(HdrBrightness.ORIGINAL, null, SourceColorSpace.HLG, bright), 0.0)
        assertEquals(1.0, HdrLevel.gain(HdrBrightness.COMFORTABLE, false, SourceColorSpace.HLG, bright), 0.0)
        assertEquals(0.5, HdrLevel.gain(HdrBrightness.ORIGINAL, true, SourceColorSpace.HLG, bright), 1e-12)
    }

    @Test
    fun `an SDR source or an unmeasured file has no gain`() {
        assertEquals(1.0, HdrLevel.gain(HdrBrightness.COMFORTABLE, null, SourceColorSpace.SDR, 5.0), 0.0)
        assertEquals(1.0, HdrLevel.gain(HdrBrightness.COMFORTABLE, null, SourceColorSpace.HLG, null), 0.0)
        assertEquals(0.5, HdrLevel.gain(HdrBrightness.COMFORTABLE, null, SourceColorSpace.PQ, 2.0), 1e-12)
    }

    @Test
    fun `the brightness setting reads unknown values as comfortable`() {
        assertEquals(HdrBrightness.COMFORTABLE, HdrBrightness.fromId(null))
        assertEquals(HdrBrightness.COMFORTABLE, HdrBrightness.fromId("brighter"))
        assertEquals(HdrBrightness.ORIGINAL, HdrBrightness.fromId("original"))
        assertEquals(HdrBrightness.ORIGINAL, HdrBrightness.fromId("ORIGINAL"))
    }

    // endregion

    // region the levels of a project

    @Test
    fun `levels are looked up by file and by how it is read`() {
        val levels = HdrLevels(levels = mapOf(HdrLevels.keyOf("content://m/a", 300, SourceColorSpace.HLG) to 1.6))
        assertEquals(1.6, levels.levelOf("content://m/a", 300, SourceColorSpace.HLG)!!, 0.0)
        assertNull(levels.levelOf("content://m/a", 300, SourceColorSpace.PQ))
        assertNull(levels.levelOf("content://m/a", 301, SourceColorSpace.HLG)) // a replaced file is another file
        assertNull(levels.levelOf("content://m/b", 300, SourceColorSpace.HLG))
    }

    @Test
    fun `a colour override reads the file as another space and so has another level`() {
        val levels = HdrLevels(levels = mapOf(HdrLevels.keyOf("u", 10, SourceColorSpace.HLG) to 2.0))
        // The file says SDR, the clip says HLG: the HLG level applies. The file says HLG, the clip says SDR: no gain.
        assertEquals(0.5, levels.gainOf(null, "u", 10, SourceColorSpace.SDR, SourceColorSpace.HLG), 1e-12)
        assertEquals(1.0, levels.gainOf(null, "u", 10, SourceColorSpace.HLG, SourceColorSpace.SDR), 0.0)
        assertNotNull(levels.levelOf("u", 10, SourceColorSpace.HLG))
    }

    @Test
    fun `footage as recorded has no gain for any clip`() {
        assertEquals(1.0, HdrLevels.NONE.gainOf(null, "u", 10, SourceColorSpace.HLG, null), 0.0)
        assertTrue(HdrLevels().wantsAnalysis(null))
        assertTrue(!HdrLevels(HdrBrightness.ORIGINAL).wantsAnalysis(null))
        assertTrue(HdrLevels(HdrBrightness.ORIGINAL).wantsAnalysis(true))
        assertTrue(!HdrLevels().wantsAnalysis(false))
    }

    // endregion
}
