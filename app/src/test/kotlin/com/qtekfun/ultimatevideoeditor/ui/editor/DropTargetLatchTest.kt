package com.qtekfun.ultimatevideoeditor.ui.editor

import com.qtekfun.ultimatevideoeditor.domain.DropTarget
import org.junit.Assert.assertEquals
import org.junit.Test

class DropTargetLatchTest {
    private val committed = setOf("v2", "v1", "a1")
    private val before = listOf("v2", "v1", "a1")
    private val after = listOf("track-v-new", "v2", "v1", "a1")

    private fun step(zone: DragZone, index: Int, shown: List<String>, prev: DropTarget?, latch: NewLaneLatch, canOpen: Boolean = true) =
        resolveDropTarget(zone, index, shown, committed, prev, latch, canOpen)

    @Test
    fun `the target stays open when the row it adds shifts the finger onto an existing lane`() {
        val open = step(DragZone.ABOVE_LANES, -1, before, null, NewLaneLatch())
        assertEquals(DropTarget.AboveLanes, open.target)
        // Same finger position, layout one row taller: it now hits v2 (index 1 of the shown lanes).
        val shifted = step(DragZone.LANES, 1, after, open.target, open.latch)
        assertEquals(DropTarget.AboveLanes, shifted.target)
        // Jitter between the room above, the provisional lane and that lane keeps it open.
        var r = shifted
        repeat(3) {
            r = step(DragZone.LANES, 0, after, r.target, r.latch)
            assertEquals(DropTarget.AboveLanes, r.target)
            r = step(DragZone.ABOVE_LANES, -1, after, r.target, r.latch)
            assertEquals(DropTarget.AboveLanes, r.target)
            r = step(DragZone.LANES, 1, after, r.target, r.latch)
            assertEquals(DropTarget.AboveLanes, r.target)
        }
    }

    @Test
    fun `moving to another lane or off the panel closes it`() {
        val open = step(DragZone.ABOVE_LANES, -1, before, null, NewLaneLatch())
        val shifted = step(DragZone.LANES, 1, after, open.target, open.latch)
        assertEquals(DropTarget.Lane("v1"), step(DragZone.LANES, 2, after, shifted.target, shifted.latch).target)
        assertEquals(DropTarget.Outside, step(DragZone.OUTSIDE, -1, after, shifted.target, shifted.latch).target)
    }

    @Test
    fun `without an open target a lane hit is just that lane`() {
        val r = step(DragZone.LANES, 1, before, null, NewLaneLatch())
        assertEquals(DropTarget.Lane("v1"), r.target)
    }

    @Test
    fun `a drag that cannot make a lane never latches`() {
        val above = step(DragZone.ABOVE_LANES, -1, before, null, NewLaneLatch(), canOpen = false)
        val next = step(DragZone.LANES, 0, before, above.target, above.latch, canOpen = false)
        assertEquals(DropTarget.Lane("v2"), next.target)
    }

    @Test
    fun `a gap between lanes keeps the previous target`() {
        val r = step(DragZone.LANES, 9, before, DropTarget.Lane("v1"), NewLaneLatch())
        assertEquals(DropTarget.Lane("v1"), r.target)
    }
}
