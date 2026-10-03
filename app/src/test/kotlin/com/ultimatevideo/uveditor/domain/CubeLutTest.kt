package com.ultimatevideo.uveditor.domain

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CubeLutTest {
    /** The identity LUT of [size]: output equals input. */
    private fun identity(size: Int, header: String = ""): String = buildString {
        append(header)
        append("LUT_3D_SIZE $size\n")
        for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
            val d = (size - 1).toFloat()
            append("${r / d} ${g / d} ${b / d}\n")
        }
    }

    @Test
    fun `an identity LUT of every common size parses and samples back its input`() {
        for (size in listOf(2, 17, 33, 65)) {
            val lut = CubeParser.parse(identity(size, "TITLE \"Neutral\"\n# a comment\n\nDOMAIN_MIN 0 0 0\nDOMAIN_MAX 1 1 1\n"))
            assertEquals(size, lut.size)
            assertEquals("Neutral", lut.title)
            for (c in listOf(Triple(0f, 0f, 0f), Triple(1f, 1f, 1f), Triple(0.25f, 0.5f, 0.8f), Triple(0.123f, 0.9f, 0.01f))) {
                val out = lut.sample(c.first, c.second, c.third)
                assertArrayEquals("size $size at $c", floatArrayOf(c.first, c.second, c.third), out, 1e-4f)
            }
        }
    }

    @Test
    fun `red varies fastest so a red-to-blue swap LUT swaps channels`() {
        // Output = (b, g, r) of the input.
        val size = 2
        val text = buildString {
            append("LUT_3D_SIZE 2\n")
            for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) append("$b $g $r\n")
        }
        val lut = CubeParser.parse(text)
        assertArrayEquals(floatArrayOf(0.2f, 0.5f, 0.9f), lut.sample(0.9f, 0.5f, 0.2f), 1e-5f)
    }

    @Test
    fun `sampling is trilinear between entries and clamps its input`() {
        val lut = CubeParser.parse(
            "LUT_3D_SIZE 2\n" +
                "0 0 0\n1 0 0\n0 1 0\n1 1 0\n0 0 1\n1 0 1\n0 1 1\n1 1 1\n",
        )
        assertArrayEquals(floatArrayOf(0.5f, 0.5f, 0.5f), lut.sample(0.5f, 0.5f, 0.5f), 1e-6f)
        assertArrayEquals(floatArrayOf(1f, 0f, 1f), lut.sample(2f, -1f, 9f), 1e-6f)
    }

    @Test
    fun `malformed files are rejected with the offending line`() {
        fun fails(text: String, part: String) {
            val e = assertThrows(LutParseException::class.java) { CubeParser.parse(text) }
            assertTrue("'${e.message}' should mention '$part'", e.message!!.contains(part))
        }
        fails("", "no LUT_3D_SIZE")
        fails("0 0 0\n", "data before LUT_3D_SIZE")
        fails("LUT_3D_SIZE 1\n", "between 2 and 65")
        fails("LUT_3D_SIZE 66\n", "between 2 and 65")
        fails("LUT_3D_SIZE abc\n", "whole number")
        fails("LUT_3D_SIZE 2\nLUT_3D_SIZE 2\n", "twice")
        fails("LUT_1D_SIZE 16\n", "1D LUTs")
        fails("LUT_3D_SIZE 2\n0 0\n", "expected 3 numbers")
        fails("LUT_3D_SIZE 2\n0 0 x\n", "not a number")
        fails("LUT_3D_SIZE 2\n0 0 NaN\n", "not finite")
        fails("LUT_3D_SIZE 2\n0 0 0\n", "expected 8 entries, found 1")
        fails(identity(2) + "0 0 0\n", "more entries")
        fails("DOMAIN_MIN 0 0 0\nDOMAIN_MAX 2 2 2\nLUT_3D_SIZE 2\n", "default 0..1")
        fails("FOO 1\n", "unknown keyword")
    }

    @Test
    fun `the line number is reported`() {
        val e = assertThrows(LutParseException::class.java) { CubeParser.parse("# c\nLUT_3D_SIZE 2\n0 0 q\n") }
        assertEquals(3, e.line)
    }

    @Test
    fun `values outside 0 to 1 are kept as written`() {
        val lut = CubeParser.parse("LUT_3D_SIZE 2\n" + "-0.1 0 0\n".repeat(7) + "1.2 1 1\n")
        assertEquals(-0.1f, lut.data[0], 0f)
        assertEquals(1.2f, lut.data[21], 0f)
    }
}
