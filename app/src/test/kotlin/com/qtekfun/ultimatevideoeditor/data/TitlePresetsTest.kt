package com.qtekfun.ultimatevideoeditor.data

import com.qtekfun.ultimatevideoeditor.domain.ImageLayer
import com.qtekfun.ultimatevideoeditor.domain.MotionPreset
import com.qtekfun.ultimatevideoeditor.domain.PresetFont
import com.qtekfun.ultimatevideoeditor.domain.StillKind
import com.qtekfun.ultimatevideoeditor.domain.TextLayer
import com.qtekfun.ultimatevideoeditor.domain.TextTemplates
import com.qtekfun.ultimatevideoeditor.domain.TitleLayerEdit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TitlePresetsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun store() = TitlePresetStore(File(folder.root, "presets"))

    private val content = TitleLayerEdit.of(
        listOf(
            TitleLayerEdit.newShape(com.qtekfun.ultimatevideoeditor.domain.ShapeKind.ROUNDED_RECT),
            TextLayer("Hello", fontId = "0123456789abcdef", bold = true),
            ImageLayer(StillKind.STICKER, "emoji:1f600"),
            ImageLayer(StillKind.PHOTO, "asset-1"),
        ),
    )

    @Test
    fun `every built-in template survives a trip through the file format`() {
        for (template in TextTemplates.all) {
            val text = TitlePresetCodec.encode(template)
            val back = TitlePresetCodec.decode(text, template.id)
            assertEquals(template.copy(builtIn = false), back)
        }
    }

    @Test
    fun `the file names its format and version`() {
        val text = TitlePresetCodec.encode(TextTemplates.all.first())
        assertTrue(text.contains("\"format\": \"uvtitle\""))
        assertTrue(text.contains("\"version\": 1"))
    }

    @Test
    fun `files that are not presets or are from the future are refused with a reason`() {
        fun fails(text: String) = assertThrows(PresetFormatException::class.java) { TitlePresetCodec.decode(text, "x") }
        fails("")
        fails("not json")
        fails("""{"format":"other","name":"x","layers":[]}""")
        fails("""{"format":"uvtitle","version":2,"name":"x","layers":[{"type":"text","text":"a"}]}""")
        fails("""{"format":"uvtitle","name":"x","layers":[]}""")
        fails("""{"format":"uvtitle","name":"x","layers":[{"type":"hologram"}]}""")
        fails("""{"format":"uvtitle","name":"x","layers":[{"type":"text","text":"a","size":9.0}]}""")
        fails("""{"format":"uvtitle","name":"x","seconds":0.0,"layers":[{"type":"text","text":"a"}]}""")
        fails("x".repeat(TitlePresetCodec.MAX_BYTES + 1))
    }

    @Test
    fun `photos cannot travel in a preset`() {
        val text = """{"format":"uvtitle","name":"P","layers":[{"type":"image","image":"photo","source":"a","size":0.3},{"type":"text","text":"Hi"}]}"""
        val template = TitlePresetCodec.decode(text, "p")
        assertEquals(1, template.layers.size)
        assertTrue(template.layers.single() is TextLayer)
    }

    @Test
    fun `saving a title as a preset drops its photos, names its fonts and can be listed and applied`() {
        val store = store()
        val saved = store.saveFrom("  My lower third ", content, 5.0, MotionPreset.SLIDE_LEFT, MotionPreset.FADE, 0.5) { id -> if (id == "0123456789abcdef") "Fancy Font" else null }
        assertEquals("My lower third", saved.name)
        assertEquals("Hello", saved.defaultText)
        assertEquals(3, saved.layers.size)
        assertEquals(listOf(PresetFont("0123456789abcdef", "Fancy Font")), saved.fonts)
        assertEquals(false, saved.builtIn)
        assertEquals(listOf(saved), store.list())
        // Saving the same title again replaces it instead of adding a copy.
        store.saveFrom("My lower third", content, 5.0, MotionPreset.SLIDE_LEFT, MotionPreset.FADE, 0.5) { null }
        assertEquals(1, store.list().size)
    }

    @Test
    fun `an exported preset can be imported on another device as a new preset`() {
        val store = store()
        val saved = store.saveFrom("Shared", content, 4.0, MotionPreset.POP, MotionPreset.NONE, 0.3) { "Fancy" }
        val text = store.exportText(saved.id)!!
        val other = TitlePresetStore(File(folder.root, "other"))
        val imported = other.import(text)
        assertEquals("Shared", imported.name)
        assertEquals(saved.layers, imported.layers)
        assertEquals(MotionPreset.POP, imported.intro)
        assertEquals(listOf(imported), other.list())
        assertNull(store.exportText("missing"))
    }

    @Test
    fun `damaged preset files are skipped when listing and delete removes the file`() {
        val store = store()
        val saved = store.saveFrom("Keep", content, 4.0, MotionPreset.NONE, MotionPreset.NONE, 0.3) { null }
        File(folder.root, "presets/broken.uvtitle").writeText("{ nope")
        assertEquals(listOf("Keep"), store.list().map { it.name })
        assertTrue(store.delete(saved.id))
        assertEquals(emptyList<Any>(), store.list())
        assertEquals(false, store.delete(saved.id))
        assertNotNull(TextTemplates.find("lower-third"))
    }

    @Test
    fun `an empty title cannot be saved as a preset`() {
        val store = store()
        val onlyPhoto = TitleLayerEdit.of(listOf(ImageLayer(StillKind.PHOTO, "a")))
        assertThrows(PresetFormatException::class.java) {
            store.saveFrom("Photos only", onlyPhoto, 4.0, MotionPreset.NONE, MotionPreset.NONE, 0.3) { null }
        }
    }
}
