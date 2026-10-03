package com.ultimatevideo.uveditor.data

import com.ultimatevideo.uveditor.domain.LutParseException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LutStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun identity(size: Int, tweak: Float = 0f) = buildString {
        append("LUT_3D_SIZE $size\n")
        val d = (size - 1).toFloat()
        for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) append("${r / d + tweak} ${g / d} ${b / d}\n")
    }

    private fun store() = LutStore(folder.newFolder("luts"))

    @Test
    fun `an imported LUT is listed and loads back`() {
        val s = store()
        val info = s.import("Warm film.cube", identity(17))
        assertEquals("Warm film", info.name)
        assertEquals(17, info.size)
        assertTrue(info.key in 1..0xFFFFFF)
        assertEquals(listOf(info), s.list())
        assertEquals(17, s.load(info.key)!!.size)
    }

    @Test
    fun `importing the same file twice is a no-op and different files get different keys`() {
        val s = store()
        val a = s.import("a.cube", identity(2))
        assertEquals(a, s.import("renamed.cube", identity(2)))
        assertEquals(1, s.list().size)
        assertNotEquals(a.key, s.import("b.cube", identity(2, tweak = 0.1f)).key)
    }

    @Test
    fun `a bad file is rejected and nothing is stored`() {
        val s = store()
        assertThrows(LutParseException::class.java) { s.import("bad.cube", "LUT_3D_SIZE 2\n0 0 0\n") }
        assertTrue(s.list().isEmpty())
    }

    @Test
    fun `a missing or deleted LUT loads as null`() {
        val s = store()
        assertNull(s.load(12345))
        val info = s.import("x.cube", identity(2))
        assertTrue(s.delete(info.key))
        assertNull(s.load(info.key))
        assertTrue(s.list().isEmpty())
    }

    @Test
    fun `names are made safe for the file system`() {
        val info = store().import("../../evil/na:me?.cube", identity(2))
        assertTrue(info.name.none { it == '/' || it == ':' || it == '?' })
    }
}
