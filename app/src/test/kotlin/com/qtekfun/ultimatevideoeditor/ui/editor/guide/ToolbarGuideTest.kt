package com.qtekfun.ultimatevideoeditor.ui.editor.guide

import com.qtekfun.ultimatevideoeditor.ui.about.AboutController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keeps the in-app toolbar guide from going stale: every editor icon needs a help entry, and every entry must
 * describe an icon that a screen really uses.
 */
class ToolbarGuideTest {
    private val sourceRoot: File =
        listOf(File("."), File("app")).map { File(it, "src/main/kotlin/com/qtekfun/ultimatevideoeditor") }.first { it.isDirectory }

    /** Icons that screens use but that are not editor symbols to explain (none today; add one with a reason). */
    private val notDocumented = emptySet<String>()

    /** `EditorIcons.Foo` and `SelectionIcons.Foo` as written in the screens, except in the icon definitions and the guide itself. */
    private fun iconsUsedByScreens(): Set<String> {
        val reference = Regex("""\b(?:EditorIcons|SelectionIcons)\.([A-Z][A-Za-z]+)""")
        return sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "EditorIcons.kt" && it.parentFile?.name != "guide" }
            .flatMap { file -> reference.findAll(file.readText()).map { it.groupValues[1] } }
            .toSet()
    }

    private fun iconsDocumented(): Set<String> = ToolbarGuide.entries.map { it.icon.name }.toSet()

    @Test
    fun `every icon the editor uses has a guide entry`() {
        val missing = iconsUsedByScreens() - iconsDocumented() - notDocumented
        assertTrue("Icons without a help entry in ToolbarGuide: $missing", missing.isEmpty())
    }

    @Test
    fun `every guide entry describes an icon some screen uses`() {
        val orphans = iconsDocumented() - iconsUsedByScreens()
        assertTrue("Guide entries for icons no screen uses: $orphans", orphans.isEmpty())
    }

    @Test
    fun `ids are unique and entries are complete`() {
        val ids = ToolbarGuide.entries.map { it.id } + ToolbarGuide.gestures.map { it.id }
        assertEquals("Duplicate ids: ${ids.groupBy { it }.filter { it.value.size > 1 }.keys}", ids.size, ids.toSet().size)
        for (entry in ToolbarGuide.entries) {
            assertTrue("${entry.id} needs a name and a help text", entry.name.isNotBlank() && entry.help.isNotBlank())
        }
        for (gesture in ToolbarGuide.gestures) {
            assertTrue("${gesture.id} needs a title and a help text", gesture.title.isNotBlank() && gesture.help.isNotBlank())
        }
    }

    @Test
    fun `no section is empty and an icon is documented once`() {
        for (section in GuideSection.entries) {
            assertTrue("Section ${section.title} has no entries", ToolbarGuide.entries.any { it.section == section })
        }
        assertEquals(ToolbarGuide.entries.size, ToolbarGuide.entries.map { it.icon.name }.toSet().size)
    }

    @Test
    fun `search matches names, help text and sections, and an empty query lists everything`() {
        assertEquals(ToolbarGuide.entries.size, ToolbarGuide.search("  ").size)
        assertTrue(ToolbarGuide.search("split").any { it.id == "split" })
        assertTrue(ToolbarGuide.search("SELECTION BAR").any { it.id == "copy" })
        assertTrue(ToolbarGuide.search("zzzz").isEmpty())
        assertTrue(ToolbarGuide.searchGestures("fullscreen").any { it.id == "double-tap-preview" })
    }

    @Test
    fun `the guide is reachable from the editor and from About, and the online guide is only handed to the browser`() {
        assertTrue(ToolbarGuide.entries.any { it.id == "help" })
        val editor = File(sourceRoot, "ui/editor/EditorScreen.kt").readText()
        assertTrue(editor.contains("ToolbarGuideScreen"))
        val about = File(sourceRoot, "ui/about/AboutScreen.kt").readText()
        assertTrue(about.contains("ToolbarGuideScreen"))
        assertTrue(about.contains("Intent.ACTION_VIEW"))
        assertTrue(AboutController.ONLINE_GUIDE_URL.startsWith("https://"))
    }
}
