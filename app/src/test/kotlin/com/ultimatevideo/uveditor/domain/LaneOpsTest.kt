package com.ultimatevideo.uveditor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LaneOpsTest {
    // Display order: overlays on top, base last, audio below.
    private fun scene(overlay1: List<Clip> = listOf(clip("x", 20, 40)), overlay2: List<Clip> = emptyList()) = timeline(
        track("v3", *overlay2.toTypedArray()),
        track("v2", *overlay1.toTypedArray()),
        track("v1", clip("a", 0, 100), clip("b", 100, 100)),
        track("a1", clip("m", 0, 200), type = TrackType.AUDIO),
    )

    private fun ids(t: Timeline) = t.tracks.map { it.id }

    // region new lane

    @Test
    fun `moving to a new lane puts a fresh lane above every other video lane`() {
        val result = LaneOps.moveToNewLane(scene(), "x", f(30)).getOrFail()
        assertEquals(listOf("track-v1", "v3", "v2", "v1", "a1"), ids(result))
        assertLayout(result, "track-v1", at("x", 30, 70))
        assertLayout(result, "v2")
        assertEquals(emptyList<String>(), result.invariantViolations())
    }

    @Test
    fun `new lane ids never collide`() {
        val t = timeline(track("track-v1"), track("v2", clip("x", 0, 10)), track("v1", clip("a", 0, 50)))
        val result = LaneOps.moveToNewLane(t, "x", f(0)).getOrFail()
        assertEquals(listOf("track-v2", "track-v1", "v2", "v1"), ids(result))
    }

    @Test
    fun `base clips and audio clips cannot go to a new overlay lane`() {
        assertEquals(EditError.BaseClipCannotLeave("a"), LaneOps.moveToNewLane(scene(), "a", f(0)).errorOrFail())
        assertTrue(LaneOps.moveToNewLane(scene(), "m", f(0)) is EditResult.Failure)
        assertEquals(EditError.ClipNotFound("nope"), LaneOps.moveToNewLane(scene(), "nope", f(0)).errorOrFail())
    }

    @Test
    fun `new lane move is one undo step`() {
        val after = EditHistory(scene()).execute(EditCommand.MoveToNewLane("x", f(30))).getOrFail()
        assertEquals(5, after.timeline.tracks.size)
        assertEquals(scene(), after.undo().timeline)
    }

    // endregion

    // region overwrite

    @Test
    fun `overwrite on an overlay lane replaces what the clip covers`() {
        val t = scene(listOf(clip("x", 0, 50), clip("y", 60, 40)), listOf(clip("z", 10, 20)))
        val result = LaneOps.overwriteMove(t, "z", "v2", f(40)).getOrFail()
        // z (20 frames) lands on 40..60: x is cut to 0..40, y untouched at 60.
        assertLayout(result, "v2", at("x", 0, 40), at("z", 40, 60), at("y", 60, 100))
        assertLayout(result, "v3")
    }

    @Test
    fun `overwrite onto the base keeps its length and clears overlays over the replaced part`() {
        val t = scene(listOf(clip("x", 20, 40), clip("keep", 150, 20)), listOf(clip("z", 300, 50)))
        val result = LaneOps.overwriteMove(t, "z", "v1", f(10)).getOrFail()
        // z replaces base frames 10..60 (a is cut around it); the base is still 0..200 without a gap.
        val base = result.track("v1")!!.clips
        assertEquals(0L, base.first().timelineStart.value)
        assertEquals(200L, base.last().timelineEnd.value)
        assertEquals(base.zipWithNext().all { (l, r) -> l.timelineEnd == r.timelineStart }, true)
        assertTrue(base.any { it.id == "z" && it.timelineStart.value == 10L && it.timelineEnd.value == 60L })
        // The overlay under 10..60 is cleared like a deleted range, the one outside stays where it was.
        assertLayout(result, "v2", at("keep", 150, 170))
        assertEquals(emptyList<String>(), result.invariantViolations())
    }

    @Test
    fun `overwrite onto the base past its end is placed at the end`() {
        val t = scene(listOf(clip("x", 20, 40)))
        val result = LaneOps.overwriteMove(t, "x", "v1", f(500)).getOrFail()
        val base = result.track("v1")!!.clips
        assertTrue(base.zipWithNext().all { (l, r) -> l.timelineEnd == r.timelineStart })
        assertEquals(200L, base.first { it.id == "x" }.timelineStart.value)
        assertEquals(240L, base.last().timelineEnd.value)
    }

    @Test
    fun `overwrite rejects other kinds of lane and base clips`() {
        assertTrue(LaneOps.overwriteMove(scene(), "x", "a1", f(0)) is EditResult.Failure)
        assertTrue(LaneOps.overwriteMove(scene(), "a", "v1", f(0)) is EditResult.Failure)
        assertEquals(EditError.TrackNotFound("zz"), LaneOps.overwriteMove(scene(), "x", "zz", f(0)).errorOrFail())
    }

    @Test
    fun `overwrite is one undo step`() {
        val after = EditHistory(scene()).execute(EditCommand.OverwriteMove("x", "v1", f(10))).getOrFail()
        assertEquals(scene(), after.undo().timeline)
    }

    // endregion


    @Test
    fun `overlap detection ignores the moving clip itself`() {
        val t = scene(listOf(clip("p", 0, 60), clip("q", 80, 20)))
        assertTrue(LaneOps.overlapsOnLane(t, "q", "v2", f(50)))
        assertFalse(LaneOps.overlapsOnLane(t, "q", "v2", f(60)))
        assertFalse(LaneOps.overlapsOnLane(t, "q", "v2", f(85)))
    }

    // region reorder lanes

    @Test
    fun `overlay lanes reorder among themselves`() {
        val down = LaneOps.moveTrack(scene(), "v3", 1).getOrFail()
        assertEquals(listOf("v2", "v3", "v1", "a1"), ids(down))
        val up = LaneOps.moveTrack(down, "v3", -1).getOrFail()
        assertEquals(ids(scene()), ids(up))
    }

    @Test
    fun `the base never moves and nothing passes it`() {
        assertEquals(EditError.BaseTrackCannotMove("v1"), LaneOps.moveTrack(scene(), "v1", -1).errorOrFail())
        // The lowest overlay cannot go below the base.
        assertTrue(LaneOps.moveTrack(scene(), "v2", 1) is EditResult.Failure)
        assertTrue(LaneOps.moveTrack(scene(), "v3", -1) is EditResult.Failure)
    }

    @Test
    fun `audio lanes only reorder with audio lanes and stay below`() {
        val t = timeline(
            track("v2"),
            track("v1", clip("a", 0, 10)),
            track("a1", type = TrackType.AUDIO),
            track("a2", type = TrackType.AUDIO),
        )
        assertEquals(listOf("v2", "v1", "a2", "a1"), ids(LaneOps.moveTrack(t, "a1", 1).getOrFail()))
        assertTrue(LaneOps.moveTrack(t, "a1", -1) is EditResult.Failure)
    }

    @Test
    fun `lane reorder is one undo step and keeps clips`() {
        val start = scene(overlay2 = listOf(clip("z", 0, 10)))
        val after = EditHistory(start).execute(EditCommand.MoveTrack("v3", 1)).getOrFail()
        assertEquals(listOf("v2", "v3", "v1", "a1"), ids(after.timeline))
        assertEquals(start.tracks.sumOf { it.clips.size }, after.timeline.tracks.sumOf { it.clips.size })
        assertEquals(start, after.undo().timeline)
    }

    // endregion

    // region lifting a clip off the base

    private fun liftScene() = timeline(
        track("v2", clip("x", 160, 40)),
        track("v1", clip("a", 0, 100), clip("b", 100, 50), clip("c", 150, 100)),
    )

    @Test
    fun `lifting a base clip closes the base gap, leaves every overlay alone and lands the clip on the overlay`() {
        val result = LaneOps.liftFromBase(liftScene(), "b", "v2", f(20)).getOrFail()
        assertLayout(result, "v1", at("a", 0, 100), at("c", 100, 200))
        assertLayout(result, "v2", at("b", 20, 70), at("x", 160, 200))
    }

    @Test
    fun `lifting onto a new lane puts the clip on a fresh top lane`() {
        val result = LaneOps.liftFromBase(liftScene(), "b", null, f(30)).getOrFail()
        assertEquals(3, result.tracks.size)
        val top = result.tracks.first()
        assertEquals(listOf("b"), top.clips.map { it.id })
        assertEquals(f(30), top.clips.single().timelineStart)
        assertEquals(emptyList<String>(), result.invariantViolations())
    }

    @Test
    fun `lifting over an overlay clip overwrites what it covers`() {
        val t = timeline(track("v2", clip("x", 0, 200)), track("v1", clip("a", 0, 100), clip("b", 100, 50)))
        val result = LaneOps.liftFromBase(t, "b", "v2", f(10)).getOrFail()
        assertEquals(emptyList<String>(), result.invariantViolations())
        assertEquals(1, result.tracks.first { it.id == "v2" }.clips.count { it.id == "b" })
    }

    @Test
    fun `only base clips can be lifted and the destination must be an overlay video lane`() {
        assertTrue(LaneOps.liftFromBase(liftScene(), "x", "v2", f(0)).errorOrFail() is EditError.InvalidClip)
        assertTrue(LaneOps.liftFromBase(liftScene(), "b", "v1", f(0)).errorOrFail() is EditError.TrackTypeMismatch)
        assertEquals(EditError.TrackNotFound("zz"), LaneOps.liftFromBase(liftScene(), "b", "zz", f(0)).errorOrFail())
    }

    @Test
    fun `lifting the only base clip leaves an empty base and undoes exactly`() {
        val t = timeline(track("v2"), track("v1", clip("a", 0, 100)))
        val lifted = EditHistory(t).execute(EditCommand.LiftFromBase("a", "v2", f(5))).getOrFail()
        assertEquals(emptyList<Clip>(), lifted.timeline.track("v1")!!.clips)
        assertEquals(t, lifted.undo().timeline)
    }

    // endregion
}
