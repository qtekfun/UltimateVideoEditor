package com.qtekfun.ultimatevideoeditor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Several overlay clips (a run of photos on V2) dropped into a cut of the base together, in order. */
class GroupInsertBaseTest {
    // v1 is the base: a 0-100, b 100-200. Photos p1..p3 (30 frames each, back to back) and a loose one q on v2.
    private fun scene() = timeline(
        track("v2", clip("p1", 300, 30), clip("p2", 330, 30), clip("p3", 360, 30), clip("q", 500, 20)),
        track("v1", clip("a", 0, 100), clip("b", 100, 100)),
        track("a1", clip("m", 0, 200), type = TrackType.AUDIO),
    )

    @Test
    fun `the group lands in the cut in timeline order and the rest ripples right`() {
        val result = GroupInsertBase(listOf("p3", "p1", "p2"), f(100)).apply(scene()).getOrFail()
        assertLayout(result, "v1", at("a", 0, 100), at("p1", 100, 130), at("p2", 130, 160), at("p3", 160, 190), at("b", 190, 290))
        assertEquals(listOf("q"), result.track("v2")!!.clips.map { it.id })
    }

    @Test
    fun `the group can go at the start or the end of the base`() {
        val atStart = GroupInsertBase(listOf("p1", "p2"), f(0)).apply(scene()).getOrFail()
        assertLayout(atStart, "v1", at("p1", 0, 30), at("p2", 30, 60), at("a", 60, 160), at("b", 160, 260))
        val atEnd = GroupInsertBase(listOf("p1", "p2"), f(200)).apply(scene()).getOrFail()
        assertLayout(atEnd, "v1", at("a", 0, 100), at("b", 100, 200), at("p1", 200, 230), at("p2", 230, 260))
    }

    @Test
    fun `one undo step restores the whole timeline`() {
        val before = scene()
        val history = EditHistory(before).execute(GroupInsertBase(listOf("p1", "p2", "p3"), f(100))).getOrFail()
        assertEquals(before, history.undo().timeline)
    }

    @Test
    fun `a base clip, an audio clip or an unknown id make the group ineligible`() {
        val t = scene()
        assertTrue(GroupOps.canInsertOnBase(t, listOf("p1", "q")))
        assertEquals(false, GroupOps.canInsertOnBase(t, listOf("p1", "a")))
        assertEquals(false, GroupOps.canInsertOnBase(t, listOf("p1", "m")))
        assertEquals(false, GroupOps.canInsertOnBase(t, listOf("p1", "nope")))
        assertEquals(false, GroupOps.canInsertOnBase(t, emptyList()))
        assertTrue(GroupInsertBase(listOf("p1", "a"), f(100)).apply(t) is EditResult.Failure)
    }

    @Test
    fun `dropping near a cut inserts and in the middle of a clip does not`() {
        val t = scene()
        val ids = listOf("p1", "p2", "p3")
        val near = checkNotNull(DropPlan.decideGroupInsert(t, ids, f(104), DropAim()))
        assertEquals(DropKind.INSERT, near.kind)
        assertEquals(DropHint(DropKind.INSERT, "v1", 100, 100), near.hint)
        assertEquals(GroupInsertBase(ids, f(100)), near.command)
        assertNull(DropPlan.decideGroupInsert(t, ids, f(50), DropAim()))
    }

    @Test
    fun `forcing insert takes the nearest cut and past the end appends`() {
        val t = scene()
        val ids = listOf("p1", "p2")
        val forced = checkNotNull(DropPlan.decideGroupInsert(t, ids, f(60), DropAim(choice = DropChoice.INSERT, finger = f(60))))
        assertEquals(GroupInsertBase(ids, f(100)), forced.command)
        val appended = checkNotNull(DropPlan.decideGroupInsert(t, ids, f(250), DropAim()))
        assertEquals(GroupInsertBase(ids, f(200)), appended.command)
        assertNull(DropPlan.decideGroupInsert(t, ids, f(104), DropAim(choice = DropChoice.OVERWRITE)))
    }
}
