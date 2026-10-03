package com.ultimatevideo.uveditor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackOpsTest {

    private val base = timeline(track("v1", clip("c1", 0, 100)), track("a1", type = TrackType.AUDIO))

    @Test
    fun `a track is inserted at the requested index`() {
        val result = TimelineOps.addTrack(base, track("v2"), index = 0).getOrFail()

        assertEquals(listOf("v2", "v1", "a1"), result.tracks.map { it.id })
        assertTrue(result.invariantViolations().isEmpty())
    }

    @Test
    fun `the index is clamped to the valid range`() {
        assertEquals(listOf("v1", "a1", "a2"), TimelineOps.addTrack(base, track("a2", type = TrackType.AUDIO), index = 99).getOrFail().tracks.map { it.id })
        assertEquals(listOf("v0", "v1", "a1"), TimelineOps.addTrack(base, track("v0"), index = -5).getOrFail().tracks.map { it.id })
    }

    @Test
    fun `a duplicate track id and a track with clips are rejected`() {
        assertEquals(EditError.DuplicateTrackId("v1"), TimelineOps.addTrack(base, track("v1"), 0).errorOrFail())
        assertTrue(TimelineOps.addTrack(base, track("v2", clip("x", 0, 10)), 0).errorOrFail() is EditError.InvalidClip)
    }

    @Test
    fun `an empty track can be removed`() {
        val result = TimelineOps.removeTrack(base, "a1").getOrFail()

        assertEquals(listOf("v1"), result.tracks.map { it.id })
    }

    @Test
    fun `a track with clips cannot be removed and an unknown one is reported`() {
        assertEquals(EditError.TrackNotEmpty("v1"), TimelineOps.removeTrack(base, "v1").errorOrFail())
        assertEquals(EditError.TrackNotFound("nope"), TimelineOps.removeTrack(base, "nope").errorOrFail())
    }

    @Test
    fun `adding and removing a track are undoable`() {
        var history = EditHistory(base)

        history = history.execute(EditCommand.AddTrack(track("v2"), 0)).getOrFail()
        history = history.execute(EditCommand.RemoveTrack("a1")).getOrFail()
        assertEquals(listOf("v2", "v1"), history.timeline.tracks.map { it.id })

        history = history.undo().undo()
        assertEquals(base, history.timeline)
    }

    @Test
    fun `clips can move between two tracks of the same type`() {
        val two = timeline(track("v2"), track("v1", clip("c1", 0, 100)))

        val moved = TimelineOps.move(two, "c1", f(20), toTrackId = "v2").getOrFail()

        assertLayout(moved, "v2", at("c1", 20, 120))
        assertLayout(moved, "v1")
    }
}
