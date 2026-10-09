package com.qtekfun.ultimatevideoeditor.ui.text

import com.qtekfun.ultimatevideoeditor.qa.QaSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** One place in the source where words for the user are typed into the code. */
internal data class HardCodedText(val line: Int, val text: String)

/**
 * Finds words typed into the code for the user to read: a literal given to `Text(...)`, to a parameter that is shown
 * (`contentDescription =`, `label =`, `title =`...), to a message, a notification or a snackbar, and sentences in general. It is a
 * line-based reading of Kotlin, not a parser, and errs on the side of flagging; a literal that is data and not words (a protocol
 * word, a file name) says so with `// i18n-ok` and a reason on its line.
 */
internal object HardCodedTextScanner {
    private const val MARK = "i18n-ok"

    /** A literal with a word of at least two letters (so "%d", "·", "/", "" and a bare "${'$'}it" are not words). */
    private const val WORDS = """"(?=[^"]*(?<![${'$'}\w{.])[A-Za-z]{2})(?:[^"\\]|\\.)*""""

    private val shown = listOf(
        // A literal as the first argument of a composable that draws text, or of a message type.
        Regex("""\bText\(\s*(?:if\s*\([^)]*\)\s*)?$WORDS"""),
        Regex("""\b(?:contentDescription|label|title|text|placeholder|supportingText|confirmLabel|onClickLabel|onLongClickLabel|stateDescription|headline)\s*=\s*(?:if\s*\([^)]*\)\s*)?$WORDS"""),
        Regex("""\b(?:ShowMessage|Refused|Message|showSnackbar|setContentTitle|setContentText|setTicker|Toast\.makeText\([^,]*,)\(?\s*$WORDS"""),
        Regex("""\baddAction\(\s*\w+\s*,\s*$WORDS"""),
        Regex("""\bUiText\.Raw\(\s*$WORDS"""),
        Regex("""\bcreateChooser\([^,]*,\s*$WORDS"""),
    )

    /** A sentence: capital letter, then at least two more words. Catches text that is built before it is shown. */
    private val sentence = Regex(""""[A-Z][a-z]+(?:[ ,'-]+[A-Za-z][a-z']*){2,}[^"]*"""")

    /** Lines whose literals are for the developer, not the user. */
    private val developerLine = Regex("""\b(?:Log\.[a-z]|require|check|error|checkNotNull|requireNotNull|assert)\(|\bthrow\b|@Suppress|\bprintln\(""")

    fun scan(source: String): List<HardCodedText> {
        val found = LinkedHashMap<Int, String>()
        val lines = source.split('\n')
        val offsets = IntArray(lines.size)
        var running = 0
        for (i in lines.indices) {
            offsets[i] = running
            running += lines[i].length + 1
        }
        fun lineOf(offset: Int): Int {
            var low = 0
            var high = lines.lastIndex
            while (low < high) {
                val mid = (low + high + 1) / 2
                if (offsets[mid] <= offset) low = mid else high = mid - 1
            }
            return low + 1
        }
        fun allowed(line: Int): Boolean {
            val text = lines[line - 1]
            val trimmed = text.trim()
            return text.contains(MARK) || trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")
        }
        for (pattern in shown) {
            for (match in pattern.findAll(source)) {
                val line = lineOf(match.range.last)
                if (!allowed(line) && !allowed(lineOf(match.range.first))) found.putIfAbsent(line, match.value.take(100).replace('\n', ' '))
            }
        }
        for ((index, text) in lines.withIndex()) {
            val line = index + 1
            if (allowed(line) || developerLine.containsMatchIn(text)) continue
            val match = sentence.find(text) ?: continue
            found.putIfAbsent(line, match.value.take(100))
        }
        return found.map { HardCodedText(it.key, it.value) }.sortedBy { it.line }
    }
}

/**
 * The ratchet of the translation work: every file under `src/main/kotlin` except those on `src/test/i18n/unmigrated.txt` must
 * have no words typed into the code, so a screen that has been moved to string resources cannot slip back, and a new screen
 * cannot start English-only. The allow-list names what is still English and only ever gets shorter: an entry with nothing left
 * to flag has to be removed (that is the stage's last step), and an entry for a file that does not exist is an error.
 */
class HardCodedTextRatchetTest {
    private val sourceRoot = QaSources.main("kotlin/com/qtekfun/ultimatevideoeditor")

