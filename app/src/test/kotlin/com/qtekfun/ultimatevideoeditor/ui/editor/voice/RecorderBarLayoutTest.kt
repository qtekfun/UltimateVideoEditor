package com.qtekfun.ultimatevideoeditor.ui.editor.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecorderBarLayoutTest {
    /** The bar's width inside its 12 dp padding on a window [windowDp] wide. */
    private fun room(windowDp: Float) = windowDp - 24f

    @Test
    fun `a normal phone at normal font size has one row`() {
        assertTrue(RecorderBarLayout.isWide(room(411f), 1f))
        assertTrue(RecorderBarLayout.isWide(room(360f), 1f))
    }

    @Test
    fun `a 320 dp window or a large font puts the actions on a row of their own`() {
        assertFalse(RecorderBarLayout.isWide(room(320f), 1f))
        assertFalse(RecorderBarLayout.isWide(room(411f), 2f))
        assertFalse(RecorderBarLayout.isWide(room(360f), 1.3f))
    }

    @Test
    fun `a wider window or a bigger tablet stays on one row even with a larger font`() {
        assertTrue(RecorderBarLayout.isWide(room(840f), 2f))
        assertTrue(RecorderBarLayout.isWide(room(1280f), 3f))
    }

    @Test
    fun `the room needed never shrinks as the font grows`() {
        val scales = listOf(0.85f, 1f, 1.15f, 1.3f, 1.5f, 2f, 3f, 4f)
        val needs = scales.map(RecorderBarLayout::wideNeededDp)
        assertEquals(needs.sorted(), needs)
        assertEquals(RecorderBarLayout.wideNeededDp(1f), RecorderBarLayout.wideNeededDp(0.85f), 0f)
    }

    @Test
    fun `an action keeps a 48 dp touch target`() {
        assertTrue(RecorderBarLayout.ACTION_DP >= 48f)
    }

    @Test
    fun `the history keeps the newest levels and no more than the slots`() {
        var history = emptyList<Float>()
        for (i in 1..LevelHistory.SLOTS + 10) history = LevelHistory.push(history, i / 100f)
        assertEquals(LevelHistory.SLOTS, history.size)
        assertEquals((LevelHistory.SLOTS + 10) / 100f, history.last(), 0f)
        assertEquals(11 / 100f, history.first(), 0f)
    }

    @Test
    fun `levels outside 0 to 1 and not-a-number are clamped`() {
        val history = LevelHistory.push(LevelHistory.push(LevelHistory.push(emptyList(), 3f), -1f), Float.NaN)
        assertEquals(listOf(1f, 0f, 0f), history)
    }

    @Test
    fun `quiet speech fills visibly more of the meter than its raw level`() {
        assertEquals(0f, LevelHistory.fill(0f), 0f)
        assertEquals(1f, LevelHistory.fill(1f), 0f)
        assertTrue(LevelHistory.fill(0.04f) >= 0.19f)
        assertTrue(LevelHistory.fill(0.25f) > 0.25f)
    }
}
