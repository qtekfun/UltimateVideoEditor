package com.qtekfun.ultimatevideoeditor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The reach around a cut and the insert / overwrite choice made during a drag (SPECS 5.5, DECISIONS "Insert or overwrite while dragging"). */
class DropPlanChoiceTest {
    // Overlay x (60 long) over a base of three 100-frame clips: cuts at 100 and 200, ends at 0 and 300.
    private fun scene() = timeline(
        track("v2", clip("x", 0, 60), clip("y", 400, 50), clip("t", 500, 10)),
        track("v1", clip("a", 0, 100), clip("b", 100, 100), clip("c", 200, 100)),
        track("a1", clip("m", 0, 100), clip("n", 100, 50), type = TrackType.AUDIO),
    )

    private fun decide(start: Long, target: String = "v1", aim: DropAim = DropAim(), id: String = "x") =
        checkNotNull(DropPlan.decide(scene(), id, f(start), DropTarget.Lane(target), null, aim))

    @Test
    fun `at the default reach a clip start 25 frames from a cut overwrites`() {
        assertEquals(DropKind.OVERWRITE, decide(125).kind)
    }

    @Test
    fun `a wider reach from a zoomed out timeline inserts at the cut that is a fingertip away`() {
        // Regression: the radius was a fixed 10 frames, a few pixels at fit zoom, so a drop between two clips never landed.
        // x is 60 long, so starting at 125 its end edge is 15 from the cut at 200 (and the start 25 from the one at 100).
        val d = decide(125, aim = DropAim(reachFrames = 30))
        assertEquals(DropKind.INSERT, d.kind)
        assertEquals(DropHint(DropKind.INSERT, "v1", 200, 200), d.hint)
        // y is 50 long: its end is 25 from either cut, the tie goes to the first.
        assertEquals(DropHint(DropKind.INSERT, "v1", 100, 100), decide(125, id = "y", aim = DropAim(reachFrames = 30)).hint)
    }

    @Test
    fun `the end edge of the dragged clip aims at a cut too`() {
        // x is 60 long: starting at 45 it ends at 105, 5 frames past the cut at 100.
        assertEquals(DropHint(DropKind.INSERT, "v1", 100, 100), decide(45).hint)
        // Starting at 30 it ends at 90, within 10 of the cut as well.
        assertEquals(DropKind.INSERT, decide(30).kind)
    }

    @Test
    fun `the finger aims at a cut when neither edge is near`() {
        assertEquals(DropKind.OVERWRITE, decide(120).kind)
        assertEquals(DropHint(DropKind.INSERT, "v1", 200, 200), decide(120, aim = DropAim(finger = f(204))).hint)
    }

    @Test
    fun `a huge reach never turns the middle of a clip into a cut`() {
        // Zoomed far out: the reach exceeds the clips, yet a third of the neighbour is the most that counts as the cut.
        val huge = DropAim(reachFrames = 5_000, finger = f(150))
        assertEquals(DropKind.OVERWRITE, decide(145, id = "t", aim = huge).kind)
        assertEquals(DropKind.INSERT, decide(125, id = "t", aim = huge.copy(finger = f(133))).kind)
    }

    @Test
    fun `the start and the end of the base are junctions`() {
        assertEquals(DropHint(DropKind.INSERT, "v1", 0, 0), decide(4).hint)
        // Past the end the clip is appended whatever the aim.
        assertEquals(DropHint(DropKind.INSERT, "v1", 300, 300), decide(340).hint)
    }

    @Test
    fun `forcing insert over the body of a clip inserts at the nearest cut`() {
        val d = decide(130, aim = DropAim(finger = f(130), choice = DropChoice.INSERT))
        assertEquals(DropHint(DropKind.INSERT, "v1", 100, 100), d.hint)
        assertTrue(d.command is EditCommand.MoveClip)
        val far = decide(185, aim = DropAim(finger = f(185), choice = DropChoice.INSERT))
        assertEquals(DropHint(DropKind.INSERT, "v1", 200, 200), far.hint)
    }

