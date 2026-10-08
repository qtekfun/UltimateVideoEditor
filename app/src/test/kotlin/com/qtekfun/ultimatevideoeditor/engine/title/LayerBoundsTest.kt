package com.qtekfun.ultimatevideoeditor.engine.title

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LayerBoundsTest {
    @Test
    fun `an empty title still has a valid one pixel bitmap`() {
        assertEquals(1 to 1, LayerBounds.halfExtents(emptyList(), 100, 100))
    }

    @Test
    fun `a centred rectangle needs half its size on each side`() {
        val half = LayerBounds.halfExtents(listOf(LayerFootprint(0.0, 0.0, 400.0, 100.0)), 1000, 1000)
        assertEquals(200 to 50, half)
    }

    @Test
    fun `an off-centre layer pushes the symmetric bitmap out to its far edge`() {
        // Centre 300 px left of the canvas centre and 40 px wide: its far edge is 320 px from the centre.
        val half = LayerBounds.halfExtents(listOf(LayerFootprint(-300.0, 0.0, 40.0, 40.0)), 1000, 1000)
        assertEquals(320 to 20, half)
    }

    @Test
    fun `a lower third stays a thin strip`() {
        // A bar 0.52 of 1920 wide and 0.11 of 1080 tall, centred at (-0.22, 0.30) of the canvas.
        val bar = LayerFootprint(-0.22 * 1920, 0.30 * 1080, 0.52 * 1920, 0.11 * 1080)
        val (hx, hy) = LayerBounds.halfExtents(listOf(bar), 1152, 648)
        assertEquals(Math.ceil(0.22 * 1920 + 0.26 * 1920).toInt(), hx)
        assertEquals(Math.ceil(0.30 * 1080 + 0.055 * 1080).toInt(), hy)
        // Far smaller than the canvas it sits on.
        assert(2 * hy < 1080)
    }

    @Test
    fun `shadows and outlines add their reach on every side`() {
        val plain = LayerBounds.halfExtents(listOf(LayerFootprint(0.0, 0.0, 100.0, 100.0)), 1000, 1000)
        val shadowed = LayerBounds.halfExtents(listOf(LayerFootprint(0.0, 0.0, 100.0, 100.0, reach = 12.0)), 1000, 1000)
        assertEquals(plain.first + 12, shadowed.first)
        assertEquals(plain.second + 12, shadowed.second)
    }

    @Test
    fun `rotation grows the box of a rectangle`() {
        val (hx, hy) = LayerBounds.rotatedHalfExtent(200.0, 100.0, 90.0)
        assertEquals(50.0, hx, 1e-9)
        assertEquals(100.0, hy, 1e-9)
        val (dx, dy) = LayerBounds.rotatedHalfExtent(200.0, 100.0, 45.0)
        assertEquals(dx, dy, 1e-9)
        assertEquals((200.0 + 100.0) / 2 * Math.sqrt(0.5), dx, 1e-9)
        // Turning by a full circle changes nothing.
        val (fx, fy) = LayerBounds.rotatedHalfExtent(200.0, 100.0, 360.0)
        assertEquals(100.0, fx, 1e-9)
        assertEquals(50.0, fy, 1e-9)
    }

    @Test
    fun `layers pushed far off the canvas are cut at the cap`() {
        val half = LayerBounds.halfExtents(listOf(LayerFootprint(5000.0, -4000.0, 100.0, 100.0)), 600, 400)
        assertEquals(600 to 400, half)
    }

    @Test
    fun `the widest layer decides and caps must be positive`() {
        val half = LayerBounds.halfExtents(
            listOf(LayerFootprint(0.0, 0.0, 100.0, 100.0), LayerFootprint(0.0, 0.0, 300.0, 20.0), LayerFootprint(10.0, 10.0, 10.0, 10.0)),
            1000,
            1000,
        )
        assertEquals(150 to 50, half)
        assertThrows(IllegalArgumentException::class.java) { LayerBounds.halfExtents(emptyList(), 0, 10) }
    }
}
