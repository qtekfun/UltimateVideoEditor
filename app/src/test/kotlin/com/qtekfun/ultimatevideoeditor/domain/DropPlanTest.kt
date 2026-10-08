package com.qtekfun.ultimatevideoeditor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DropPlanTest {
    private val snap = Snap(playhead = null, thresholdFrames = 8)

    // Display order: overlay on top, base last, audio below.
    private fun scene() = timeline(
        track("v2", clip("p", 0, 60), clip("q", 60, 40), clip("r", 150, 30)),
        track("v1", clip("a", 0, 100), clip("b", 100, 100)),
        track("a1", clip("m", 0, 100), clip("n", 100, 50), type = TrackType.AUDIO),
    ).let { t ->
        // x: the clip being dragged, on its own overlay lane, 30 frames long.
        t.copy(tracks = listOf(Track("v3", TrackType.VIDEO, listOf(clip("x", 300, 30)))) + t.tracks)
    }

    private fun decide(clipId: String, start: Long, target: DropTarget) =
        checkNotNull(DropPlan.decide(scene(), clipId, f(start), target, snap))

    // region zones

    @Test
    fun `near a junction on the base is an insert at that junction`() {
        val d = decide("x", 104, DropTarget.Lane("v1"))
        assertEquals(DropKind.INSERT, d.kind)
        assertEquals(DropHint(DropKind.INSERT, "v1", 100, 100), d.hint)
        assertEquals(EditCommand.MoveClip("x", f(100), "v1", null), d.command)
    }

    @Test
    fun `the insert radius is inclusive and picks the nearest junction`() {
        assertEquals(DropKind.INSERT, decide("x", 90, DropTarget.Lane("v1")).kind)
        assertEquals(DropKind.OVERWRITE, decide("x", 89, DropTarget.Lane("v1")).kind)
        assertEquals(DropKind.INSERT, decide("x", 110, DropTarget.Lane("v1")).kind)
        assertEquals(DropKind.OVERWRITE, decide("x", 111, DropTarget.Lane("v1")).kind)
    }

    @Test
    fun `on the base the lane start and end are junctions and past the end appends`() {
        assertEquals(DropHint(DropKind.INSERT, "v1", 0, 0), decide("x", 6, DropTarget.Lane("v1")).hint)
        assertEquals(DropHint(DropKind.INSERT, "v1", 200, 200), decide("x", 205, DropTarget.Lane("v1")).hint)
        assertEquals(DropHint(DropKind.INSERT, "v1", 200, 200), decide("x", 900, DropTarget.Lane("v1")).hint)
    }

    @Test
    fun `over the body of a base clip is an overwrite of the covered frames`() {
        val d = decide("x", 40, DropTarget.Lane("v1"))
        assertEquals(DropKind.OVERWRITE, d.kind)
        assertEquals(DropHint(DropKind.OVERWRITE, "v1", 40, 70), d.hint)
        assertEquals(EditCommand.OverwriteMove("x", "v1", f(40)), d.command)
    }

    @Test
    fun `an overlay lane inserts into a cut between two touching clips`() {
        val d = decide("x", 62, DropTarget.Lane("v2"))
        assertEquals(DropKind.INSERT, d.kind)
        assertEquals(EditCommand.InsertOnLane("x", "v2", f(60)), d.command)
        assertEquals(DropHint(DropKind.INSERT, "v2", 60, 60), d.hint)
    }

    @Test
    fun `an overlay lane never inserts at a gap or at the ends, those stay overwrite or move`() {
        // 100 is the end of q and the start of a gap, 150 the start of r after the gap: not cuts between touching clips.
        assertEquals(DropKind.MOVE, decide("x", 104, DropTarget.Lane("v2")).kind)
        assertEquals(DropKind.MOVE, decide("x", 182, DropTarget.Lane("v2")).kind)
    }

    @Test
    fun `an overlay lane over a clip body overwrites`() {
        val d = decide("x", 20, DropTarget.Lane("v2"))
        assertEquals(DropKind.OVERWRITE, d.kind)
        assertEquals(EditCommand.OverwriteMove("x", "v2", f(20)), d.command)
    }

    @Test
    fun `dropping on a clip that only touches the span still overwrites`() {
        // x is 30 long: starting at 125 it covers 125..155 and overlaps r (150..180); 125 is far from any junction.
        assertEquals(DropKind.OVERWRITE, decide("x", 125, DropTarget.Lane("v2")).kind)
    }

    @Test
    fun `free space in an overlay lane is a plain move`() {
        val d = decide("x", 200, DropTarget.Lane("v2"))
        assertEquals(DropKind.MOVE, d.kind)
        assertEquals(EditCommand.MoveClip("x", f(200), "v2", snap), d.command)
    }

    @Test
    fun `an empty lane takes a plain move`() {
        val t = scene().copy(tracks = listOf(Track("v9", TrackType.VIDEO)) + scene().tracks)
        val d = checkNotNull(DropPlan.decide(t, "x", f(10), DropTarget.Lane("v9"), snap))
        assertEquals(DropKind.MOVE, d.kind)
    }

    @Test
    fun `the same lane without a target change keeps its lane`() {
        val d = decide("x", 500, DropTarget.Lane("v3"))
        assertEquals(DropKind.MOVE, d.kind)
        assertEquals(EditCommand.MoveClip("x", f(500), null, snap), d.command)
    }

    @Test
    fun `audio lanes overwrite or move, never insert`() {
        assertEquals(DropKind.OVERWRITE, decide("m", 103, DropTarget.Lane("a1")).kind)
        assertEquals(DropKind.OVERWRITE, decide("n", 30, DropTarget.Lane("a1")).kind)
        assertEquals(DropKind.MOVE, decide("n", 400, DropTarget.Lane("a1")).kind)
    }

    @Test
    fun `a lane of another kind is ignored`() {
        // A video clip over the audio lane stays on its own lane.
        val d = decide("x", 20, DropTarget.Lane("a1"))
        assertEquals(DropKind.MOVE, d.kind)
        assertEquals(EditCommand.MoveClip("x", f(20), null, snap), d.command)
    }

    @Test
    fun `above the lanes creates a new lane for an overlay video clip`() {
        val d = decide("x", 40, DropTarget.AboveLanes)
        assertEquals(DropKind.NEW_LANE, d.kind)
        assertEquals(EditCommand.MoveToNewLane("x", f(40), snap), d.command)
        assertEquals(DropHint(DropKind.NEW_LANE, null, 40, 70), d.hint)
    }

    @Test
    fun `above the lanes lifts a base clip to a new lane and does nothing special for audio`() {
        val lifted = decide("a", 40, DropTarget.AboveLanes)
        assertEquals(DropKind.NEW_LANE, lifted.kind)
        assertEquals(EditCommand.LiftFromBase("a", null, f(40)), lifted.command)
        assertEquals(DropKind.MOVE, decide("m", 0, DropTarget.AboveLanes).kind)
    }

    @Test
    fun `outside every lane cancels`() {
        val d = decide("x", 40, DropTarget.Outside)
        assertEquals(DropKind.CANCEL, d.kind)
        assertNull(d.command)
        assertEquals(DropKind.CANCEL, d.hint.kind)
    }

    @Test
    fun `a base clip reorders on the base and is lifted when dropped on an overlay lane`() {
        assertEquals(DropKind.REORDER, decide("b", 10, DropTarget.Lane("v1")).kind)
        val overOverlay = decide("b", 10, DropTarget.Lane("v2"))
        assertEquals(DropKind.OVERWRITE, overOverlay.kind)
        assertEquals(EditCommand.LiftFromBase("b", "v2", f(10)), overOverlay.command)
        val inFreeSpace = decide("b", 200, DropTarget.Lane("v2"))
        assertEquals(DropKind.MOVE, inFreeSpace.kind)
        assertEquals(EditCommand.LiftFromBase("b", "v2", f(200)), inFreeSpace.command)
    }

    @Test
    fun `unknown clip has no decision and negative frames clamp to zero`() {
        assertNull(DropPlan.decide(scene(), "nope", f(0), DropTarget.Lane("v1"), snap))
        assertEquals(DropHint(DropKind.INSERT, "v1", 0, 0), decide("x", -50, DropTarget.Lane("v1")).hint)
    }

    // endregion

    // region the decision runs: indicator and release agree

    private fun applied(clipId: String, start: Long, target: DropTarget): Timeline =
        checkNotNull(decide(clipId, start, target).command).apply(scene()).getOrFail()

    @Test
    fun `insert on the base opens a gap and overlays follow the footage`() {
        val result = applied("x", 100, DropTarget.Lane("v1"))
        val base = result.track("v1")!!.clips
        assertEquals(listOf("a", "x", "b"), base.map { it.id.substringBefore('~') })
        assertTrue(base.zipWithNext().all { (l, r) -> l.timelineEnd == r.timelineStart })
        assertEquals(230L, base.last().timelineEnd.value)
    }

    @Test
    fun `overwrite on the base keeps its length`() {
        val result = applied("x", 40, DropTarget.Lane("v1"))
        assertEquals(200L, result.track("v1")!!.end.value)
        assertEquals(emptyList<String>(), result.invariantViolations())
    }

    @Test
    fun `every zone yields a valid timeline`() {
        for (start in 0L..360L step 3) {
            for (target in listOf(DropTarget.Lane("v1"), DropTarget.Lane("v2"), DropTarget.Lane("v3"), DropTarget.Lane("a1"), DropTarget.AboveLanes)) {
                val command = decide("x", start, target).command ?: continue
                val result = command.apply(scene())
                if (result is EditResult.Success) {
                    assertEquals("$target@$start", emptyList<String>(), result.value.invariantViolations())
                    assertEquals("$target@$start", emptyList<String>(), MagneticBase.baseViolations(result.value))
                }
            }
        }
    }

    // endregion
}
