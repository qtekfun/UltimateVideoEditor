package com.ultimatevideo.uveditor.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeScrollTest {

    private val width = 1000f
    private val density = 1f

    @Test
    fun `no scrolling away from the edges`() {
        assertEquals(0f, edgeScrollSpeed(500f, width, density), 0f)
        assertEquals(0f, edgeScrollSpeed(60f, width, density), 0f)
        assertEquals(0f, edgeScrollSpeed(940f, width, density), 0f)
    }

    @Test
    fun `scrolls back in time near the left edge and forward near the right`() {
        assertTrue(edgeScrollSpeed(20f, width, density) < 0f)
        assertTrue(edgeScrollSpeed(980f, width, density) > 0f)
    }

    @Test
    fun `speed grows towards the edge and is capped`() {
        val near = edgeScrollSpeed(960f, width, density)
        val atEdge = edgeScrollSpeed(1000f, width, density)
        val beyond = edgeScrollSpeed(1200f, width, density)

        assertTrue(near < atEdge)
        assertEquals(atEdge, beyond, 0f)
        assertEquals(14f, atEdge, 0.001f)
        assertEquals(-14f, edgeScrollSpeed(0f, width, density), 0.001f)
    }

    @Test
    fun `speed scales with density`() {
        assertEquals(14f * 3f, edgeScrollSpeed(3000f, 3000f, 3f), 0.001f)
    }

    @Test
    fun `a view too narrow for two zones never scrolls`() {
        assertEquals(0f, edgeScrollSpeed(10f, 100f, density), 0f)
    }
}
