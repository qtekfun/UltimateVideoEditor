package com.ultimatevideo.uveditor.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrubGateTest {
    private val gate = ScrubGate(slopPx = 16f)

    /** Feeds [steps] of (distanceX, distanceY) like the platform's scroll callbacks and returns the step that fired, or -1. */
    private fun feed(vararg steps: Pair<Float, Float>): Int {
        var firedAt = -1
        steps.forEachIndexed { i, (dx, dy) ->
            if (gate.onScroll(dx, dy)) {
                assertEquals("fires at most once", -1, firedAt)
                firedAt = i
            }
        }
        return firedAt
    }

    @Test
    fun `a horizontal swipe fires once, on the step that passes the slop`() {
        gate.begin()
        assertEquals(2, feed(6f to 0f, 6f to 1f, 6f to 0f, 30f to 0f, 30f to 2f))
    }

    @Test
    fun `a swipe to the left counts the same as one to the right`() {
        gate.begin()
        assertEquals(0, feed(-40f to 3f))
    }

    @Test
    fun `a first step that is already past the slop fires at once`() {
        gate.begin()
        assertEquals(0, feed(25f to 4f, 10f to 0f))
    }

    @Test
    fun `movement below the slop never fires`() {
        gate.begin()
        assertEquals(-1, feed(5f to 0f, 5f to 0f, -4f to 0f, 5f to 0f))
    }

    @Test
    fun `a mostly vertical drag only scrolls the lanes and does not fire`() {
        gate.begin()
        assertEquals(-1, feed(4f to 40f, 5f to 40f, 4f to 40f))
    }

    @Test
    fun `a drag that starts vertical and turns horizontal fires once the time axis wins`() {
        gate.begin()
        assertEquals(2, feed(0f to 30f, 10f to 0f, 40f to 0f))
    }

    @Test
    fun `a new gesture is judged on its own`() {
        gate.begin()
        assertEquals(0, feed(30f to 0f))
        assertEquals(-1, feed(30f to 0f))
        gate.begin()
        assertEquals(0, feed(30f to 0f))
    }

    @Test
    fun `a horizontal fling fires when the scroll did not, and only once`() {
        gate.begin()
        assertTrue(gate.onFling(2000f, 100f))
        assertFalse(gate.onFling(2000f, 100f))
        assertFalse(gate.onScroll(40f, 0f))
    }

    @Test
    fun `a fling after a recognised scrub does not fire again`() {
        gate.begin()
        assertTrue(gate.onScroll(40f, 0f))
        assertFalse(gate.onFling(2000f, 0f))
    }

    @Test
    fun `a vertical fling does not fire`() {
        gate.begin()
        assertFalse(gate.onFling(100f, 2500f))
    }
}
