package com.ultimatevideo.uveditor.data

import com.ultimatevideo.uveditor.domain.CurvePoint
import com.ultimatevideo.uveditor.domain.EffectType
import com.ultimatevideo.uveditor.domain.GradeCurve
import com.ultimatevideo.uveditor.domain.GradeCurves
import com.ultimatevideo.uveditor.domain.GradeLook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class LookStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun store() = LookStore(File(temp.root, "looks"))

    private fun look(id: String, name: String, lift: Double = 0.0): GradeLook {
        val values = EffectType.COLOR_GRADE.defaults.toMutableList().also { it[3] = lift }
        val curves = GradeCurves(master = GradeCurve(listOf(CurvePoint(0.0, 0.1), CurvePoint(0.5, 0.45), CurvePoint(1.0, 1.0))))
        return GradeLook(id, name, values, curves)
    }

    @Test
    fun `a saved look comes back with its values and curves`() {
        val s = store()
        val saved = look("l1", "Warm", lift = 0.3)
        s.save(saved)
        assertEquals(listOf(saved), s.list())
    }

    @Test
    fun `identity curves are not written and read back as identity`() {
        val s = store()
        val plain = GradeLook("l1", "Plain", EffectType.COLOR_GRADE.defaults)
        s.save(plain)
        val text = File(temp.root, "looks/l1.json").readText()
        assertFalse(text.contains("\"master\""))
        assertEquals(listOf(plain), s.list())
    }

    @Test
    fun `looks are listed by name and replaced by id`() {
        val s = store()
        s.save(look("b", "beta"))
        s.save(look("a", "Alpha"))
        assertEquals(listOf("Alpha", "beta"), s.list().map { it.name })
        s.save(look("a", "Alpha 2"))
        assertEquals(listOf("Alpha 2", "beta"), s.list().map { it.name })
    }

    @Test
    fun `deleting removes the file`() {
        val s = store()
        s.save(look("l1", "One"))
        assertTrue(s.delete("l1"))
        assertEquals(emptyList<GradeLook>(), s.list())
        assertFalse(s.delete("l1"))
    }

    @Test
    fun `an invalid look is refused and corrupt files are skipped`() {
        val s = store()
        assertThrows(IOException::class.java) { s.save(GradeLook("l1", "Bad", listOf(1.0))) }
        s.save(look("good", "Good"))
        File(temp.root, "looks/broken.json").writeText("{ not json")
        File(temp.root, "looks/invalid.json").writeText("""{"id":"x","name":"X","values":[1.0]}""")
        File(temp.root, "looks/readme.txt").writeText("hello")
        assertEquals(listOf("Good"), s.list().map { it.name })
    }

    @Test
    fun `an id cannot escape the looks directory`() {
        val s = store()
        s.save(look("../../evil", "Evil"))
        assertTrue(File(temp.root, "looks").listFiles().orEmpty().all { it.parentFile == File(temp.root, "looks") })
        assertFalse(File(temp.root, "evil.json").exists())
        assertEquals(1, s.list().size)
    }

    @Test
    fun `listing a store that was never written is empty`() {
        assertEquals(emptyList<GradeLook>(), store().list())
    }
}
