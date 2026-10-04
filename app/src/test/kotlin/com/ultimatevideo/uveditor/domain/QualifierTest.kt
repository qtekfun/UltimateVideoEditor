package com.ultimatevideo.uveditor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class QualifierTest {
    private val defaults = EffectType.QUALIFIER.defaults

    @Test
    fun `the qualifier has the documented 14 parameters and valid defaults`() {
        assertEquals(14, EffectType.QUALIFIER.params.size)
        assertEquals(18, EffectType.QUALIFIER.code)
        assertNull(Effect("q", EffectType.QUALIFIER).problem())
        assertEquals(EffectType.QUALIFIER, EffectType.fromCode(18))
        // Neutral correction by default: the key alone changes nothing.
        assertEquals(0.0, defaults[Qualifier.HUE_SHIFT], 0.0)
        assertEquals(1.0, defaults[Qualifier.SAT_GAIN], 0.0)
        assertEquals(0.0, defaults[Qualifier.LIGHTNESS], 0.0)
    }

    @Test
    fun `out of range values are refused`() {
        val bad = defaults.toMutableList().also { it[Qualifier.HUE_WIDTH] = 0.9 }
        assertTrue(Effect("q", EffectType.QUALIFIER, bad).problem()!!.contains("hue width"))
        val short = defaults.dropLast(1)
        assertTrue(Effect("q", EffectType.QUALIFIER, short).problem()!!.contains("14"))
    }

    @Test
    fun `hsl matches the primary and secondary colours`() {
        val red = Qualifier.hsl(1.0, 0.0, 0.0)
        assertEquals(0.0, red.first, 1e-9)
        assertEquals(1.0, red.second, 1e-9)
        assertEquals(0.5, red.third, 1e-9)
        assertEquals(1.0 / 3.0, Qualifier.hsl(0.0, 1.0, 0.0).first, 1e-9)
        assertEquals(2.0 / 3.0, Qualifier.hsl(0.0, 0.0, 1.0).first, 1e-9)
        assertEquals(0.5, Qualifier.hsl(0.0, 1.0, 1.0).first, 1e-9)       // cyan
        assertEquals(5.0 / 6.0, Qualifier.hsl(1.0, 0.0, 1.0).first, 1e-9) // magenta
        val grey = Qualifier.hsl(0.4, 0.4, 0.4)
        assertEquals(0.0, grey.second, 0.0)
        assertEquals(0.4, grey.third, 1e-9)
    }

    @Test
    fun `picking a colour centres the key on it and leaves softness and correction alone`() {
        val base = defaults.toMutableList().also {
            it[Qualifier.HUE_SOFT] = 0.11
            it[Qualifier.HUE_SHIFT] = 0.2
            it[Qualifier.SAT_GAIN] = 1.5
        }
        val keyed = Qualifier.keyedOn(base, 0.1, 0.8, 0.1) // a saturated green
        assertEquals(Qualifier.hsl(0.1, 0.8, 0.1).first, keyed[Qualifier.HUE], 1e-9)
        assertEquals(Qualifier.PICK_HUE_WIDTH, keyed[Qualifier.HUE_WIDTH], 0.0)
        val s = Qualifier.hsl(0.1, 0.8, 0.1).second
        assertEquals((s - Qualifier.PICK_SAT_REACH).coerceIn(0.0, 1.0), keyed[Qualifier.SAT_MIN], 1e-9)
        assertEquals((s + Qualifier.PICK_SAT_REACH).coerceIn(0.0, 1.0), keyed[Qualifier.SAT_MAX], 1e-9)
        val y = Qualifier.luma(0.1, 0.8, 0.1)
        assertEquals((y - Qualifier.PICK_LUMA_REACH).coerceIn(0.0, 1.0), keyed[Qualifier.LUMA_MIN], 1e-9)
        assertEquals((y + Qualifier.PICK_LUMA_REACH).coerceIn(0.0, 1.0), keyed[Qualifier.LUMA_MAX], 1e-9)
        assertEquals(0.11, keyed[Qualifier.HUE_SOFT], 0.0)
        assertEquals(0.2, keyed[Qualifier.HUE_SHIFT], 0.0)
        assertEquals(1.5, keyed[Qualifier.SAT_GAIN], 0.0)
        assertNull(Effect("q", EffectType.QUALIFIER, keyed).problem())
    }

    @Test
    fun `picking a grey takes every hue and a low saturation band`() {
        val keyed = Qualifier.keyedOn(defaults, 0.5, 0.5, 0.5)
        assertEquals(0.5, keyed[Qualifier.HUE_WIDTH], 0.0)
        assertEquals(0.0, keyed[Qualifier.SAT_MIN], 0.0)
        assertTrue(keyed[Qualifier.SAT_MAX] < 0.3)
        assertNull(Effect("q", EffectType.QUALIFIER, keyed).problem())
    }

    @Test
    fun `picked colours at the extremes stay inside the parameter ranges`() {
        for (r in listOf(0.0, 1.0)) for (g in listOf(0.0, 1.0)) for (b in listOf(0.0, 1.0)) {
            val keyed = Qualifier.keyedOn(defaults, r, g, b)
            assertNull("$r $g $b", Effect("q", EffectType.QUALIFIER, keyed).problem())
        }
        // Values outside 0..1 are clamped rather than producing a bad key.
        assertNull(Effect("q", EffectType.QUALIFIER, Qualifier.keyedOn(defaults, 1.4, -0.2, 0.3)).problem())
    }

    @Test
    fun `keying needs a full value list`() {
        assertThrows(IllegalArgumentException::class.java) { Qualifier.keyedOn(listOf(0.0), 1.0, 0.0, 0.0) }
    }
}
