package com.qtekfun.ultimatevideoeditor.ui.about

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The privacy statement is shipped in every language it was translated to (`docs/PRIVACY.<language>.md`, shown in About).
 * A translation must keep the same shape as the English text, so a promise cannot be dropped or added in one language only:
 * the same sections, the same tables with the same number of rows and the same code names (the four permissions, the test).
 */
class PrivacyTranslationTest {
    private val docs: File = listOf(File("docs"), File("../docs")).first { it.isDirectory }
    private val english = File(docs, "PRIVACY.md").readText()
    private val translations = docs.listFiles { f -> Regex("PRIVACY\\.[a-z]{2}\\.md").matches(f.name) }.orEmpty().sortedBy { it.name }

    private fun headings(text: String) = text.lines().count { it.startsWith("## ") }
    private fun tableRows(text: String) = text.lines().count { it.startsWith("| ") }
    private fun codeNames(text: String) = Regex("`([^`]+)`").findAll(text).map { it.groupValues[1] }.toSet()

    @Test
    fun `there is at least the Spanish translation`() {
        assertTrue(translations.any { it.name == "PRIVACY.es.md" })
    }

    @Test
    fun `a translation has the same sections, table rows and code names as the English statement`() {
        for (file in translations) {
            val text = file.readText()
            assertEquals("${file.name}: sections", headings(english), headings(text))
            assertEquals("${file.name}: table rows", tableRows(english), tableRows(text))
            assertEquals("${file.name}: bullets", english.lines().count { it.startsWith("- ") }, text.lines().count { it.startsWith("- ") })
            // Names of files and commands in code spans are not translated; the translation may add none and drop none.
            val missing = codeNames(english).filter { it !in codeNames(text) }
            assertTrue("${file.name} lacks: $missing", missing.isEmpty())
            val extra = codeNames(text).filter { it !in codeNames(english) }
            assertTrue("${file.name} adds: $extra", extra.isEmpty())
        }
    }

    @Test
    fun `every translation promises no network permission and lists the four permissions`() {
        for (file in translations) {
            val text = file.readText()
            for (permission in listOf("INTERNET", "FOREGROUND_SERVICE", "FOREGROUND_SERVICE_MEDIA_PROCESSING", "FOREGROUND_SERVICE_DATA_SYNC", "POST_NOTIFICATIONS")) {
                assertTrue("${file.name} does not mention $permission", text.contains(permission))
            }
        }
    }
}
