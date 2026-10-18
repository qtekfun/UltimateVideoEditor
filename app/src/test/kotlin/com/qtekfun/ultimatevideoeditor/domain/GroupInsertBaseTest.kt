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
    fun `an audio clip, an unknown id, no clips or only base clips make the group ineligible`() {
        val t = scene()
        assertTrue(GroupOps.canInsertOnBase(t, listOf("p1", "q")))
        assertTrue(GroupOps.canInsertOnBase(t, listOf("p1", "a"))) // a base clip may travel with overlay clips
        assertEquals(false, GroupOps.canInsertOnBase(t, listOf("a", "b"))) // only base clips: a reorder
        assertEquals(false, GroupOps.canInsertOnBase(t, listOf("p1", "m")))
        assertEquals(false, GroupOps.canInsertOnBase(t, listOf("p1", "nope")))
        assertEquals(false, GroupOps.canInsertOnBase(t, emptyList()))
        assertTrue(GroupInsertBase(listOf("p1", "m"), f(100)).apply(t) is EditResult.Failure)
    }

    @Test
    fun `dropping near a cut inserts and in the middle of a clip overwrites`() {
        val t = scene()
        val ids = listOf("p1", "p2", "p3")
        val near = checkNotNull(DropPlan.decideGroupDrop(t, ids, f(104), DropAim()))
        assertEquals(DropKind.INSERT, near.kind)
        assertEquals(DropHint(DropKind.INSERT, "v1", 100, 100), near.hint)
        assertEquals(GroupInsertBase(ids, f(100)), near.command)
        val middle = checkNotNull(DropPlan.decideGroupDrop(t, ids, f(50), DropAim()))
        assertEquals(DropKind.OVERWRITE, middle.kind)
        assertEquals(GroupOverwriteBase(ids, f(50)), middle.command)
    }

    @Test
    fun `forcing insert takes the nearest cut, forcing overwrite never inserts, past the end appends`() {
        val t = scene()
        val ids = listOf("p1", "p2")
        val forced = checkNotNull(DropPlan.decideGroupDrop(t, ids, f(60), DropAim(choice = DropChoice.INSERT, finger = f(60))))
        assertEquals(GroupInsertBase(ids, f(100)), forced.command)
        val overwrite = checkNotNull(DropPlan.decideGroupDrop(t, ids, f(104), DropAim(choice = DropChoice.OVERWRITE)))
        assertEquals(GroupOverwriteBase(ids, f(104)), overwrite.command)
        val appended = checkNotNull(DropPlan.decideGroupDrop(t, ids, f(250), DropAim()))
        assertEquals(GroupInsertBase(ids, f(200)), appended.command)
    }

    @Test
    fun `an ineligible group has no group decision`() {
        assertNull(DropPlan.decideGroupDrop(scene(), listOf("p1", "m"), f(104), DropAim()))
    }

    @Test
    fun `overwrite puts the group over the footage, keeps the distances between its clips and the base length`() {
        // p1 300-330 and q 500-520: 200 apart. The earliest lands at 40, so q lands at 240, past the end (200), and is placed at the end.
        val result = GroupOverwriteBase(listOf("p1", "q"), f(40)).apply(scene()).getOrFail()
        assertEquals(listOf("p2", "p3"), result.track("v2")!!.clips.map { it.id })
        assertLayout(result, "v1", at("a", 0, 40), at("p1", 40, 70), at("a-1", 70, 100).let { at(result.track("v1")!!.clips[2].id, 70, 100) }, at("b", 100, 200), at("q", 200, 220))
    }

    @Test
    fun `overwrite never clears a clip of the group itself and is one undo step`() {
        // p1 and p2 are both above the range being replaced on the base: lifting them first keeps both.
        val before = scene()
        val after = GroupOverwriteBase(listOf("p1", "p2"), f(10)).apply(before).getOrFail()
        assertEquals(setOf("p1", "p2"), after.track("v1")!!.clips.map { it.id }.toSet() intersect setOf("p1", "p2"))
        val history = EditHistory(before).execute(GroupOverwriteBase(listOf("p1", "p2"), f(10))).getOrFail()
        assertEquals(before, history.undo().timeline)
    }
}
