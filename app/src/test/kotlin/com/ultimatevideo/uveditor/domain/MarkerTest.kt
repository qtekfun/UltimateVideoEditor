package com.ultimatevideo.uveditor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkerTest {
    private val base = timeline(track("v1", clip("a", 0, 100)))

    private fun marker(id: String, frame: Long, kind: MarkerKind = MarkerKind.MANUAL) = Marker(id, f(frame), kind)

    @Test
    fun `markers stay sorted by frame`() {
        var t = base
        t = EditCommand_apply(AddMarker(marker("m2", 50)), t)
        t = EditCommand_apply(AddMarker(marker("m1", 10)), t)
        t = EditCommand_apply(AddMarker(marker("m3", 80)), t)
        assertEquals(listOf("m1", "m2", "m3"), t.markers.map { it.id })
        assertTrue(t.invariantViolations().isEmpty())
    }

    @Test
    fun `a marker cannot repeat an id or a frame or sit before zero`() {
        val t = EditCommand_apply(AddMarker(marker("m1", 10)), base)
        assertTrue(AddMarker(marker("m1", 20)).apply(t).errorOrFail() is EditError.InvalidMarker)
        assertTrue(AddMarker(marker("m2", 10)).apply(t).errorOrFail() is EditError.InvalidMarker)
        assertTrue(AddMarker(marker("m3", -1)).apply(t).errorOrFail() is EditError.InvalidMarker)
    }

    @Test
    fun `remove deletes the marker and reports an unknown id`() {
        val t = EditCommand_apply(AddMarker(marker("m1", 10)), base)
        assertEquals(emptyList<Marker>(), RemoveMarker("m1").apply(t).getOrFail().markers)
        assertEquals(EditError.MarkerNotFound("nope"), RemoveMarker("nope").apply(t).errorOrFail())
    }

    @Test
    fun `setting beats replaces only beats and keeps manual markers`() {
        var t = EditCommand_apply(AddMarker(marker("manual", 30)), base)
        t = EditCommand_apply(SetBeatMarkers(listOf(marker("b1", 10), marker("b2", 30), marker("b3", 50))), t)
        // The beat on the manual marker's frame is dropped.
        assertEquals(listOf("b1" to 10L, "manual" to 30L, "b3" to 50L), t.markers.map { it.id to it.frame.value })
        assertEquals(MarkerKind.BEAT, t.markers.first { it.id == "b1" }.kind)
        t = EditCommand_apply(SetBeatMarkers(listOf(marker("c1", 20))), t)
        assertEquals(listOf("c1" to 20L, "manual" to 30L), t.markers.map { it.id to it.frame.value })
        t = EditCommand_apply(SetBeatMarkers(emptyList()), t)
        assertEquals(listOf("manual"), t.markers.map { it.id })
    }

    @Test
    fun `setting beats inside a range leaves other beats alone`() {
        var t = EditCommand_apply(SetBeatMarkers(listOf(marker("a1", 10), marker("a2", 20), marker("b1", 110), marker("b2", 120))), base)
        t = EditCommand_apply(SetBeatMarkers(listOf(marker("n1", 115)), from = f(100), until = f(200)), t)
        assertEquals(listOf("a1", "a2", "n1"), t.markers.map { it.id })
    }

    @Test
    fun `beat ids must not collide with surviving markers`() {
        val t = EditCommand_apply(AddMarker(marker("x", 5)), base)
        assertTrue(SetBeatMarkers(listOf(marker("x", 9))).apply(t).errorOrFail() is EditError.InvalidMarker)
    }

    @Test
    fun `markers do not move when clips are edited`() {
        var t = EditCommand_apply(AddMarker(marker("m", 70)), timeline(track("v1", clip("a", 0, 100), clip("b", 100, 50))))
        t = EditCommand.DeleteClip("a").apply(t).getOrFail()
        assertEquals(listOf(70L), t.markers.map { it.frame.value })
    }

    @Test
    fun `undo restores the markers exactly`() {
        var history = EditHistory(base)
        history = (history.execute(AddMarker(marker("m", 10))) as EditResult.Success).value
        history = (history.execute(SetBeatMarkers(listOf(marker("b", 20)))) as EditResult.Success).value
        assertEquals(2, history.timeline.markers.size)
        history = history.undo()
        assertEquals(listOf("m"), history.timeline.markers.map { it.id })
        history = history.undo()
        assertEquals(emptyList<Marker>(), history.timeline.markers)
    }

    @Test
    fun `nearest picks the closest marker within the radius and the earlier on a tie`() {
        val markers = listOf(marker("a", 10), marker("b", 20), marker("c", 40))
        assertEquals("a", MarkerOps.nearest(markers, f(15), 5)?.id)
        assertEquals("b", MarkerOps.nearest(markers, f(21), 5)?.id)
        assertEquals(null, MarkerOps.nearest(markers, f(30), 5))
    }

    @Test
    fun `marker snapping pulls a moved clip to the marker`() {
        val t = timeline(track("v2", clip("x", 200, 20)), track("v1", clip("a", 0, 400)))
        val snap = Snap(playhead = null, thresholdFrames = 8, extraTargets = listOf(f(103)))
        // Moving x to 100 puts its start 3 frames from the marker: it snaps.
        val moved = TimelineOps.move(t, "x", f(100), null, snap).getOrFail()
        assertEquals(103L, moved.track("v2")!!.clips.single().timelineStart.value)
        // Without the target it stays where it was dropped.
        val plain = TimelineOps.move(t, "x", f(100), null, Snap(null, 8)).getOrFail()
        assertEquals(100L, plain.track("v2")!!.clips.single().timelineStart.value)
    }

    @Suppress("FunctionName")
    private fun EditCommand_apply(command: EditCommand, timeline: Timeline): Timeline = command.apply(timeline).getOrFail()
}
