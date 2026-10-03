package com.ultimatevideo.uveditor.engine.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DecoderPlannerTest {

    @Test
    fun `everything fits and nothing needs closing`() {
        val plan = DecoderPlanner.plan(maxOpen = 4, neededTopFirst = listOf(3, 2), openOldestFirst = listOf(2))

        assertEquals(listOf(3, 2), plan.render)
        assertEquals(emptyList<Int>(), plan.skipped)
        assertEquals(emptyList<Int>(), plan.toClose)
    }

    @Test
    fun `top layers win when there are more layers than decoders`() {
        val plan = DecoderPlanner.plan(maxOpen = 2, neededTopFirst = listOf(5, 4, 3, 2), openOldestFirst = emptyList())

        assertEquals(listOf(5, 4), plan.render)
        assertEquals(listOf(3, 2), plan.skipped)
    }

    @Test
    fun `least recently used unneeded assets are closed to make room`() {
        val plan = DecoderPlanner.plan(maxOpen = 3, neededTopFirst = listOf(9, 8), openOldestFirst = listOf(1, 2, 3))

        // Two new assets need two free slots; only one is free, so one old asset goes: the oldest.
        assertEquals(listOf(9, 8), plan.render)
        assertEquals(listOf(1), plan.toClose)
    }

    @Test
    fun `assets still needed are never closed`() {
        val plan = DecoderPlanner.plan(maxOpen = 2, neededTopFirst = listOf(1, 7), openOldestFirst = listOf(1, 2))

        assertEquals(listOf(2), plan.toClose)
    }

    @Test
    fun `an asset used by several layers counts once`() {
        val plan = DecoderPlanner.plan(maxOpen = 1, neededTopFirst = listOf(4, 4, 4), openOldestFirst = listOf(4))

        assertEquals(listOf(4), plan.render)
        assertEquals(emptyList<Int>(), plan.skipped)
        assertEquals(emptyList<Int>(), plan.toClose)
    }

    @Test
    fun `an empty scene keeps decoders open for fast returns`() {
        val plan = DecoderPlanner.plan(maxOpen = 2, neededTopFirst = emptyList(), openOldestFirst = listOf(1, 2))

        assertEquals(emptyList<Int>(), plan.render)
        assertEquals(emptyList<Int>(), plan.toClose)
    }

    @Test
    fun `the plan never needs more than maxOpen decoders`() {
        for (max in 1..4) {
            for (needed in 0..6) {
                for (open in 0..max) {
                    val plan = DecoderPlanner.plan(max, (100 until 100 + needed).toList(), (0 until open).toList())
                    val after = (0 until open).toSet() - plan.toClose.toSet() + plan.render.toSet()
                    assertEquals("max=$max needed=$needed open=$open", true, after.size <= max)
                }
            }
        }
    }

    @Test
    fun `at least one decoder is required`() {
        assertThrows(IllegalArgumentException::class.java) { DecoderPlanner.plan(0, listOf(1), emptyList()) }
    }
}
