package com.ultimatevideo.uveditor.ui.about

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownBlocksTest {
    private fun plain(spans: List<MdSpan>) = spans.joinToString("") { it.text }

    @Test fun headingLevelsAndText() {
        val b = MarkdownBlocks.parse("# Title\n\n### Sub **x**")
        assertEquals(MdBlock.Heading(1, listOf(MdSpan("Title"))), b[0])
        val h = b[1] as MdBlock.Heading
        assertEquals(3, h.level)
        assertEquals(listOf(MdSpan("Sub "), MdSpan("x", bold = true)), h.spans)
    }

    @Test fun inlineBoldItalicCodeAndLinks() {
        val s = MarkdownBlocks.inline("a **b** *c* `d_e` [Oboe](https://x.y/z) end")
        assertEquals("a b c d_e Oboe end", plain(s))
        assertEquals(true, s.first { it.text == "b" }.bold)
        assertEquals(true, s.first { it.text == "c" }.italic)
        assertEquals(true, s.first { it.text == "d_e" }.code)
    }

    @Test fun unmatchedMarksAndSnakeCaseStayText() {
        assertEquals("2 * 3 and snake_case_name and a*b", plain(MarkdownBlocks.inline("2 * 3 and snake_case_name and a*b")))
    }

    @Test fun codeSpanKeepsMarkup() {
        val s = MarkdownBlocks.inline("`**not bold**`")
        assertEquals(listOf(MdSpan("**not bold**", code = true)), s)
    }

    @Test fun listsBulletNumberedAndNested() {
        val b = MarkdownBlocks.parse("- one\n  - nested\n1. first\n2. second")
        assertEquals(listOf("•", "•", "1.", "2."), b.map { (it as MdBlock.ListItem).marker })
        assertEquals(listOf(0, 1, 0, 0), b.map { (it as MdBlock.ListItem).depth })
    }

    @Test fun wrappedListContinuationJoinsItem() {
        val b = MarkdownBlocks.parse("- long item\n  continues here")
        assertEquals(1, b.size)
        assertEquals("long item continues here", plain((b[0] as MdBlock.ListItem).spans))
    }

    @Test fun paragraphLinesAreJoinedAndSplitByBlankLine() {
        val b = MarkdownBlocks.parse("line one\nline two\n\nnext")
        assertEquals(listOf("line one line two", "next"), b.map { plain((it as MdBlock.Paragraph).spans) })
    }

    @Test fun tableHasHeaderAndRowsWithoutDivider() {
        val t = MarkdownBlocks.parse("| A | B |\n|---|:--:|\n| 1 | `x` |\n| 2 | y |\nafter")
        val table = t[0] as MdBlock.Table
        assertEquals(listOf("A", "B"), table.header.map(::plain))
        assertEquals(2, table.rows.size)
        assertEquals(listOf("1", "x"), table.rows[0].map(::plain))
        assertEquals(true, table.rows[0][1][0].code)
        assertEquals("after", plain((t[1] as MdBlock.Paragraph).spans))
    }

    @Test fun ruleIsNotMistakenForListOrTable() {
        assertEquals(listOf<MdBlock>(MdBlock.Rule), MarkdownBlocks.parse("---"))
    }

    @Test fun fencedCodeIsKeptVerbatim() {
        val b = MarkdownBlocks.parse("```\n# not heading\n**x**\n```")
        assertEquals(listOf<MdBlock>(MdBlock.Code("# not heading\n**x**")), b)
    }

    @Test fun emptyAndWindowsLineEndings() {
        assertEquals(emptyList<MdBlock>(), MarkdownBlocks.parse(""))
        assertEquals(2, MarkdownBlocks.parse("# A\r\n\r\ntext").size)
    }
}
