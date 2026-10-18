package com.qtekfun.ultimatevideoeditor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Video clips dragged together to wherever the finger lets go: an overlay lane, new lanes above, or into a cut of the base. */
class GroupMoveToLanesTest {
    // v3 and v2 are overlay lanes (v3 on top), v1 is the base.
    private fun scene() = timeline(
        track("v3"),
        track("v2", clip("o1", 200, 30), clip("o2", 300, 30)),
        track("v1", clip("a", 0, 30), clip("b", 30, 30), clip("c", 60, 30), clip("d", 90, 30)),
        track("a1", clip("m", 0, 120), type = TrackType.AUDIO),
    )

    @Test
    fun `base clips lifted to an overlay lane keep their times and the base closes behind them`() {
        val result = GroupMoveToLanes(listOf("b", "c"), 0, "b", "v3").apply(scene()).getOrFail()
        assertLayout(result, "v3", at("b", 30, 60), at("c", 60, 90))
        assertLayout(result, "v1", at("a", 0, 30), at("d", 30, 60))
    }

    @Test
    fun `the whole group moves later by the delta`() {
        val result = GroupMoveToLanes(listOf("b", "c"), 100, "c", "v3").apply(scene()).getOrFail()
        assertLayout(result, "v3", at("b", 130, 160), at("c", 160, 190))
    }

    @Test
    fun `dropped above the lanes the clips get a new lane on top`() {
        val result = GroupMoveToLanes(listOf("a", "b"), 0, "a", null).apply(scene()).getOrFail()
        assertEquals(listOf("track-v1", "v3", "v2", "v1", "a1"), result.tracks.map { it.id }.let { ids -> ids })
        assertLayout(result, "track-v1", at("a", 0, 30), at("b", 30, 60))
        assertLayout(result, "v1", at("c", 0, 30), at("d", 30, 60))
    }

    @Test
    fun `a group from the base and an overlay lane makes one new lane for each lane it came from, in the same order`() {
        val result = GroupMoveToLanes(listOf("o1", "b"), 0, "b", null).apply(scene()).getOrFail()
        val top = result.tracks.take(2).map { it.id }
        assertEquals(2, top.size)
        // The overlay lane's clip is on the upper new lane, the base clip on the lower one.
        assertEquals(listOf("o1"), result.track(top[0])!!.clips.map { it.id })
        assertEquals(listOf("b"), result.track(top[1])!!.clips.map { it.id })
        assertLayout(result, "v1", at("a", 0, 30), at("c", 30, 60), at("d", 60, 90))
    }

    @Test
    fun `when the grabbed clip was on an overlay lane the other overlay clips shift by as many lanes`() {
        val result = GroupMoveToLanes(listOf("o1", "o2"), 0, "o1", "v3").apply(scene()).getOrFail()
        assertLayout(result, "v3", at("o1", 200, 230), at("o2", 300, 330))
        assertTrue(result.track("v2")!!.clips.isEmpty())
    }

    @Test
    fun `there must be a lane for every clip that shifts`() {
        // Moving the group down a lane from v3 would need a lane below the base.
        val timeline = timeline(
            track("v3", clip("p", 0, 30)),
            track("v2", clip("q", 40, 30)),
            track("v1", clip("a", 0, 30)),
        )
        val result = GroupMoveToLanes(listOf("p", "q"), 0, "q", "v3").apply(timeline)
        assertTrue(result is EditResult.Failure)
    }

    @Test
    fun `audio or title clips make the group refuse`() {
        val result = GroupMoveToLanes(listOf("b", "m"), 0, "b", "v3").apply(scene())
        assertTrue(result is EditResult.Failure)
    }

    @Test
    fun `a clip cannot be sent before the start of the timeline`() {
        val result = GroupMoveToLanes(listOf("c", "d"), -70, "c", "v3").apply(scene())
        assertTrue(result is EditResult.Failure)
    }

    @Test
    fun `it is one undo step`() {
        val before = scene()
        val history = EditHistory(before).execute(GroupMoveToLanes(listOf("b", "c"), 0, "b", null)).getOrFail()
        assertEquals(before, history.undo().timeline)
    }

    @Test
    fun `a group that mixes base and overlay clips is inserted into a cut of the base`() {
        val result = GroupInsertBase(listOf("o1", "b"), f(90)).apply(scene()).getOrFail()
        // b leaves its place, so the cut before d is the one between c and d; o1 and b go in there, earliest first.
        assertLayout(result, "v1", at("a", 0, 30), at("c", 30, 60), at("b", 60, 90), at("o1", 90, 120), at("d", 120, 150))
        assertEquals(listOf("o2"), result.track("v2")!!.clips.map { it.id })
    }

    @Test
    fun `the group decision aims at the cuts of the clips that stay on the base`() {
        val t = scene()
        // b is part of the group: the base without it is a, c, d, and the cut between c and d sits at 60 in its closed-up form.
        val decision = checkNotNull(DropPlan.decideGroupDrop(t, listOf("o1", "b"), f(62), DropAim()))
        assertEquals(DropKind.INSERT, decision.kind)
        assertEquals(GroupInsertBase(listOf("o1", "b"), f(90)), decision.command)
    }

    @Test
    fun `a group made only of base clips has no group drop, it is reordered as before`() {
        assertNull(DropPlan.decideGroupDrop(scene(), listOf("b", "c"), f(10), DropAim()))
        assertTrue(!GroupOps.canInsertOnBase(scene(), listOf("b", "c")))
    }

    @Test
    fun `overwriting the base with a mixed group removes the base members first`() {
        val result = GroupOverwriteBase(listOf("o1", "b"), f(0)).apply(scene()).getOrFail()
        // b left (the base closed), then o1 and b land at 0 and 170-... keeping their distance; nothing of the group is cleared by itself.
        assertTrue(result.trackOfClip("o1") != null && result.trackOfClip("b") != null)
        assertEquals(listOf("v1"), listOf(result.trackOfClip("b")!!.id))
    }
}
