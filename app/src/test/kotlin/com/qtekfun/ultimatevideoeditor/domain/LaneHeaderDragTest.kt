package com.qtekfun.ultimatevideoeditor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LaneHeaderDragTest {
    // Display order, top first: three overlays, the base, two audio lanes and a title lane.
    private fun scene() = timeline(
        track("v4", clip("a", 0, 10)),
        track("v3", clip("b", 0, 10)),
        track("v2", clip("c", 0, 10)),
        track("v1", clip("base", 0, 100)),
        track("a1", clip("m", 0, 10), type = TrackType.AUDIO),
        track("a2", type = TrackType.AUDIO),
        track("t1", type = TrackType.TITLE),
    )

    private fun order(t: Timeline) = t.tracks.map { it.id }

    @Test
    fun `lane group holds the lanes of its kind without the base`() {
        val t = scene()
        assertEquals(listOf(0, 1, 2), LaneOps.laneGroup(t, "v3"))
        assertEquals(listOf(4, 5), LaneOps.laneGroup(t, "a1"))
        assertEquals(listOf(6), LaneOps.laneGroup(t, "t1"))
        assertEquals(emptyList<Int>(), LaneOps.laneGroup(t, "v1"))
        assertEquals(emptyList<Int>(), LaneOps.laneGroup(t, "nope"))
    }

    @Test
    fun `drop target is the nearest lane of the same kind`() {
        val t = scene()
        assertEquals(0, LaneOps.laneDropTarget(t, "v2", -1))
        assertEquals(0, LaneOps.laneDropTarget(t, "v2", 0))
        assertEquals(1, LaneOps.laneDropTarget(t, "v2", 1))
        // Over the base the nearest overlay lane wins (the base is never a target).
        assertEquals(2, LaneOps.laneDropTarget(t, "v4", 3))
        assertEquals(2, LaneOps.laneDropTarget(t, "v4", 6))
        // Audio lanes only ever land on audio lanes.
        assertEquals(4, LaneOps.laneDropTarget(t, "a2", 0))
        assertEquals(5, LaneOps.laneDropTarget(t, "a1", 6))
    }

    @Test
    fun `a lane that cannot move has no drop target`() {
        val t = scene()
        assertNull(LaneOps.laneDropTarget(t, "v1", 0))
        assertNull(LaneOps.laneDropTarget(t, "t1", 6))
        assertNull(LaneOps.laneDropTarget(t, "nope", 0))
    }

    @Test
    fun `moving a lane up shifts the lanes between by one`() {
        val moved = LaneOps.moveTrackTo(scene(), "v2", 0).getOrFail()
        assertEquals(listOf("v2", "v4", "v3", "v1", "a1", "a2", "t1"), order(moved))
    }

    @Test
    fun `moving a lane down shifts the lanes between by one`() {
        val moved = LaneOps.moveTrackTo(scene(), "v4", 2).getOrFail()
        assertEquals(listOf("v3", "v2", "v4", "v1", "a1", "a2", "t1"), order(moved))
    }

    @Test
    fun `audio lanes reorder among themselves and leave the video stack alone`() {
        val moved = LaneOps.moveTrackTo(scene(), "a2", 4).getOrFail()
        assertEquals(listOf("v4", "v3", "v2", "v1", "a2", "a1", "t1"), order(moved))
    }

    @Test
    fun `the base never moves and is never a destination`() {
        val t = scene()
        assertTrue(LaneOps.moveTrackTo(t, "v1", 0).errorOrFail() is EditError.BaseTrackCannotMove)
        assertTrue(LaneOps.moveTrackTo(t, "v4", 3).errorOrFail() is EditError.TrackCannotMove)
        assertTrue(LaneOps.moveTrackTo(t, "v4", 4).errorOrFail() is EditError.TrackCannotMove)
    }

    @Test
    fun `moving to its own slot changes nothing and an unknown lane fails`() {
        val t = scene()
        assertEquals(t, LaneOps.moveTrackTo(t, "v3", 1).getOrFail())
        assertEquals(EditError.TrackNotFound("zz"), LaneOps.moveTrackTo(t, "zz", 0).errorOrFail())
    }

    @Test
    fun `the clips travel with their lanes and the timeline stays valid`() {
        val moved = LaneOps.moveTrackTo(scene(), "v2", 0).getOrFail()
        assertEquals("c", moved.track("v2")!!.clips.single().id)
        assertEquals(emptyList<String>(), moved.invariantViolations())
        assertEquals(setOf("a", "b", "c", "base", "m"), moved.tracks.flatMap { t -> t.clips.map { it.id } }.toSet())
    }

    @Test
    fun `one command is one undo step that restores the exact order`() {
        val start = scene()
        val moved = EditHistory(start).execute(EditCommand.MoveTrackTo("v2", 0)).getOrFail()
        assertEquals(listOf("v2", "v4", "v3", "v1", "a1", "a2", "t1"), order(moved.timeline))
        assertTrue(moved.canUndo)
        assertEquals(start, moved.undo().timeline)
        assertEquals(moved.timeline, moved.undo().redo().timeline)
    }

    @Test
    fun `every legal drag keeps the base last of the video lanes`() {
        val t = scene()
        for (id in listOf("v4", "v3", "v2")) {
            for (target in LaneOps.laneGroup(t, id)) {
                val moved = LaneOps.moveTrackTo(t, id, target).getOrFail()
                assertEquals("v1", ClipDeletion.baseTrack(moved)!!.id)
                assertEquals(3, moved.tracks.indexOfFirst { it.id == "v1" })
                assertEquals(emptyList<String>(), moved.invariantViolations())
            }
        }
    }
}
