package com.ultimatevideo.uveditor.ui.about

/** A run of text with inline styling; links keep only their label. */
internal data class MdSpan(val text: String, val bold: Boolean = false, val italic: Boolean = false, val code: Boolean = false)

internal sealed interface MdBlock {
    data class Heading(val level: Int, val spans: List<MdSpan>) : MdBlock
    data class Paragraph(val spans: List<MdSpan>) : MdBlock

    /** [marker] is "•" for bullets or "3." for numbered items; [depth] is the nesting level (0-based). */
    data class ListItem(val marker: String, val depth: Int, val spans: List<MdSpan>) : MdBlock
    data class Table(val header: List<List<MdSpan>>, val rows: List<List<List<MdSpan>>>) : MdBlock
    data class Code(val text: String) : MdBlock
    data object Rule : MdBlock
}

/**
 * A small Markdown reader for our own documents (PRIVACY.md, THIRD_PARTY_NOTICES.md): headings, paragraphs, bullet and
 * numbered lists, pipe tables, fenced code, rules, and inline bold / italic / code / links. Anything else shows as text.
 */
internal object MarkdownBlocks {
    private val heading = Regex("^(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val bullet = Regex("^(\\s*)[-*+]\\s+(.*)$")
    private val numbered = Regex("^(\\s*)(\\d+)[.)]\\s+(.*)$")
    private val rule = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")
    private val tableDivider = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")

    fun parse(text: String): List<MdBlock> {
        val lines = text.replace("\r\n", "\n").lines()
        val out = ArrayList<MdBlock>()
        val para = ArrayList<String>()
        fun flush() {
            if (para.isNotEmpty()) out += MdBlock.Paragraph(inline(para.joinToString(" ")))
            para.clear()
        }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val h = heading.matchEntire(line)
            val b = bullet.matchEntire(line)
            val n = numbered.matchEntire(line)
            when {
                line.trimStart().startsWith("```") -> {
                    flush()
                    val code = ArrayList<String>()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith("```")) code += lines[i++]
                    out += MdBlock.Code(code.joinToString("\n"))
                }
                line.isBlank() -> flush()
                h != null -> {
                    flush()
                    out += MdBlock.Heading(h.groupValues[1].length, inline(h.groupValues[2]))
                }
                rule.matches(line) -> { flush(); out += MdBlock.Rule }
                isTableStart(lines, i) -> {
                    flush()
                    val header = cells(line).map(::inline)
                    i += 2
                    val rows = ArrayList<List<List<MdSpan>>>()
                    while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) rows += cells(lines[i++]).map(::inline)
                    out += MdBlock.Table(header, rows)
                    continue
                }
                bullet.matches(line) -> {
                    flush()
                    val m = bullet.matchEntire(line)!!
                    out += MdBlock.ListItem("•", depth(m.groupValues[1]), inline(m.groupValues[2]))
                }
                numbered.matches(line) -> {
                    flush()
                    val m = numbered.matchEntire(line)!!
                    out += MdBlock.ListItem(m.groupValues[2] + ".", depth(m.groupValues[1]), inline(m.groupValues[3]))
                }
                else -> {
                    // A wrapped continuation of the previous list item belongs to it rather than starting a paragraph.
                    val last = out.lastOrNull()
                    if (para.isEmpty() && last is MdBlock.ListItem && line.startsWith(" ")) {
                        out[out.size - 1] = last.copy(spans = last.spans + MdSpan(" ") + inline(line.trim()))
                    } else {
                        para += line.trim()
                    }
                }
            }
            i++
        }
        flush()
        return out
    }

    private fun depth(indent: String): Int = indent.replace("\t", "    ").length / 2

    private fun isTableStart(lines: List<String>, i: Int): Boolean =
        lines[i].contains('|') && i + 1 < lines.size && tableDivider.matches(lines[i + 1]) && lines[i + 1].contains('-')

    private fun cells(line: String): List<String> {
        val t = line.trim().removePrefix("|").removeSuffix("|")
        return t.split('|').map { it.trim() }
    }

    /** Inline markup: `**bold**`, `*italic*` / `_italic_`, `` `code` `` and `[label](url)`. Unmatched marks stay as text. */
    fun inline(text: String): List<MdSpan> {
        val spans = ArrayList<MdSpan>()
        val sb = StringBuilder()
        var bold = false
        var italic = false
        fun emit() {
            if (sb.isNotEmpty()) spans += MdSpan(sb.toString(), bold, italic)
            sb.clear()
        }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' && i + 1 < text.length && text[i + 1] in "\\`*_{}[]()#+-.!|" -> { sb.append(text[i + 1]); i += 2 }
                c == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i) {
                        emit()
                        spans += MdSpan(text.substring(i + 1, end), bold, italic, code = true)
                        i = end + 1
                    } else { sb.append(c); i++ }
                }
                text.startsWith("**", i) && closes(text, i, "**", bold) -> { emit(); bold = !bold; i += 2 }
                (c == '*' || (c == '_' && wordEdge(text, i))) && closes(text, i, c.toString(), italic) -> { emit(); italic = !italic; i++ }
                c == '[' -> {
                    val close = text.indexOf("](", i)
                    val end = if (close > i) text.indexOf(')', close) else -1
                    if (end > close) {
                        emit()
                        spans += inline(text.substring(i + 1, close)).map { it.copy(bold = it.bold || bold, italic = it.italic || italic) }
                        i = end + 1
                    } else { sb.append(c); i++ }
                }
                else -> { sb.append(c); i++ }
            }
        }
        emit()
        return spans
    }

    /** A mark opens only when a matching mark follows, and closes only when it is currently open. */
    private fun closes(text: String, at: Int, mark: String, open: Boolean): Boolean =
        open || (text.getOrNull(at + mark.length)?.isWhitespace() == false && text.indexOf(mark, at + mark.length) >= 0)

    /** Underscores inside words (snake_case) are not emphasis. */
    private fun wordEdge(text: String, i: Int): Boolean {
        val before = text.getOrNull(i - 1)
        val after = text.getOrNull(i + 1)
        return (before == null || !before.isLetterOrDigit()) || (after == null || !after.isLetterOrDigit())
    }
}
