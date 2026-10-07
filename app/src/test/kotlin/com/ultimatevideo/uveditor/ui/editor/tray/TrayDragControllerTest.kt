package com.ultimatevideo.uveditor.ui.editor.tray

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The drag below the gesture, driven by the root observer's pointer samples: whatever happens, the ghost ends. */
class TrayDragControllerTest {
    private class FakeSink(var dropResult: Boolean = true, var failOn: String? = null) : TrayDragSink {
        val calls = mutableListOf<String>()
        override fun begin(assetId: String) { calls += "begin"; check(failOn != "begin") }
        override fun move(x: Float, y: Float) { calls += "move"; check(failOn != "move") { "boom" } }
        override fun drop(x: Float, y: Float): Boolean { calls += "drop"; check(failOn != "drop"); return dropResult }
        override fun cancel() { calls += "cancel" }
        override fun frame(nowMs: Long) = Unit
    }

    private fun carrying(sink: FakeSink): TrayDragController {
        val c = TrayDragController()
        c.sink = sink
        c.logger = { _, _ -> }
        c.pickUp(TrayCarry("a", "a", "", AssetKind.VIDEO, null, Offset(1f, 1f), Offset(5f, 5f)), 3L)
        return c
    }

    private fun pointer(pressed: Boolean, id: Long = 3L, newDown: Boolean = false) = listOf(TrayPointer(id, 10f, 20f, pressed, newDown))

    @Test
    fun `events are ignored and not consumed when nothing is carried`() {
        assertFalse(TrayDragController().onPointerEvent(pointer(true)))
    }

    @Test
    fun `moves follow the finger and are consumed`() {
        val sink = FakeSink()
        val c = carrying(sink)
        assertTrue(c.onPointerEvent(pointer(true)))
        assertEquals(Offset(10f, 20f), c.carry?.position)
        assertEquals(listOf("begin", "move"), sink.calls)
    }

    @Test
    fun `the lift over the timeline drops and clears the ghost at once`() {
        val sink = FakeSink(dropResult = true)
        val c = carrying(sink)
        c.onPointerEvent(pointer(false))
        assertNull(c.carry)
        assertEquals("drop", sink.calls.last())
    }

    @Test
    fun `the lift elsewhere flies back and the ghost ends`() {
        val sink = FakeSink(dropResult = false)
        val c = carrying(sink)
        c.onPointerEvent(pointer(false))
        assertTrue(c.carry?.returning == true)
        assertFalse(c.isCarrying)
        c.finishReturn()
        assertNull(c.carry)
    }

    @Test
    fun `a lost finger cancels`() {
        val sink = FakeSink()
        val c = carrying(sink)
        assertTrue(c.onPointerEvent(emptyList()))
        assertEquals("cancel", sink.calls.last())
        assertTrue(c.carry?.returning == true)
    }

    @Test
    fun `a second finger cancels, and its lift later does nothing`() {
        val sink = FakeSink()
        val c = carrying(sink)
        c.onPointerEvent(pointer(true, id = 9L, newDown = true))
        assertTrue(c.carry?.returning == true)
        assertEquals(1, sink.calls.count { it == "cancel" })
        assertFalse(c.onPointerEvent(pointer(false)))
    }

    @Test
    fun `a failure in the editor ends the drag cleanly and is logged`() {
        val sink = FakeSink(failOn = "move")
        val c = carrying(sink)
        var logged: Throwable? = null
        c.logger = { _, e -> logged = e }
        c.onPointerEvent(pointer(true))
        assertNull(c.carry)
        assertNotNull(logged)
        assertEquals("cancel", sink.calls.last())
    }

    @Test
    fun `a failing drop does not leave the ghost either`() {
        val sink = FakeSink(failOn = "drop")
        val c = carrying(sink)
        c.logger = { _, _ -> }
        c.onPointerEvent(pointer(false))
        assertNull(c.carry)
    }

    @Test
    fun `reset (the editor leaves composition) ends a carried drag and tells the editor`() {
        val sink = FakeSink()
        val c = carrying(sink)
        c.reset()
        assertNull(c.carry)
        assertEquals("cancel", sink.calls.last())
    }

    @Test
    fun `a ghost flying back whose animation never reports is cleared by the next event after the watchdog`() {
        val c = carrying(FakeSink())
        c.cancel()
        c.finishStaleReturn()
        assertNotNull(c.carry)
        Thread.sleep(450)
        c.finishStaleReturn()
        assertNull(c.carry)
    }
}
