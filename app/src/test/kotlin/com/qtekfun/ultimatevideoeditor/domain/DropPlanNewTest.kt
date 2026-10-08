package com.qtekfun.ultimatevideoeditor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DropPlanNewTest {
    // Display order: overlay on top, base, audio below.
    private fun scene() = timeline(
        track("v2", clip("p", 0, 60), clip("q", 60, 40), clip("r", 150, 30)),
        track("v1", clip("a", 0, 100), clip("b", 100, 100)),
        track("a1", clip("m", 0, 100), clip("n", 100, 50), type = TrackType.AUDIO),
    )

    private val incoming = clip("new", 0, 30)
    private val snap = Snap(playhead = null, thresholdFrames = 8)

    private fun decide(
        start: Long,
        target: DropTarget,
        type: TrackType = TrackType.VIDEO,
        clip: Clip = incoming,
        withSnap: Snap? = snap,
    ) = DropPlan.decideNew(scene(), clip, type, f(start), target, withSnap)

    @Test
    fun `near a cut on the base inserts there`() {
        val d = decide(104, DropTarget.Lane("v1"))
        assertEquals(DropKind.INSERT, d.kind)
        assertEquals(DropHint(DropKind.INSERT, "v1", 100, 100), d.hint)
        assertEquals(EditCommand.InsertBase(incoming, f(100)), d.command)
    }

    @Test
    fun `over the body of a base clip overwrites`() {
        val d = decide(140, DropTarget.Lane("v1"))
        assertEquals(DropKind.OVERWRITE, d.kind)
        assertEquals("v1", d.hint.trackId)
        assertEquals(140L, d.hint.startFrame)
        assertTrue(d.command is EditCommand.OverwriteNewClip)
    }

    @Test
    fun `past the end of the base appends and an empty base inserts at zero`() {
        assertEquals(DropHint(DropKind.INSERT, "v1", 200, 200), decide(260, DropTarget.Lane("v1")).hint)
        val empty = timeline(track("v2"), track("v1"))
        val d = DropPlan.decideNew(empty, incoming, TrackType.VIDEO, f(50), DropTarget.Lane("v1"), snap)
        assertEquals(DropHint(DropKind.INSERT, "v1", 0, 0), d.hint)
    }

    @Test
    fun `an overlay lane overwrites what it covers and places the clip in free space`() {
        assertEquals(DropKind.OVERWRITE, decide(80, DropTarget.Lane("v2")).kind)
        val free = decide(110, DropTarget.Lane("v2"))
        assertEquals(DropKind.MOVE, free.kind)
        assertEquals(EditCommand.OverwriteNewClip(incoming, "v2", f(110)), free.command)
    }

    @Test
    fun `near a cut between two touching clips of an overlay lane inserts there`() {
        // "p" and "q" touch at 60.
        val d = decide(66, DropTarget.Lane("v2"))
        assertEquals(DropKind.INSERT, d.kind)
        assertEquals(DropHint(DropKind.INSERT, "v2", 60, 60), d.hint)
        assertEquals(EditCommand.InsertNewOnLane(incoming, "v2", f(60)), d.command)
        // The end of a lane and a gap between clips are not cuts: they stay plain placements or overwrites.
        assertEquals(DropKind.MOVE, decide(110, DropTarget.Lane("v2")).kind)
    }

    @Test
    fun `above the lanes makes a new lane for video only`() {
        val d = decide(40, DropTarget.AboveLanes)
        assertEquals(DropKind.NEW_LANE, d.kind)
        assertEquals(EditCommand.AddClipOnNewLane(incoming, f(40)), d.command)
        assertEquals(DropKind.CANCEL, decide(40, DropTarget.AboveLanes, TrackType.AUDIO).kind)
    }

    @Test
    fun `a lane of the wrong kind or the outside cancels`() {
        assertEquals(DropKind.CANCEL, decide(10, DropTarget.Lane("a1")).kind)
        assertEquals(DropKind.CANCEL, decide(10, DropTarget.Lane("v2"), TrackType.AUDIO).kind)
        assertEquals(DropKind.CANCEL, decide(10, DropTarget.Outside).kind)
        assertNull(decide(10, DropTarget.Outside).command)
        assertEquals(DropKind.CANCEL, decide(10, DropTarget.Lane("missing")).kind)
    }

    @Test
    fun `audio lands on an audio lane and overwrites what it covers`() {
        val audio = clip("snd", 0, 40)
        assertEquals(DropKind.OVERWRITE, decide(80, DropTarget.Lane("a1"), TrackType.AUDIO, audio).kind)
        assertEquals(DropKind.MOVE, decide(160, DropTarget.Lane("a1"), TrackType.AUDIO, audio).kind)
    }

    @Test
    fun `snapping pulls the start to the playhead and the end to an edge`() {
        val withPlayhead = Snap(playhead = f(212), thresholdFrames = 8)
        assertEquals(
            EditCommand.OverwriteNewClip(incoming, "v2", f(212)),
            decide(215, DropTarget.Lane("v2"), withSnap = withPlayhead).command,
        )
        // The clip's end (start + 30 = 148) is within 2 frames of 150, the start of "r": the start becomes 120.
        val d = decide(118, DropTarget.Lane("v2"))
        assertEquals(DropKind.MOVE, d.kind)
        assertEquals(120L, d.hint.startFrame)
        // Without a snap the position is kept exactly.
        assertEquals(118L, decide(118, DropTarget.Lane("v2"), withSnap = null).hint.startFrame)
    }

    @Test
    fun `negative starts clamp to zero`() {
        assertEquals(0L, decide(-30, DropTarget.Lane("v2"), withSnap = null).hint.startFrame)
    }

    // region the operations behind the commands

    @Test
    fun `a new clip on a new lane lands above every video lane`() {
        val result = LaneOps.addClipOnNewLane(scene(), incoming, f(40)).getOrFail()
        assertEquals(4, result.tracks.size)
        val top = result.tracks.first()
        assertEquals(TrackType.VIDEO, top.type)
        assertEquals(listOf("new"), top.clips.map { it.id })
        assertEquals(f(40), top.clips.single().timelineStart)
        assertEquals(emptyList<String>(), result.invariantViolations())
        assertEquals(EditError.DuplicateClipId("a"), LaneOps.addClipOnNewLane(scene(), clip("a", 0, 5), f(0)).errorOrFail())
    }

    @Test
    fun `overwriting a new clip on the base replaces footage and clears the overlays above it`() {
        val result = LaneOps.overwriteNewClip(scene(), incoming, "v1", f(40)).getOrFail()
        assertEquals(emptyList<String>(), result.invariantViolations())
        // The base never gets a gap and keeps its length (the clip ends inside it).
        assertEquals(200L, result.track("v1")!!.end.value)
        assertTrue(result.track("v1")!!.clips.any { it.id == "new" && it.timelineStart == f(40) })
        // Overlays over the replaced range 40..70 are trimmed or removed, not kept whole.
        assertTrue(result.track("v2")!!.clips.none { it.timelineStart < f(70) && it.timelineEnd > f(40) })
    }

    @Test
    fun `inserting a new clip into a cut of an overlay shifts only that lane`() {
        val t = scene()
        val result = LaneOps.insertNewOnLane(t, incoming, "v2", f(60)).getOrFail()
        assertEquals(emptyList<String>(), result.invariantViolations())
        assertEquals(listOf("p", "new", "q", "r"), result.track("v2")!!.clips.map { it.id })
        assertEquals(f(90), result.track("v2")!!.clips.first { it.id == "q" }.timelineStart)
        assertEquals(f(180), result.track("v2")!!.clips.first { it.id == "r" }.timelineStart)
        assertEquals(t.track("v1"), result.track("v1"))
        assertEquals(t, EditHistory(t).execute(EditCommand.InsertNewOnLane(incoming, "v2", f(60))).getOrFail().undo().timeline)
    }

    @Test
    fun `inserting a new clip inside a clip or on the base is refused`() {
        assertTrue(LaneOps.insertNewOnLane(scene(), incoming, "v2", f(30)).errorOrFail() is EditError.Overlap)
        assertTrue(LaneOps.insertNewOnLane(scene(), incoming, "v1", f(100)).errorOrFail() is EditError.InvalidClip)
        assertEquals(EditError.DuplicateClipId("a"), LaneOps.insertNewOnLane(scene(), clip("a", 0, 5), "v2", f(60)).errorOrFail())
    }

    @Test
    fun `a new clip past the end of the base is placed at the end`() {
        val result = LaneOps.overwriteNewClip(scene(), incoming, "v1", f(500)).getOrFail()
        assertEquals(f(200), result.track("v1")!!.clips.last { it.id == "new" }.timelineStart)
    }

    @Test
    fun `new clip commands are one undo step each`() {
        val t = scene()
        for (command in listOf(
            EditCommand.AddClipOnNewLane(incoming, f(10)),
            EditCommand.OverwriteNewClip(incoming, "v2", f(110)),
            EditCommand.InsertBase(incoming, f(100)),
        )) {
            val done = EditHistory(t).execute(command).getOrFail()
            assertEquals(emptyList<String>(), done.timeline.invariantViolations())
            assertEquals(t, done.undo().timeline)
        }
    }

    // endregion
}
