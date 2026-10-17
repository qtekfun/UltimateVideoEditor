package com.qtekfun.ultimatevideoeditor.ui.editor.tray

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The numbered choice of several tray assets: the numbers follow the order of the taps. */
class TrayPickTest {
    @Test
    fun `tiles are numbered in the order they were tapped`() {
        val state = TrayState().withPicking(true).togglePicked("c").togglePicked("a").togglePicked("b")
        assertEquals(listOf("c", "a", "b"), state.picked)
        assertEquals(1, state.pickNumber("c"))
        assertEquals(3, state.pickNumber("b"))
        assertEquals(0, state.pickNumber("zzz"))
    }

    @Test
    fun `tapping a chosen tile unchooses it and the later ones renumber`() {
        val state = TrayState().withPicking(true).togglePicked("a").togglePicked("b").togglePicked("c").togglePicked("a")
        assertEquals(listOf("b", "c"), state.picked)
        assertEquals(1, state.pickNumber("b"))
        assertEquals(0, state.pickNumber("a"))
    }

    @Test
    fun `leaving the choice, or adding it, forgets it`() {
        val chosen = TrayState().withPicking(true).togglePicked("a").togglePicked("b")
        assertEquals(emptyList<String>(), chosen.withPicking(false).picked)
        assertFalse(chosen.afterAdding().picking)
        assertEquals(emptyList<String>(), chosen.afterAdding().picked)
    }

    @Test
    fun `the other tray settings survive`() {
        val state = TrayState(query = "beach", layout = TrayLayout.LIST).withPicking(true).togglePicked("a").afterAdding()
        assertEquals("beach", state.query)
        assertEquals(TrayLayout.LIST, state.layout)
    }
}
