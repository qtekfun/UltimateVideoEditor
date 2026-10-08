package com.qtekfun.ultimatevideoeditor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.DataOutputStream

/** Builds a tiny but structurally valid sfnt: just the tables the parser looks at. */
internal fun fakeFont(
    family: String = "Fake Sans",
    cff: Boolean = false,
    platform: Int = 3,
    nameId: Int = 1,
    magic: Int = 0x00010000,
    withGlyphs: Boolean = true,
    withName: Boolean = true,
): ByteArray {
    val nameTable = ByteArrayOutputStream().also { out ->
        val text = if (platform == 3) family.toByteArray(Charsets.UTF_16BE) else family.toByteArray(Charsets.ISO_8859_1)
        DataOutputStream(out).apply {
            writeShort(0) // format
            writeShort(1) // count
            writeShort(6 + 12) // string offset
            writeShort(platform)
            writeShort(if (platform == 3) 1 else 0)
            writeShort(0)
            writeShort(nameId)
            writeShort(text.size)
            writeShort(0)
            write(text)
        }
    }.toByteArray()
    val filler = ByteArray(8) { 1 }
    val tables = buildList {
        add("head" to filler)
        add("cmap" to filler)
        if (withGlyphs) add((if (cff) "CFF " else "glyf") to filler)
        if (withName) add("name" to nameTable)
    }
    val out = ByteArrayOutputStream()
    DataOutputStream(out).apply {
        writeInt(magic)
        writeShort(tables.size)
        writeShort(0)
        writeShort(0)
        writeShort(0)
        var offset = 12 + tables.size * 16
        for ((tag, data) in tables) {
            write(tag.toByteArray(Charsets.ISO_8859_1))
            writeInt(0)
            writeInt(offset)
            writeInt(data.size)
            offset += data.size
        }
        for ((_, data) in tables) write(data)
    }
    return out.toByteArray()
}

class FontRegistryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun registry() = FontRegistry(File(folder.root, "fonts"))

    @Test
    fun `the parser reads the family name of a TrueType and an OpenType font`() {
        assertEquals(FontMeta("Fake Sans", false), FontMetaParser.parse(fakeFont()))
        assertEquals(FontMeta("Cff Serif", true), FontMetaParser.parse(fakeFont("Cff Serif", cff = true, magic = 0x4F54544F)))
        assertEquals("Mac Style", FontMetaParser.parse(fakeFont("Mac Style", platform = 1)).family)
        assertEquals("Typographic Family", FontMetaParser.parse(fakeFont("Typographic Family", nameId = 16)).family)
    }

    @Test
    fun `things that are not fonts are refused with a reason`() {
        assertThrows(FontException::class.java) { FontMetaParser.parse(ByteArray(0)) }
        assertThrows(FontException::class.java) { FontMetaParser.parse("hello world, not a font at all".toByteArray()) }
        assertThrows(FontException::class.java) { FontMetaParser.parse(fakeFont(magic = 0x74746366)) }
        assertThrows(FontException::class.java) { FontMetaParser.parse(fakeFont(withGlyphs = false)) }
        assertThrows(FontException::class.java) { FontMetaParser.parse(fakeFont(withName = false)) }
        assertThrows(FontException::class.java) { FontMetaParser.parse(fakeFont(nameId = 4)) }
        // A directory that points outside the file.
        assertThrows(FontException::class.java) { FontMetaParser.parse(fakeFont().copyOf(40)) }
        assertThrows(FontException::class.java) { FontMetaParser.parse(ByteArray(FontMetaParser.MAX_BYTES + 1)) }
    }

    @Test
    fun `importing stores the file under its hash and lists it by family`() {
        val registry = registry()
        val entry = registry.import(fakeFont("Zeta"))
        assertEquals("Zeta", entry.family)
        assertTrue(entry.file.isFile)
        assertEquals(FontRegistry.idOf(fakeFont("Zeta")), entry.id)
        assertEquals(16, entry.id.length)
        registry.import(fakeFont("Alpha", cff = true, magic = 0x4F54544F))
        assertEquals(listOf("Alpha", "Zeta"), registry.list().map { it.family })
        assertEquals(setOf(entry.id, FontRegistry.idOf(fakeFont("Alpha", cff = true, magic = 0x4F54544F))), registry.ids())
        assertTrue(registry.file(entry.id)!!.name.endsWith(".ttf"))
    }

    @Test
    fun `importing the same font twice keeps one file`() {
        val registry = registry()
        val first = registry.import(fakeFont("Same"))
        val second = registry.import(fakeFont("Same"))
        assertEquals(first.id, second.id)
        assertEquals(1, registry.list().size)
    }

    @Test
    fun `bad fonts are not stored`() {
        val registry = registry()
        assertThrows(FontException::class.java) { registry.import("nope".toByteArray()) }
        assertEquals(emptyList<FontEntry>(), registry.list())
        assertFalse(File(folder.root, "fonts").listFiles().orEmpty().any())
    }

    @Test
    fun `a damaged stored file is skipped and removing deletes the file`() {
        val registry = registry()
        val good = registry.import(fakeFont("Good"))
        File(folder.root, "fonts/${"a".repeat(16)}.ttf").writeBytes(byteArrayOf(1, 2, 3))
        assertEquals(listOf("Good"), registry.list().map { it.family })
        assertTrue(registry.remove(good.id))
        assertNull(registry.file(good.id))
        assertFalse(registry.remove(good.id))
    }

    @Test
    fun `ids that could escape the folder are never resolved`() {
        val registry = registry()
        registry.import(fakeFont("Safe"))
        assertNull(registry.file("../fonts/anything"))
        assertNull(registry.file(""))
        assertNull(registry.file("ABCDEF0123456789"))
        assertNotNull(registry.file(FontRegistry.idOf(fakeFont("Safe"))))
    }
}
