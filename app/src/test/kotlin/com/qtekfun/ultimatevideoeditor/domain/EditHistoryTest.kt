package com.qtekfun.ultimatevideoeditor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EditHistoryTest {
    private val start = timeline(track("v1", clip("a", 0, 100), clip("b", 120, 50)))

    private fun EditHistory.run(command: EditCommand) = execute(command).getOrFail()

    @Test
    fun `undo and redo restore exact snapshots`() {
        val h0 = EditHistory(start)
        val h1 = h0.run(EditCommand.Split("v1", f(50), "a2"))
        val h2 = h1.run(EditCommand.RippleDelete("b"))

        assertEquals(start, h2.undo().undo().timeline)
        assertEquals(h1.timeline, h2.undo().timeline)
        assertEquals(h2.timeline, h2.undo().undo().redo().redo().timeline)
    }

    @Test
    fun `undo and redo at the ends are no-ops`() {
        val h = EditHistory(start)
        assertFalse(h.canUndo)
        assertFalse(h.canRedo)
        assertEquals(start, h.undo().timeline)
        assertEquals(start, h.redo().timeline)
    }

    @Test
    fun `a new edit clears the redo stack`() {
        val h = EditHistory(start).run(EditCommand.RippleDelete("a")).undo()
        assertTrue(h.canRedo)
        assertFalse(h.run(EditCommand.RippleDelete("b")).canRedo)
    }

    @Test
    fun `a failed edit leaves history untouched`() {
        val h = EditHistory(start).run(EditCommand.RippleDelete("a"))
        val result = h.execute(EditCommand.Move("zz", f(0)))
        assertEquals(EditError.ClipNotFound("zz"), result.errorOrFail())
        assertEquals(1, h.undoDepth)
    }

    @Test
    fun `history is bounded and drops the oldest edits`() {
        var h = EditHistory(start, limit = 2)
        h = h.run(EditCommand.Move("b", f(200)))
        h = h.run(EditCommand.Move("b", f(300)))
        h = h.run(EditCommand.Move("b", f(400)))
        assertEquals(2, h.undoDepth)
        val oldest = h.undo().undo()
        assertFalse(oldest.canUndo)
        assertEquals(f(200), oldest.timeline.track("v1")!!.clip("b")!!.timelineStart)
    }

    @Test
    fun `every command type can be executed through history`() {
        var h = EditHistory(start)
        h = h.run(EditCommand.Split("v1", f(40), "a2"))
        h = h.run(EditCommand.Move("b", f(200), snap = Snap(null, 3)))
        h = h.run(EditCommand.Trim("b", TrimEdge.END, f(230)))
        h = h.run(EditCommand.Overwrite("v1", clip("n", 10, 10)))
        h = h.run(EditCommand.RippleAppend("b"))
        assertTrue(h.timeline.invariantViolations().isEmpty())
        assertEquals(start, generateSequence(h) { it.undo() }.take(10).last().timeline)
    }

    @Test
    fun `limit must be positive`() {
        assertThrows(IllegalArgumentException::class.java) { EditHistory(start, limit = 0) }
    }
}
