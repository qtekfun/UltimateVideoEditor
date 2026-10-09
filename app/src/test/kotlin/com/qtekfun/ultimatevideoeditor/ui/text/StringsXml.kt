package com.qtekfun.ultimatevideoeditor.ui.text

import com.qtekfun.ultimatevideoeditor.qa.QaSources
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * A `strings.xml` read on the JVM: the same file Android compiles, so unit tests can say what a [UiText] shows without a device.
 * [strings] and [plurals] hold the text as Android would see it (escapes resolved); [raw] keeps the source form for format checks.
 */
internal class StringsXml(
    val strings: Map<String, String>,
    val plurals: Map<String, Map<String, String>>,
    /** Keys marked `translatable="false"`: they exist only in the source file. */
    val untranslatable: Set<String>,
) {
    companion object {
        fun parse(file: File): StringsXml {
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val strings = LinkedHashMap<String, String>()
            val plurals = LinkedHashMap<String, Map<String, String>>()
            val untranslatable = LinkedHashSet<String>()
            val nodes = document.documentElement.childNodes
            for (i in 0 until nodes.length) {
                val node = nodes.item(i) as? Element ?: continue
                val name = node.getAttribute("name")
                if (node.getAttribute("translatable") == "false") untranslatable += name
                when (node.tagName) {
                    "string" -> strings[name] = node.textContent
                    "plurals" -> {
                        val items = LinkedHashMap<String, String>()
                        val children = node.getElementsByTagName("item")
                        for (j in 0 until children.length) {
                            val item = children.item(j) as Element
                            items[item.getAttribute("quantity")] = item.textContent
                        }
                        plurals[name] = items
                    }
                }
            }
            return StringsXml(strings, plurals, untranslatable)
        }

        /** The text of a resource as the app shows it: quotes and escapes resolved the way aapt does. */
        fun unescape(raw: String): String {
            val out = StringBuilder()
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                if (c == '\\' && i + 1 < raw.length) {
                    val next = raw[i + 1]
                    when (next) {
                        'n' -> out.append('\n')
                        't' -> out.append('\t')
                        'u' -> {
                            out.append(raw.substring(i + 2, i + 6).toInt(16).toChar())
                            i += 4
                        }
                        else -> out.append(next)
                    }
                    i += 2
                } else {
                    out.append(c)
                    i++
                }
            }
            return out.toString()
        }

        fun values(qualifier: String = ""): File = QaSources.main("res/values$qualifier/strings.xml")
    }
}

/**
 * The words of one language, read from its `strings.xml`, as a [TextSource]: a unit test that builds a [UiText] can assert on what
 * the user would read while the production code stays free of Android. Resource ids are mapped back to names with the generated
 * `R` class. [quantity] is the CLDR plural rule of the language: the form name ("one", "many", "other"...) a count uses.
 */
internal class FileStrings(private val xml: StringsXml, private val locale: Locale, private val quantity: (Int) -> String) : TextSource {
    private val stringNames: Map<Int, String> = namesOf("com.qtekfun.ultimatevideoeditor.R\$string")
    private val pluralNames: Map<Int, String> = namesOf("com.qtekfun.ultimatevideoeditor.R\$plurals")

    private fun namesOf(className: String): Map<Int, String> =
        Class.forName(className).fields.filter { it.type == Int::class.javaPrimitiveType }.associate { it.getInt(null) to it.name }

    override fun string(id: Int, args: Array<Any>): String {
        val name = stringNames[id] ?: error("no string resource with id $id")
        val raw = xml.strings[name] ?: error("string $name is not in this strings.xml")
        return String.format(locale, StringsXml.unescape(raw), *args)
    }

    override fun plural(id: Int, quantity: Int, args: Array<Any>): String {
        val name = pluralNames[id] ?: error("no plurals resource with id $id")
        val items = xml.plurals[name] ?: error("plurals $name is not in this strings.xml")
        val raw = items[this.quantity(quantity)] ?: items["other"] ?: error("plurals $name has no form for $quantity")
        return String.format(locale, StringsXml.unescape(raw), *args)
    }

    /** The string resource [id] in this language, for a test of code that shows a resource directly. */
    fun text(id: Int, vararg args: Any): String = string(id, arrayOf(*args))
}

/** The English words of the app (`res/values/strings.xml`): what the tests assert on. English plurals: "one" for exactly 1. */
internal val EnglishStrings = FileStrings(StringsXml.parse(StringsXml.values()), Locale.US) { if (it == 1) "one" else "other" }

/** What the user would read, in English. */
internal fun UiText.english(): String = resolve(EnglishStrings)

/** [english] of each text. */
internal fun List<UiText>.english(): List<String> = map { it.english() }
