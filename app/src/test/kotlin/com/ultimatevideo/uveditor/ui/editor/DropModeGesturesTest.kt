package com.ultimatevideo.uveditor.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DropModeGesturesTest {
    private fun gate() = SecondFingerTap(slopPx = 16f, timeoutMs = 350)

    @Test
    fun `a quick still second finger is a tap`() {
        val g = gate()
        g.onPointerDown(1, 300f, 400f, 1_000)
        g.onMove(1, 303f, 402f)
        assertTrue(g.onPointerUp(1, 1_120))
    }

    @Test
    fun `a second finger that moves past the slop is not a tap`() {
        val g = gate()
        g.onPointerDown(1, 300f, 400f, 1_000)
        g.onMove(1, 330f, 400f)
        assertFalse(g.onPointerUp(1, 1_100))
    }

    @Test
    fun `a second finger held too long is not a tap`() {
        val g = gate()
        g.onPointerDown(1, 300f, 400f, 1_000)
        assertFalse(g.onPointerUp(1, 1_351))
        g.onPointerDown(1, 300f, 400f, 2_000)
        assertTrue(g.onPointerUp(1, 2_350))
    }

    @Test
    fun `a third finger spoils the tap and the gate is clean afterwards`() {
        val g = gate()
        g.onPointerDown(1, 300f, 400f, 1_000)
        g.onPointerDown(2, 500f, 400f, 1_050)
        assertFalse(g.onPointerUp(2, 1_100))
        assertFalse(g.onPointerUp(1, 1_120))
        g.onPointerDown(1, 300f, 400f, 2_000)
        assertTrue(g.onPointerUp(1, 2_100))
    }

    @Test
    fun `unknown fingers never complete a tap and a reset forgets a press`() {
        val g = gate()
        assertFalse(g.onPointerUp(0, 10))
        g.onPointerDown(1, 0f, 0f, 0)
        g.reset()
        assertFalse(g.onPointerUp(1, 10))
    }

    @Test
    fun `the reach is the frames a fingertip covers, at least one and bounded`() {
        assertEquals(30L, DropReach.frames(1_000, 1_030))
        assertEquals(30L, DropReach.frames(1_030, 1_000))
        assertEquals(1L, DropReach.frames(500, 500))
        assertEquals(DropReach.MAX_FRAMES, DropReach.frames(0, 5_000_000))
    }
}
