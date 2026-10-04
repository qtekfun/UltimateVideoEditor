package com.ultimatevideo.uveditor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupClipboardTest {
    private fun rich(id: String, start: Long, len: Long, srcIn: Long = 50) = clip(id, start, len, srcIn = srcIn).copy(
        transform = ClipTransform(positionX = 12.0, scaleX = 2.0, scaleY = 2.0),
        fx = ClipFx(listOf(Effect("e1", EffectType.SEPIA, listOf(0.7))), BlendMode.MULTIPLY),
        keyframes = listOf(Keyframe(0, ClipTransform(opacity = 0.0)), Keyframe(len - 1, ClipTransform(opacity = 1.0))),
        gainDb = -4.0,
    )

    // Display order: overlay, base, audio.
    private fun scene() = timeline(
        track("v2", rich("p", 10, 20), rich("q", 30, 20), clip("far", 400, 10)),
        track("v1", clip("a", 0, 60), clip("b", 60, 60), clip("c", 120, 60)),
        track("a1", clip("m", 0, 200), type = TrackType.AUDIO),
    ).let { TimelineOps.addTransition(it, Transition("tr1", "p", "q", 4), 500).getOrFail() }

    private fun reason(result: EditResult<*>): String = (result.errorOrFail() as GroupEditUnavailable).reason

    @Test
    fun `capture keeps the clips, their lanes, relative layout and the transitions between copied clips`() {
        val board = checkNotNull(Clipboard.capture(scene(), listOf("q", "p", "b")))
        // Sorted by start: p (10), q (30), b (60).
        assertEquals(listOf("p", "q", "b"), board.entries.map { it.clip.id })
        assertEquals(listOf(0L, 20L, 50L), board.entries.map { it.relativeStart })
        assertEquals(listOf(false, false, true), board.entries.map { it.onBase })
        assertEquals(listOf("v2", "v2", "v1"), board.entries.map { it.trackId })
        assertEquals(listOf("tr1"), board.transitions.map { it.id })
        assertNull(Clipboard.capture(scene(), emptyList()))
        assertNull(Clipboard.capture(scene(), listOf("nope")))
        assertEquals(emptyList<Transition>(), checkNotNull(Clipboard.capture(scene(), listOf("p"))).transitions)
    }

    @Test
    fun `paste round trips every attribute, keyframes and the transition with fresh ids`() {
        val t = scene()
        val board = checkNotNull(Clipboard.capture(t, listOf("p", "q")))
        val result = GroupOps.paste(t, board, f(100)).getOrFail()
        val copies = result.track("v2")!!.clips.filter { it.id.contains("~c") }
        assertEquals(2, copies.size)
        assertEquals(listOf(100L, 120L), copies.map { it.timelineStart.value })
        val original = t.track("v2")!!.clip("p")!!
        val copy = copies.first()
        assertEquals(original.copy(id = copy.id, timelineStart = copy.timelineStart), copy)
        val transition = result.transitions.first { it.id != "tr1" }
        assertEquals(setOf(copies[0].id, copies[1].id), setOf(transition.fromClipId, transition.toClipId))
        assertEquals(4L, transition.durationFrames)
        assertEquals(emptyList<String>(), result.invariantViolations())
        assertEquals(t.track("v1"), result.track("v1"))
    }

    @Test
    fun `paste is refused where it would land on a clip`() {
        val t = scene()
        val board = checkNotNull(Clipboard.capture(t, listOf("p")))
        assertEquals(EditError.Overlap("q"), GroupOps.paste(t, board, f(35)).errorOrFail())
        assertTrue(reason(GroupOps.paste(t, Clipboard(emptyList(), emptyList()), f(0))).contains("empty"))
    }

    @Test
    fun `a pasted overlay uses the first lane of its kind when its lane is gone`() {
        val t = scene()
        val board = checkNotNull(Clipboard.capture(t, listOf("p")))
        val other = timeline(track("v9", clip("z", 0, 5)), track("v1", clip("a", 0, 100)))
        val result = GroupOps.paste(other, board, f(50)).getOrFail()
        assertEquals(2, result.track("v9")!!.clips.size)
        val noLane = timeline(track("v1", clip("a", 0, 100)))
        assertTrue(reason(GroupOps.paste(noLane, board, f(50))).contains("no video lane"))
    }

    @Test
    fun `a base run is inserted at the nearest cut, rippling later clips and overlays`() {
        val t = timeline(
            track("v2", clip("x", 70, 10), clip("y", 130, 10)),
            track("v1", clip("a", 0, 60), clip("b", 60, 60), clip("c", 120, 60)),
        )
        val board = checkNotNull(Clipboard.capture(t, listOf("a", "b")))
        // Pasted at 118 the cut nearest is 120 (between b and c).
        val result = GroupOps.paste(t, board, f(118)).getOrFail()
        assertEquals(listOf("a", "b", "a~c1", "b~c1", "c"), result.track("v1")!!.clips.map { it.id })
        assertLayout(result, "v1", at("a", 0, 60), at("b", 60, 120), at("a~c1", 120, 180), at("b~c1", 180, 240), at("c", 240, 300))
        // Overlays starting at or after the cut shift right by the pasted run (120); x, before it, stays.
        assertLayout(result, "v2", at("x", 70, 80), at("y", 250, 260))
        assertEquals(emptyList<String>(), MagneticBase.baseViolations(result))
    }

    @Test
    fun `base clips and overlay clips pasted together keep their relative offsets`() {
        val t = timeline(track("v2", clip("o", 20, 10)), track("v1", clip("a", 0, 60), clip("b", 60, 60)))
        val board = checkNotNull(Clipboard.capture(t, listOf("b", "o")))
        // Earliest is o (20); b is 40 after it. Pasted at 150: o' at 150 and the base run at the end of the base.
        val result = GroupOps.paste(t, board, f(150)).getOrFail()
        assertEquals(emptyList<String>(), result.invariantViolations())
        assertEquals(listOf("a", "b", "b~c1"), result.track("v1")!!.clips.map { it.id })
        assertEquals(150L, result.track("v2")!!.clips.single { it.id != "o" }.timelineStart.value)
    }

    @Test
    fun `duplicate puts the copies right after the group on the same lanes`() {
        val t = scene()
        val result = GroupOps.duplicate(t, listOf("far")).getOrFail()
        assertEquals(listOf(10L, 30L, 400L, 410L), result.track("v2")!!.clips.map { it.timelineStart.value })
        val onBase = GroupOps.duplicate(t, listOf("b")).getOrFail()
        assertEquals(listOf("a", "b", "b~c1", "c"), onBase.track("v1")!!.clips.map { it.id })
        assertEquals(emptyList<String>(), onBase.invariantViolations())
        // With the audio clip m (it ends at 200) the group ends at 200 and the copies follow m from there:
        // m at 200, p at 210 and q at 230.
        val withAudio = GroupOps.duplicate(t, listOf("p", "q", "m")).getOrFail()
        assertEquals(listOf(10L, 30L, 210L, 230L, 400L), withAudio.track("v2")!!.clips.map { it.timelineStart.value })
        assertEquals(emptyList<String>(), withAudio.invariantViolations())
    }

    @Test
    fun `cut is copy then delete as one undo step and paste restores the clips elsewhere`() {
        val t = scene()
        val board = checkNotNull(Clipboard.capture(t, listOf("p", "q")))
        val history = EditHistory(t).execute(GroupDelete(listOf("p", "q"))).getOrFail()
        assertNull(history.timeline.track("v2")!!.clip("p"))
        val pasted = history.execute(GroupPaste(board, f(200))).getOrFail()
        assertEquals(listOf(200L, 220L), pasted.timeline.track("v2")!!.clips.filter { it.id.contains("~c") }.map { it.timelineStart.value })
        assertEquals(t, pasted.undo().undo().timeline)
    }

    @Test
    fun `selection helpers pick whole lanes, everything after the playhead, a range and drop stale ids`() {
        val t = scene()
        assertEquals(setOf("p", "q", "far"), ClipSelection.allInLane(t, "v2"))
        assertEquals(emptySet<String>(), ClipSelection.allInLane(t, "nope"))
        assertEquals(setOf("q", "far", "a", "b", "c", "m"), ClipSelection.fromPlayhead(t, f(40)))
        assertEquals(setOf("q", "far"), ClipSelection.fromPlayhead(t, f(40), "v2"))
        assertEquals(setOf("p", "a", "m"), ClipSelection.inRange(t, f(0), f(30)))
        assertEquals(setOf("p"), ClipSelection.existing(t, listOf("p", "gone")))
        assertTrue(ClipSelection.isContiguousBaseRun(t, listOf("a", "b")))
        assertTrue(!ClipSelection.isContiguousBaseRun(t, listOf("a", "c")))
        assertTrue(!ClipSelection.isContiguousBaseRun(t, listOf("a", "p")))
        assertNotEquals(ClipAttributes.of(t.track("v2")!!.clip("p")!!), ClipAttributes.of(t.track("v1")!!.clip("a")!!))
    }
}
