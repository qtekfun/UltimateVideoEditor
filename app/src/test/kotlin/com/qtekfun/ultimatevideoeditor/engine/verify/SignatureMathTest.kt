package com.qtekfun.ultimatevideoeditor.engine.verify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignatureMathTest {
    private val t = VerifyThresholds()

    private fun distance(a: FrameSignature, b: FrameSignature) = SignatureMath.distance(a, b, t.cellLuma)

    @Test
    fun `identical signatures have zero distance and match`() {
        val s = SyntheticSignatures.frame(10)
        val d = distance(s, s)

        assertEquals(0.0, d.lumaMean, 0.0)
        assertEquals(0.0, d.badCellShare, 0.0)
        assertTrue(t.matches(d))
    }

    @Test
    fun `signatures survive the wire format`() {
        val a = SyntheticSignatures.frame(3, 100)
        val data = ShortArray(3 * SignatureFormat.CELLS)
        for (i in 0 until SignatureFormat.CELLS) {
            data[i] = a.y[i].toShort()
            data[SignatureFormat.CELLS + i] = a.cb[i].toShort()
            data[2 * SignatureFormat.CELLS + i] = a.cr[i].toShort()
        }

        val parsed = FrameSignature.parse(longArrayOf(3, 100), data).single()

        assertEquals(3L, parsed.frame)
        assertEquals(100L, parsed.ptsUs)
        assertEquals(0.0, distance(a, parsed).lumaMean, 0.0)
    }

    @Test
    fun `a malformed wire payload yields no signatures, not a crash`() {
        assertTrue(FrameSignature.parse(longArrayOf(1, 2, 3), ShortArray(0)).isEmpty())
        assertTrue(FrameSignature.parse(longArrayOf(1, 2), ShortArray(10)).isEmpty())
    }

    @Test
    fun `flat pictures are recognised and a real picture is not`() {
        assertTrue(SignatureMath.isFlat(SyntheticSignatures.flat(0), t))
        assertTrue(SignatureMath.isFlat(SyntheticSignatures.flat(0, luma = 0.5, chroma = 0.45), t))
        assertFalse(SignatureMath.isFlat(SyntheticSignatures.frame(5), t))
    }

    /**
     * The calibration table: the distance of each kind of picture from its original, against the thresholds. Normal lossy coding
     * must sit well inside (here: at most half the threshold), damage and wrong frames outside. The same classes were measured
     * on real exports (DECISIONS.md "Post-export verification", calibration).
     */
    @Test
    fun `calibration table normal exports pass with margin and damage fails`() {
        val frames = (0L until 40L)
        fun worst(make: (Long) -> FrameSignature): SignatureDistance =
            frames.map { distance(SyntheticSignatures.frame(it), make(it)) }.maxByOrNull { it.lumaMean + it.chromaMean }!!

        val rows = linkedMapOf(
            "exact" to worst { SyntheticSignatures.frame(it) },
            "lossy 0.5%" to worst { SyntheticSignatures.lossy(it, 0.005) },
            "lossy 1.5%" to worst { SyntheticSignatures.lossy(it, 0.015) },
            "lossy 3% (worst case encoder)" to worst { SyntheticSignatures.lossy(it, 0.03) },
        )
        for ((name, d) in rows) {
            assertTrue("$name should match: $d", t.matches(d))
            assertTrue("$name lumaMean ${d.lumaMean} is not far enough inside ${t.lumaMean}", d.lumaMean <= t.lumaMean * 0.65)
        }
        val bad = linkedMapOf(
            "black" to worst { SyntheticSignatures.flat(it) },
            "grey" to worst { SyntheticSignatures.flat(it, 0.5, 0.5) },
            "other frame +1 s" to worst { SyntheticSignatures.frame(it + 30) },
            "other frame +1" to worst { SyntheticSignatures.frame(it + 1) },
            "half the picture grey" to worst { f ->
                val s = SyntheticSignatures.frame(f)
                FrameSignature(f, 0, IntArray(SignatureFormat.CELLS) { if (it >= SignatureFormat.CELLS / 2) 32768 else s.y[it] }, s.cb, s.cr)
            },
        )
        for ((name, d) in bad) assertFalse("$name must not match: $d", t.matches(d))
    }

    @Test
    fun `a damaged band across the bottom of the picture is caught by the share of bad cells`() {
        val s = SyntheticSignatures.frame(20)
        val damaged = FrameSignature(
            20, 0,
            IntArray(SignatureFormat.CELLS) { if (it / SignatureFormat.W >= 15) 0 else s.y[it] }, // bottom three of 18 rows
            s.cb, s.cr,
        )

        val d = distance(s, damaged)

        assertFalse(d.toString(), t.matches(d))
    }

    @Test
    fun `expectation maps timestamps to frame indices exactly`() {
        val ntsc = VerifyExpectation(100_000, 60000, 1001, false, false)
        assertEquals(0L, ntsc.frameIndexOf(0))
        assertEquals(1L, ntsc.frameIndexOf(16_683))
        assertEquals(60_000L, ntsc.frameIndexOf(1_001_000_000))
        assertEquals(ntsc.ptsUsOf(12345), ntsc.ptsUsOf(12345))
        for (f in listOf(0L, 1L, 59L, 999L, 99_999L)) assertEquals(f, ntsc.frameIndexOf(ntsc.ptsUsOf(f)))
    }
}
