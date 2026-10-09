package com.qtekfun.ultimatevideoeditor.ui.text

import com.qtekfun.ultimatevideoeditor.qa.QaSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.IllegalFormatException
import java.util.Locale

/**
 * Keeps the translations honest: a language is a `res/values-xx/strings.xml` and nothing else, so this test is the review of a
 * new language (see docs/TRANSLATING.md). Android lint (`MissingTranslation`, `ExtraTranslation`, `StringFormatInvalid`,
 * `StringFormatMatches`, `MissingQuantity`, errors in the build) says the same on the release build; this runs on every unit test
 * run and names the offending key.
 */
class TranslationsGuardTest {
    private val resDir = QaSources.main("res")
    private val source = StringsXml.parse(StringsXml.values())

    /** Every `values-xx` directory that holds a strings.xml, with its language code. */
    private val languages: Map<String, StringsXml> = (resDir.listFiles().orEmpty())
        .filter { it.isDirectory && it.name.startsWith("values-") && File(it, "strings.xml").exists() }
        .associate { it.name.removePrefix("values-") to StringsXml.parse(File(it, "strings.xml")) }

    /** The plural forms Android needs for a language (CLDR); a language that is not listed has to be added here with its rules. */
    private val pluralForms: Map<String, Set<String>> = mapOf(
        "en" to setOf("one", "other"),
        "es" to setOf("one", "many", "other"),
        "fr" to setOf("one", "many", "other"),
        "it" to setOf("one", "many", "other"),
        "pt" to setOf("one", "many", "other"),
        "de" to setOf("one", "other"),
        "nl" to setOf("one", "other"),
        "ca" to setOf("one", "many", "other"),
        "ru" to setOf("one", "few", "many", "other"),
        "pl" to setOf("one", "few", "many", "other"),
        "uk" to setOf("one", "few", "many", "other"),
        "ja" to setOf("other"),
        "zh" to setOf("other"),
        "ko" to setOf("other"),
        "tr" to setOf("one", "other"),
    )

    private val formatSpec = Regex("""%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z%]""")

    private fun specs(text: String): List<String> = formatSpec.findAll(text).map { it.value }.sorted().toList()

    @Test
    fun `there is at least one translation and the source is English`() {
        assertTrue("values-es/strings.xml is the first translation", "es" in languages)
        assertTrue("the source has strings", source.strings.size > 100)
    }

    @Test
    fun `every language has exactly the keys of the source`() {
        val translatable = source.strings.keys.filter { it !in source.untranslatable } + source.plurals.keys.filter { it !in source.untranslatable }
        for ((language, xml) in languages) {
            val have = xml.strings.keys + xml.plurals.keys
            val missing = translatable.filter { it !in have }
            val extra = have.filter { it !in translatable }
            assertTrue("values-$language is missing: $missing", missing.isEmpty())
            assertTrue("values-$language has keys the source does not (or marks untranslatable): $extra", extra.isEmpty())
            // A key is a string in both or a plural in both.
            for (key in xml.strings.keys) assertTrue("$key is a string in values-$language but a plural in the source", key in source.strings)
            for (key in xml.plurals.keys) assertTrue("$key is a plural in values-$language but a string in the source", key in source.plurals)
        }
    }

    @Test
    fun `no translation is empty`() {
        for ((language, xml) in languages) {
            for ((key, text) in xml.strings) assertTrue("values-$language $key is empty", text.isNotBlank())
            for ((key, items) in xml.plurals) {
                for ((quantity, text) in items) assertTrue("values-$language $key ($quantity) is empty", text.isNotBlank())
            }
        }
        for ((key, text) in source.strings) assertTrue("the source string $key is empty", text.isNotBlank())
    }

    @Test
    fun `format arguments are the same as in the source and are valid`() {
        for ((language, xml) in languages) {
            for ((key, text) in xml.strings) {
                val sourceText = source.strings[key] ?: continue
                assertEquals("values-$language $key must use the format arguments of the source", specs(sourceText), specs(text))
            }
            for ((key, items) in xml.plurals) {
                val sourceItems = source.plurals[key] ?: continue
                for ((quantity, text) in items) {
                    // A form the source has not (English has no "many") uses the arguments of its "other".
                    val reference = sourceItems[quantity] ?: sourceItems.getValue("other")
                    assertEquals("values-$language $key ($quantity) must use the format arguments of the source", specs(reference), specs(text))
                }
            }
        }
    }

    @Test
    fun `every string formats without an error`() {
        fun check(where: String, raw: String) {
            val text = StringsXml.unescape(raw)
            val arguments: Array<Any> = specs(text).filter { it != "%%" }.map<String, Any> { spec ->
                when (spec.last()) {
                    'd' -> 1
                    'f', 'e', 'g' -> 1.5
                    else -> "x"
                }
            }.toTypedArray()
            // Positional specifiers can repeat; give enough arguments for the highest index.
            val highest = Regex("""%(\d+)\$""").findAll(text).maxOfOrNull { it.groupValues[1].toInt() } ?: arguments.size
            val padded = Array<Any>(maxOf(highest, arguments.size)) { index -> arguments.getOrElse(index) { "x" } }
            try {
                String.format(Locale.ROOT, text, *padded)
            } catch (e: IllegalFormatException) {
                throw AssertionError("$where does not format: ${e.message} in \"$raw\"")
            }
        }
        for ((key, raw) in source.strings) check("values $key", raw)
        for ((key, items) in source.plurals) for ((quantity, raw) in items) check("values $key ($quantity)", raw)
        for ((language, xml) in languages) {
            for ((key, raw) in xml.strings) check("values-$language $key", raw)
            for ((key, items) in xml.plurals) for ((quantity, raw) in items) check("values-$language $key ($quantity)", raw)
        }
    }

    @Test
    fun `plurals have the forms the language needs and no others`() {
        fun check(language: String, xml: StringsXml) {
            val needed = pluralForms[language] ?: error("add the plural forms of '$language' to pluralForms in this test (CLDR rules)")
            for ((key, items) in xml.plurals) {
                assertEquals("$language $key must have exactly the forms $needed", needed, items.keys)
            }
        }
        check("en", source)
        for ((language, xml) in languages) check(language, xml)
    }

    @Test
    fun `the supported languages are the source plus the translations`() {
        val declared = Regex("""android:name="([^"]+)"""").findAll(QaSources.main("res/xml/locales_config.xml").readText()).map { it.groupValues[1] }.toList()
        assertEquals("res/xml/locales_config.xml lists en and every values-xx", (listOf("en") + languages.keys).sorted(), declared.sorted())
        for (language in languages.keys) {
            assertTrue("values-$language: only a bare language code is supported (regions share the translation)", Regex("[a-z]{2,3}").matches(language))
        }
    }
}