    /** Entries are path prefixes below the source root; a line starting with `!` takes a path back out of an earlier entry. */
    private val entries: List<String> = QaSources.appDir.resolve("src/test/i18n/unmigrated.txt").readLines()
        .map { it.substringBefore('#').trim() }
        .filter { it.isNotEmpty() }

    private fun relative(file: File): String = file.relativeTo(sourceRoot).path

    private fun isAllowed(path: String): Boolean {
        var allowed = false
        for (entry in entries) {
            if (entry.startsWith("!")) {
                if (path.startsWith(entry.removePrefix("!"))) allowed = false
            } else if (path.startsWith(entry)) {
                allowed = true
            }
        }
        return allowed
    }

    private val files: List<File> = sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.toList()

    @Test
    fun `migrated files have no words typed into the code`() {
        val offences = ArrayList<String>()
        for (file in files) {
            if (isAllowed(relative(file))) continue
            for (hit in HardCodedTextScanner.scan(file.readText())) {
                offences += "${QaSources.relative(file)}:${hit.line}: ${hit.text}"
            }
        }
        assertTrue(
            "words for the user typed into migrated code (use a string resource or a UiText, or mark a literal that is data with // i18n-ok and a reason):\n" +
                offences.joinToString("\n"),
            offences.isEmpty(),
        )
    }

    @Test
    fun `the allow-list names only what exists and still has English in it`() {
        val problems = ArrayList<String>()
        for (entry in entries.filter { !it.startsWith("!") }) {
            val inside = files.filter { relative(it).startsWith(entry) && isAllowed(relative(it)) }
            if (inside.isEmpty()) {
                problems += "$entry matches no file: remove it from src/test/i18n/unmigrated.txt"
                continue
            }
            if (inside.none { HardCodedTextScanner.scan(it.readText()).isNotEmpty() }) {
                problems += "$entry has no hard-coded text left: remove it from src/test/i18n/unmigrated.txt (the list only shrinks)"
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun `the detector finds what it is meant to find`() {
        fun lines(code: String) = HardCodedTextScanner.scan(code).map { it.line }

        assertEquals(listOf(1), lines("""Text("Hello world")"""))
        assertEquals(listOf(2), lines("Column {\n    Text(\"Share\")\n}"))
        assertEquals(listOf(1), lines("""Icon(x, contentDescription = "Close search")"""))
        assertEquals(listOf(1), lines("""emit(HubEffect.ShowMessage("Project exported"))"""))
        assertEquals(listOf(2), lines("Text(\n    \"Welcome to the app\",\n)"))
        assertEquals(listOf(1), lines("""val reason = "The storage is full. Free some space.""""))
        assertEquals(listOf(1), lines("""Text(if (open) "Hide" else label)"""))
        assertEquals(listOf(1), lines("""builder.addAction(0, "Cancel", cancel)"""))
        assertEquals(listOf(1), lines("""UiText.Raw("Backup started")"""))
    }

    @Test
    fun `the detector leaves data, resources and developer messages alone`() {
        fun lines(code: String) = HardCodedTextScanner.scan(code).map { it.line }

        assertEquals(emptyList<Int>(), lines("""Text(stringResource(R.string.hub_new_project))"""))
        assertEquals(emptyList<Int>(), lines("""Text(project.name)"""))
        assertEquals(emptyList<Int>(), lines("""Text("%1d%%")"""))
        assertEquals(emptyList<Int>(), lines("""Text("·")"""))
        assertEquals(emptyList<Int>(), lines("""UiText.Raw("")"""))
        assertEquals(emptyList<Int>(), lines("""Log.w(TAG, "Could not start the export service; running without it", e)"""))
        assertEquals(emptyList<Int>(), lines("""require(width > 0) { "the width must be positive for this" }"""))
        assertEquals(emptyList<Int>(), lines("""throw IOException("A clip refers to media that is no longer in the project")"""))
        assertEquals(emptyList<Int>(), lines("// Text(\"Hello world\") is only a comment"))
        assertEquals(emptyList<Int>(), lines("""val tag = "UVExport""""))
        assertEquals(emptyList<Int>(), lines("""Text("Raw data from a provider") // i18n-ok: shown as the provider sent it"""))
    }
}
