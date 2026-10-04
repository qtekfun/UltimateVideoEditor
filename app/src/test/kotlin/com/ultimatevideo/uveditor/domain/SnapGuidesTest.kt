package com.ultimatevideo.uveditor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SnapGuidesTest {
    private val base = timeline(
        track("v1", clip("a", 0, 100), clip("b", 100, 50)),
        track("v2", clip("c", 300, 40)),
    )
    private val snap = Snap(playhead = f(500), thresholdFrames = 8, extraTargets = listOf(f(700)))

    private fun guide(preview: Timeline, vararg moving: String) = SnapGuides.guideFrame(base, preview, moving.toList(), snap)

    @Test
    fun `a moved clip whose start sits on another clip's end gets a line there`() {
        // c moves from 300 to 150: its start touches the end of b.
        val preview = TimelineOps.move(base, "c", f(150), "v1").getOrFail()
        assertEquals(150L, guide(preview, "c"))
    }

    @Test
    fun `a moved clip whose end sits on another clip's start gets a line at its end`() {
        val moved = timeline(track("v1", clip("a", 100, 100)), track("v2", clip("c", 360, 40)))
        val preview = TimelineOps.move(moved, "c", f(60)).getOrFail() // 60..100, so its end meets the start of a
        assertEquals(100L, SnapGuides.guideFrame(moved, preview, listOf("c"), snap))
    }

    @Test
    fun `the playhead, markers and frame zero are targets`() {
        assertEquals(500L, guide(TimelineOps.move(base, "c", f(500)).getOrFail(), "c"))
        assertEquals(700L, guide(TimelineOps.move(base, "c", f(700)).getOrFail(), "c"))
        assertEquals(0L, guide(TimelineOps.move(base, "c", f(0), "v2").getOrFail(), "c"))
    }

    @Test
    fun `no line when nothing moved or nothing is aligned`() {
        assertNull(guide(base, "a"))
        assertNull(guide(base, "b")) // b touches a, but it is where it began
        assertNull(guide(TimelineOps.move(base, "c", f(220)).getOrFail(), "c"))
    }

    @Test
    fun `a trim only counts the edge that moved`() {
        // Trimming the end of b to 120: its start still touches a's end, which must not draw a line.
        val trimmed = EditCommand.TrimClip("b", TrimEdge.END, f(120)).apply(base).getOrFail()
        assertNull(guide(trimmed, "b"))
        // Trimming the start of b to the playhead (frame 120) does: that edge moved and sits on a target.
        val toPlayhead = EditCommand.TrimClip("b", TrimEdge.START, f(120)).apply(base).getOrFail()
        assertEquals(120L, SnapGuides.guideFrame(base, toPlayhead, listOf("b"), Snap(f(120), 8)))
    }

    @Test
    fun `clips that move together are not targets for each other`() {
        // c1 and c2 touch and move right together: c2's start meets c1's end, but both are moving.
        val lanes = timeline(track("v1", clip("a", 0, 100)), track("au", clip("c1", 300, 40), clip("c2", 340, 40), type = TrackType.AUDIO))
        val preview = GroupOps.move(lanes, listOf("c1", "c2"), 250, 0).getOrFail()
        assertNull(SnapGuides.guideFrame(lanes, preview, listOf("c1", "c2"), snap))
    }

    @Test
    fun `targets leave out the moving clips`() {
        assertEquals(setOf(0L, 500L, 700L, 300L, 340L), SnapGuides.targets(base, setOf("a", "b"), snap))
    }
}
