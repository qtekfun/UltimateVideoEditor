package com.qtekfun.ultimatevideoeditor.ui.editor.title

import com.qtekfun.ultimatevideoeditor.data.FontRegistry
import com.qtekfun.ultimatevideoeditor.data.TitlePresetStore
import com.qtekfun.ultimatevideoeditor.data.fakeFont
import com.qtekfun.ultimatevideoeditor.domain.MotionPreset
import com.qtekfun.ultimatevideoeditor.domain.TextLayer
import com.qtekfun.ultimatevideoeditor.domain.TitleLayerEdit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class TitleLibraryViewModelTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Files by uri; a missing uri or an oversized file behaves like an unreadable one. */
    private class Files(val bytes: MutableMap<String, ByteArray> = mutableMapOf()) : BytesReader, TextWriter {
        val written = mutableMapOf<String, String>()
        var failWrites = false

        override fun read(uri: String, maxBytes: Int): ByteArray {
            val data = bytes[uri] ?: throw IOException("missing")
            if (data.size > maxBytes) throw IOException("too large")
            return data
        }

        override fun write(uri: String, text: String) {
            if (failWrites) throw IOException("disk full")
            written[uri] = text
        }
    }

    private fun vm(files: Files = Files()) =
        TitleLibraryViewModel(FontRegistry(File(folder.root, "fonts")), TitlePresetStore(File(folder.root, "presets")), files, files, dispatcher)

    private val content = TitleLayerEdit.of(listOf(TextLayer("Hello", fontId = "0123456789abcdef"), TitleLayerEdit.newShape(com.qtekfun.ultimatevideoeditor.domain.ShapeKind.RECT)))

    @Test
    fun `importing a font lists it, says so and reports the licence reminder`() = runTest(dispatcher) {
        val files = Files(mutableMapOf("f1" to fakeFont("Alpha")))
        val vm = vm(files)
        var imported: String? = null
        vm.importFont("f1") { imported = it.family }
        assertEquals("Alpha", imported)
        assertEquals(listOf("Alpha"), vm.state.value.fonts.map { it.family })
        assertEquals(1, vm.state.value.fontIds.size)
        assertTrue(vm.state.value.message!!.contains("licence"))
        assertFalse(vm.state.value.busy)
        vm.clearMessage()
        assertNull(vm.state.value.message)
    }

    @Test
    fun `a bad or unreadable font file gives a message and changes nothing`() = runTest(dispatcher) {
        val files = Files(mutableMapOf("bad" to "this is not a font file".toByteArray()))
        val vm = vm(files)
        vm.importFont("bad")
        assertTrue(vm.state.value.message!!.contains("not a TrueType or OpenType"))
        assertTrue(vm.state.value.fonts.isEmpty())
        vm.importFont("gone")
        assertTrue(vm.state.value.message!!.contains("could not be read"))
        assertFalse(vm.state.value.busy)
    }

    @Test
    fun `removing a font forgets it`() = runTest(dispatcher) {
        val files = Files(mutableMapOf("f1" to fakeFont("Alpha")))
        val vm = vm(files)
        vm.importFont("f1")
        vm.removeFont(vm.state.value.fonts.single().id)
        assertTrue(vm.state.value.fonts.isEmpty())
    }

    @Test
    fun `a preset is saved with its font family, exported, deleted and imported again`() = runTest(dispatcher) {
        val files = Files(mutableMapOf("f1" to fakeFont("Fancy")))
        val vm = vm(files)
        vm.savePreset("  Mine ", content, 4.0, MotionPreset.POP, MotionPreset.FADE)
        val saved = vm.state.value.presets.single()
        assertEquals("Mine", saved.name)
        assertEquals(MotionPreset.POP, saved.intro)
        assertTrue(vm.state.value.message!!.contains("Saved"))

        vm.exportPreset(saved.id, "out")
        assertTrue(files.written.getValue("out").contains("\"format\": \"uvtitle\""))

        vm.deletePreset(saved.id)
        assertTrue(vm.state.value.presets.isEmpty())

        files.bytes["in"] = files.written.getValue("out").toByteArray()
        vm.importPreset("in")
        assertEquals("Mine", vm.state.value.presets.single().name)
        // The font the preset names is not on this device: the message says which.
        assertTrue(vm.state.value.message!!.contains("Missing fonts"))
        assertNotNull(vm.state.value.presets.single().fonts.firstOrNull())
    }

    @Test
    fun `bad preset files and write failures are reported`() = runTest(dispatcher) {
        val files = Files(mutableMapOf("junk" to "{ nope".toByteArray()))
        val vm = vm(files)
        vm.importPreset("junk")
        assertTrue(vm.state.value.message!!.contains("not a title preset"))
        assertFalse(vm.state.value.busy)

        vm.savePreset("Ok", content, 4.0, MotionPreset.NONE, MotionPreset.NONE)
        files.failWrites = true
        vm.exportPreset(vm.state.value.presets.single().id, "x")
        assertTrue(vm.state.value.message!!.contains("could not be written"))
        vm.exportPreset("missing", "x")
        assertTrue(vm.state.value.message!!.contains("no longer there"))
    }
}