    @Test
    fun `forcing overwrite at a cut overwrites what the clip covers and keeps the base length`() {
        val d = decide(98, aim = DropAim(choice = DropChoice.OVERWRITE))
        assertEquals(DropKind.OVERWRITE, d.kind)
        assertEquals(DropHint(DropKind.OVERWRITE, "v1", 98, 158), d.hint)
        val after = (d.command!!.apply(scene()) as EditResult.Success).value
        assertEquals(300L, after.track("v1")!!.end.value)
        assertEquals(emptyList<String>(), after.invariantViolations())
    }

    @Test
    fun `insert is one undo step in the history and ripples later clips`() {
        val d = decide(125, aim = DropAim(reachFrames = 30))
        val done = (EditHistory(scene()).execute(d.command!!) as EditResult.Success).value
        assertEquals(listOf("a", "b", "x", "c"), done.timeline.track("v1")!!.clips.map { it.id })
        assertEquals(360L, done.timeline.track("v1")!!.end.value)
        assertEquals(emptyList<String>(), done.timeline.invariantViolations())
        assertEquals(scene(), done.undo().timeline)
    }

    @Test
    fun `on an overlay lane forcing insert needs a cut between touching clips`() {
        val lane = timeline(
            track("v2", clip("p", 0, 60), clip("q", 60, 40), clip("r", 200, 30), clip("z", 300, 20)),
            track("v1", clip("a", 0, 400)),
        )
        // r sits free: nothing to insert into near 250, so a forced insert goes to the cut at 60.
        val forced = checkNotNull(DropPlan.decide(lane, "z", f(250), DropTarget.Lane("v2"), null, DropAim(finger = f(250), choice = DropChoice.INSERT)))
        assertEquals(DropHint(DropKind.INSERT, "v2", 60, 60), forced.hint)
        // Auto at the same place is a plain move; forced overwrite at the cut covers instead of inserting.
        assertEquals(DropKind.MOVE, checkNotNull(DropPlan.decide(lane, "z", f(250), DropTarget.Lane("v2"))).kind)
        val over = checkNotNull(DropPlan.decide(lane, "z", f(55), DropTarget.Lane("v2"), null, DropAim(choice = DropChoice.OVERWRITE)))
        assertEquals(DropKind.OVERWRITE, over.kind)
    }

    @Test
    fun `a lane without cuts never inserts, forced or not`() {
        val lane = timeline(track("v2", clip("p", 0, 60), clip("q", 100, 40), clip("z", 300, 20)), track("v1", clip("a", 0, 400)))
        val d = checkNotNull(DropPlan.decide(lane, "z", f(70), DropTarget.Lane("v2"), null, DropAim(choice = DropChoice.INSERT)))
        assertEquals(DropKind.MOVE, d.kind)
    }

    @Test
    fun `an empty base inserts at zero whatever the choice`() {
        val empty = timeline(track("v2", clip("x", 50, 30)), track("v1"))
        val d = checkNotNull(DropPlan.decide(empty, "x", f(70), DropTarget.Lane("v1"), null, DropAim(choice = DropChoice.OVERWRITE)))
        assertEquals(DropHint(DropKind.INSERT, "v1", 0, 0), d.hint)
    }

    @Test
    fun `new media from the tray uses the same reach and choice`() {
        val incoming = clip("new", 0, 30)
        val near = DropPlan.decideNew(scene(), incoming, TrackType.VIDEO, f(125), DropTarget.Lane("v1"), null, DropAim(reachFrames = 30))
        assertEquals(DropKind.INSERT, near.kind)
        val forced = DropPlan.decideNew(scene(), incoming, TrackType.VIDEO, f(98), DropTarget.Lane("v1"), null, DropAim(choice = DropChoice.OVERWRITE))
        assertEquals(DropKind.OVERWRITE, forced.kind)
    }

    @Test
    fun `flipping toggles between the two actions`() {
        assertEquals(DropChoice.OVERWRITE, DropChoice.AUTO.flipped(DropKind.INSERT))
        assertEquals(DropChoice.INSERT, DropChoice.AUTO.flipped(DropKind.OVERWRITE))
        assertEquals(DropChoice.INSERT, DropChoice.OVERWRITE.flipped(DropKind.OVERWRITE))
    }
}
