package com.qtekfun.ultimatevideoeditor.ui.editor.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecorderLayoutTest {
    /** The panel's width inside its 12 dp padding on a window [windowDp] wide. */
    private fun room(windowDp: Float) = windowDp - 24f

    @Test
    fun `three buttons share a row on a normal phone at normal font size`() {
        assertEquals(3, RecorderLayout.columns(room(400f), 1f, 3))
        assertEquals(3, RecorderLayout.columns(room(360f), 1f, 3))
    }

    @Test
    fun `at 320 dp and normal font three buttons still fit one row`() {
        assertEquals(3, RecorderLayout.columns(room(320f), 1f, 3))
    }

    @Test
    fun `at 320 dp with font scale 2 the buttons wrap onto rows instead of clipping`() {
        val columns = RecorderLayout.columns(room(320f), 2f, 3)
        assertTrue("got $columns columns", columns in 1..2)
        val rows = RecorderLayout.rows(listOf("pause", "stop", "discard"), columns)
        assertEquals(listOf("pause", "stop", "discard"), rows.flatten())
        assertTrue(rows.size >= 2)
    }

    @Test
    fun `a huge font or a tiny window stacks one button per row`() {
        assertEquals(1, RecorderLayout.columns(room(320f), 3f, 3))
        assertEquals(1, RecorderLayout.columns(100f, 1f, 3))
        assertEquals(1, RecorderLayout.columns(0f, 1f, 3))
    }

    @Test
    fun `never more columns than buttons, and always at least one`() {
        assertEquals(1, RecorderLayout.columns(2000f, 1f, 1))
        assertEquals(2, RecorderLayout.columns(2000f, 1f, 2))
        assertEquals(1, RecorderLayout.columns(2000f, 1f, 0))
        // A font scale below 1 is treated as 1.
        assertEquals(RecorderLayout.columns(300f, 1f, 3), RecorderLayout.columns(300f, 0.5f, 3))
    }

    @Test
    fun `rows keep the order, fill from the left and never hold more than the columns`() {
        assertEquals(listOf(listOf(1, 2), listOf(3)), RecorderLayout.rows(listOf(1, 2, 3), 2))
        assertEquals(listOf(listOf(1), listOf(2), listOf(3)), RecorderLayout.rows(listOf(1, 2, 3), 1))
        assertEquals(listOf(listOf(1, 2, 3)), RecorderLayout.rows(listOf(1, 2, 3), 5))
        assertEquals(listOf(listOf(1), listOf(2)), RecorderLayout.rows(listOf(1, 2), 0))
        assertEquals(emptyList<List<Int>>(), RecorderLayout.rows(emptyList<Int>(), 3))
    }

    @Test
    fun `the touch target is at least 48 dp`() {
        assertTrue(RecorderLayout.MIN_TOUCH_DP >= 48f)
    }
}
