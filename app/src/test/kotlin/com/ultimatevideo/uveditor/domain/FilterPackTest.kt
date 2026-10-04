package com.ultimatevideo.uveditor.domain

import com.ultimatevideo.uveditor.data.LutStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FilterPackTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val colours = listOf(
        Triple(0.0, 0.0, 0.0), Triple(1.0, 1.0, 1.0), Triple(0.5, 0.5, 0.5), Triple(1.0, 0.0, 0.0), Triple(0.0, 1.0, 0.0),
        Triple(0.0, 0.0, 1.0), Triple(0.8, 0.6, 0.4), Triple(0.2, 0.3, 0.7), Triple(0.9, 0.9, 0.1), Triple(0.1, 0.5, 0.3),
    )

    @Test
    fun `the pack has about twenty looks with unique ids and names`() {
        assertTrue(FilterPack.looks.size >= 20)
        assertEquals(FilterPack.looks.size, FilterPack.looks.map { it.id }.toSet().size)
        assertEquals(FilterPack.looks.size, FilterPack.looks.map { it.name }.toSet().size)
        assertNotNull(FilterPack.find("cinematic"))
        assertEquals(null, FilterPack.find("nope"))
    }

    @Test
    fun `the original look and neutral parameters leave every colour unchanged`() {
        for (params in listOf(LookParams.NEUTRAL, checkNotNull(FilterPack.find("original")).params)) {
            for ((r, g, b) in colours) {
                val out = params.apply(r, g, b)
                assertEquals(r, out.first, 1e-9)
                assertEquals(g, out.second, 1e-9)
                assertEquals(b, out.third, 1e-9)
            }
        }
    }

    @Test
    fun `every look stays inside the 0 to 1 range`() {
        val steps = 6
        for (look in FilterPack.looks) {
            for (ri in 0..steps) for (gi in 0..steps) for (bi in 0..steps) {
                val (r, g, b) = look.params.apply(ri / steps.toDouble(), gi / steps.toDouble(), bi / steps.toDouble())
                for (v in listOf(r, g, b)) assertTrue("${look.id} gave $v", v in 0.0..1.0)
            }
        }
    }

    @Test
    fun `grey never gets darker as it gets brighter, in every channel and in luma`() {
        for (look in FilterPack.looks) {
            var previous = Triple(-1.0, -1.0, -1.0)
            var previousLuma = -1.0
            for (i in 0..256) {
                val x = i / 256.0
                val out = look.params.apply(x, x, x)
                assertTrue("${look.id} red at $x", out.first >= previous.first - 1e-9)
                assertTrue("${look.id} green at $x", out.second >= previous.second - 1e-9)
                assertTrue("${look.id} blue at $x", out.third >= previous.third - 1e-9)
                val luma = LookParams.luma(out.first, out.second, out.third)
                assertTrue("${look.id} luma at $x", luma >= previousLuma - 1e-9)
                previous = out
                previousLuma = luma
            }
        }
    }

    @Test
    fun `black and white looks have equal channels`() {
        for (id in listOf("noir", "silver")) {
            val params = checkNotNull(FilterPack.find(id)).params
            for ((r, g, b) in colours) {
                val out = params.apply(r, g, b)
                assertEquals(out.first, out.second, 1e-9)
                assertEquals(out.second, out.third, 1e-9)
            }
        }
    }

    @Test
    fun `looks push colour in the direction they are named for`() {
        val warm = checkNotNull(FilterPack.find("warm-glow")).params.apply(0.5, 0.5, 0.5)
        assertTrue(warm.first > warm.third)
        val cool = checkNotNull(FilterPack.find("cool-breeze")).params.apply(0.5, 0.5, 0.5)
        assertTrue(cool.third > cool.first)
        val night = checkNotNull(FilterPack.find("day-for-night")).params.apply(0.5, 0.5, 0.5)
        assertTrue(LookParams.luma(night.first, night.second, night.third) < 0.5)
        val matte = checkNotNull(FilterPack.find("matte")).params.apply(0.0, 0.0, 0.0)
        assertTrue(matte.first > 0.05)
    }

    @Test
    fun `every cube parses at the pack size and reproduces the look on its lattice`() {
        for (look in FilterPack.looks) {
            val lut = FilterPack.lut(look)
            assertEquals(FilterPack.CUBE_SIZE, lut.size)
            val max = (lut.size - 1).toDouble()
            for ((ri, gi, bi) in listOf(Triple(0, 0, 0), Triple(8, 8, 8), Triple(16, 16, 16), Triple(16, 0, 8), Triple(3, 12, 5))) {
                val expected = look.params.apply(ri / max, gi / max, bi / max)
                val got = lut.sample((ri / max).toFloat(), (gi / max).toFloat(), (bi / max).toFloat())
                assertEquals("${look.id} red", expected.first, got[0].toDouble(), 1e-4)
                assertEquals("${look.id} green", expected.second, got[1].toDouble(), 1e-4)
                assertEquals("${look.id} blue", expected.third, got[2].toDouble(), 1e-4)
            }
        }
    }

    @Test
    fun `the original cube is the identity LUT`() {
        val lut = FilterPack.lut(checkNotNull(FilterPack.find("original")))
        for ((r, g, b) in colours) {
            val got = lut.sample(r.toFloat(), g.toFloat(), b.toFloat())
            assertEquals(r, got[0].toDouble(), 1e-4)
            assertEquals(g, got[1].toDouble(), 1e-4)
            assertEquals(b, got[2].toDouble(), 1e-4)
        }
    }

    @Test
    fun `the generated text is deterministic and each look has its own library key`() {
        val keys = HashSet<Int>()
        for (look in FilterPack.looks) {
            val text = FilterPack.cube(look)
            assertEquals(text, FilterPack.cube(look))
            assertTrue(keys.add(LutStore.keyOf(text)))
        }
        assertEquals(FilterPack.looks.size, keys.size)
    }

    @Test
    fun `installing a look twice stores it once and a bad size is refused`() {
        val store = LutStore(tmp.newFolder("luts"))
        val look = checkNotNull(FilterPack.find("noir"))
        val first = store.import(look.name, FilterPack.cube(look))
        val second = store.import(look.name, FilterPack.cube(look))
        assertEquals(first, second)
        assertEquals(1, store.list().size)
        assertNotNull(store.load(first.key))
        assertNotEquals(first.key, store.import("Vivid", FilterPack.cube(checkNotNull(FilterPack.find("vivid")))).key)
        var refused = false
        try {
            FilterPack.cube(look, 1)
        } catch (e: IllegalArgumentException) {
            refused = true
        }
        assertTrue(refused)
    }
}
