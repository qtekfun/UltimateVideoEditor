package com.qtekfun.ultimatevideoeditor.ui.editor.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Keeps the voice-over to one entry point (the tool row) and its panel in the chrome above the transport, so it cannot drift back. */
class VoiceOverEntryPointTest {
    private val sourceRoot: File =
        listOf(File("."), File("app")).map { File(it, "src/main/kotlin/com/qtekfun/ultimatevideoeditor") }.first { it.isDirectory }

    private fun source(path: String) = File(sourceRoot, path).readText()

    @Test
    fun `the media tray has no voice-over button`() {
        val tray = sourceRoot.resolve("ui/editor/tray").walkTopDown().filter { it.isFile && it.extension == "kt" }
        assertTrue(tray.none { it.readText().contains("VoiceOver") })
    }

    @Test
    fun `the tool row has the microphone button and the panel is drawn in the chrome slot`() {
        val editor = source("ui/editor/EditorScreen.kt")
        assertTrue(editor.contains("ToolbarItem.VOICE_OVER ->"))
        val chrome = editor.indexOf("chrome = {")
        val panel = editor.indexOf("VoiceOverPanel(voice")
        val transport = editor.indexOf("// Transport: timecode", chrome)
        assertTrue(chrome in 0 until panel && panel < transport)
        assertFalse(editor.contains("VoiceOverBar"))
    }
}
