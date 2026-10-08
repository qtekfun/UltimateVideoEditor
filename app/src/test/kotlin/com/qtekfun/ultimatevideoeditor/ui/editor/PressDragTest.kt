package com.qtekfun.ultimatevideoeditor.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PressDragTest {
    private val slop = 8f

    @Test
    fun holdThenMoveOnAnUnselectedClipStartsTheDrag() {
        val gate = PressDrag(slop)
        gate.onLongPress(100f, 100f, alreadySelected = false)
        assertEquals(PressDragStep.NONE, gate.onMove(104f, 100f))
        assertEquals(PressDragStep.START_DRAG, gate.onMove(120f, 100f))
        assertEquals("the drag starts once", PressDragStep.NONE, gate.onMove(200f, 100f))
        assertEquals("a drag never toggles the selection on release", PressDragStep.NONE, gate.onEnd(cancelled = false))
    }

    @Test
    fun holdThenMoveOnASelectedClipDragsItAndKeepsItSelected() {
        val gate = PressDrag(slop)
        gate.onLongPress(100f, 100f, alreadySelected = true)
        assertEquals(PressDragStep.START_DRAG, gate.onMove(100f, 130f))
        assertEquals(PressDragStep.NONE, gate.onEnd(cancelled = false))
    }

    @Test
    fun releasingAHeldSelectedClipWithoutMovingTogglesItOff() {
        val gate = PressDrag(slop)
        gate.onLongPress(100f, 100f, alreadySelected = true)
        gate.onMove(102f, 101f)
        assertEquals(PressDragStep.TOGGLE_SELECTION, gate.onEnd(cancelled = false))
    }

    @Test
    fun releasingAHeldUnselectedClipLeavesTheSelectionItAlreadyMade() {
        val gate = PressDrag(slop)
        gate.onLongPress(100f, 100f, alreadySelected = false)
        assertEquals(PressDragStep.NONE, gate.onEnd(cancelled = false))
    }

    @Test
    fun cancelledHoldDoesNotToggle() {
        val gate = PressDrag(slop)
        gate.onLongPress(100f, 100f, alreadySelected = true)
        assertEquals(PressDragStep.NONE, gate.onEnd(cancelled = true))
    }

    @Test
    fun nothingHappensWithoutALongPress() {
        val gate = PressDrag(slop)
        assertFalse(gate.isArmed)
        assertEquals(PressDragStep.NONE, gate.onMove(500f, 500f))
        assertEquals(PressDragStep.NONE, gate.onEnd(cancelled = false))
    }

    @Test
    fun theGateRearmsForTheNextPress() {
        val gate = PressDrag(slop)
        gate.onLongPress(0f, 0f, alreadySelected = false)
        assertTrue(gate.isArmed)
        gate.onMove(50f, 0f)
        gate.onEnd(cancelled = false)
        assertFalse(gate.isArmed)
        gate.onLongPress(10f, 10f, alreadySelected = false)
        assertEquals(PressDragStep.START_DRAG, gate.onMove(40f, 10f))
    }
}
