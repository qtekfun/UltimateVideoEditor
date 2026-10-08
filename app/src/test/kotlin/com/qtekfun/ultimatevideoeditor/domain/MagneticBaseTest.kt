package com.qtekfun.ultimatevideoeditor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MagneticBaseTest {
    // Display order: overlay on top, base last. Base is contiguous: a[0,100) b[100,150) c[150,250).
    private fun scene(vararg overlay: Clip) = timeline(
        track("v2", *overlay),
        track("v1", clip("a", 0, 100), clip("b", 100, 50), clip("c", 150, 100)),
    )

    private fun Timeline.assertBaseOk(): Timeline {
        assertEquals(emptyList<String>(), MagneticBase.baseViolations(this))
        return this
    }

    private fun Timeline.pieces(trackId: String) = track(trackId)!!.clips.map { Triple(it.id, it.timelineStart.value, it.timelineEnd.value) }

    // region insert

    @Test
    fun `insert goes to the nearest clip boundary and ripples the base`() {
        val nearStart = MagneticBase.insert(scene(), clip("n", 0, 20), f(110)).getOrFail().assertBaseOk()
        assertLayout(nearStart, "v1", at("a", 0, 100), at("n", 100, 120), at("b", 120, 170), at("c", 170, 270))
        val nearEnd = MagneticBase.insert(scene(), clip("n", 0, 20), f(140)).getOrFail().assertBaseOk()
        assertLayout(nearEnd, "v1", at("a", 0, 100), at("b", 100, 150), at("n", 150, 170), at("c", 170, 270))
    }

    @Test
    fun `insert on a tie, on a start and past the end`() {
        // 125 is the middle of b: ties go to the end boundary.
        assertLayout(
            MagneticBase.insert(scene(), clip("n", 0, 10), f(125)).getOrFail(),
            "v1", at("a", 0, 100), at("b", 100, 150), at("n", 150, 160), at("c", 160, 260),
        )
        assertLayout(
            MagneticBase.insert(scene(), clip("n", 0, 10), f(0)).getOrFail(),
            "v1", at("n", 0, 10), at("a", 10, 110), at("b", 110, 160), at("c", 160, 260),
        )
        assertLayout(
            MagneticBase.insert(scene(), clip("n", 0, 10), f(9999)).getOrFail().assertBaseOk(),
            "v1", at("a", 0, 100), at("b", 100, 150), at("c", 150, 250), at("n", 250, 260),
        )
    }

    @Test
    fun `insert shifts overlays at or after the point and leaves crossing overlays`() {
        val t = scene(clip("before", 10, 20), clip("cross", 90, 30), clip("at", 150, 10), clip("after", 200, 10))
        val result = MagneticBase.insert(t, clip("n", 0, 20), f(100)).getOrFail()
        // Point is 100: "cross" starts before it and stays, later overlays shift by 20.
        assertLayout(result, "v2", at("before", 10, 30), at("cross", 90, 120), at("at", 170, 180), at("after", 220, 230))
    }

    @Test
    fun `insert into an empty base starts at zero and closes legacy gaps first`() {
        val empty = timeline(track("v1"))
        assertLayout(MagneticBase.insert(empty, clip("n", 0, 10), f(50)).getOrFail(), "v1", at("n", 0, 10))
        val gappy = timeline(track("v1", clip("a", 0, 100), clip("b", 120, 50)))
        assertLayout(MagneticBase.insert(gappy, clip("n", 0, 10), f(0)).getOrFail().assertBaseOk(), "v1", at("n", 0, 10), at("a", 10, 110), at("b", 110, 160))
    }

    @Test
    fun `insert rejects titles duplicates empty clips and missing base`() {
        val title = clip("t", 0, 10, asset = null).copy(title = TitleContent("x"))
        assertTrue(MagneticBase.insert(scene(), title, f(0)).errorOrFail() is EditError.TrackTypeMismatch)
        assertEquals(EditError.DuplicateClipId("a"), MagneticBase.insert(scene(), clip("a", 0, 10), f(0)).errorOrFail())
        assertTrue(MagneticBase.insert(scene(), clip("n", 0, 0), f(0)).errorOrFail() is EditError.InvalidClip)
        assertEquals(EditError.NoBaseTrack, MagneticBase.insert(timeline(track("a1", type = TrackType.AUDIO)), clip("n", 0, 10), f(0)).errorOrFail())
    }

    @Test
    fun `inserting between two clips drops a transition that no longer holds`() {
        val t = timeline(track("v1", clip("a", 0, 100), clip("b", 100, 50, srcIn = 20))).copy(transitions = listOf(Transition("tr", "a", "b", 10)))
        assertEquals(1, t.transitions.size)
        assertEquals(null, t.transitionProblem(t.transitions.single()))
        val result = MagneticBase.insert(t, clip("n", 0, 10), f(100)).getOrFail()
        assertEquals(emptyList<Transition>(), result.transitions)
        // Inserting elsewhere keeps it.
        assertEquals(1, MagneticBase.insert(t, clip("n", 0, 10), f(150)).getOrFail().transitions.size)
    }

    // endregion

    // region reorder

    @Test
    fun `moving a clip later reorders the base without gaps`() {
        val result = MagneticBase.move(scene(), "a", f(80)).getOrFail().assertBaseOk()
        // a is 100 long: its centre (130) is past the middle of b (125) and before the middle of c.
        assertLayout(result, "v1", at("b", 0, 50), at("a", 50, 150), at("c", 150, 250))
    }

    @Test
    fun `moving a clip earlier reorders the base`() {
        val result = MagneticBase.move(scene(), "c", f(0)).getOrFail().assertBaseOk()
        assertLayout(result, "v1", at("c", 0, 100), at("a", 100, 200), at("b", 200, 250))
    }

    @Test
    fun `reorder carries overlays with the footage they sit over`() {
        val t = scene(clip("onA", 10, 20), clip("onB", 100, 20), clip("onC", 200, 10))
        val result = MagneticBase.move(t, "a", f(80)).getOrFail()
        // order b,a,c: b delta -100, a delta +50, c delta 0.
        assertLayout(result, "v2", at("onB", 0, 20), at("onA", 60, 80), at("onC", 200, 210))
    }

    @Test
    fun `reorder cuts an overlay that spans footage moving by different amounts`() {
        val result = MagneticBase.move(scene(clip("x", 90, 20)), "a", f(80)).getOrFail()
        assertEquals(listOf(Triple("x~m1", 0L, 10L), Triple("x", 140L, 150L)), result.pieces("v2"))
        assertEquals(emptyList<String>(), result.invariantViolations())
        // Both pieces keep playing the same source frames, just cut apart.
        val byId = result.track("v2")!!.clips.associateBy { it.id }
        assertEquals(f(0), byId.getValue("x").sourceIn)
        assertEquals(f(10), byId.getValue("x").sourceOut)
        assertEquals(f(10), byId.getValue("x~m1").sourceIn)
        assertEquals(f(20), byId.getValue("x~m1").sourceOut)
    }

    @Test
    fun `an overlay past the end of the base does not move on reorder`() {
        val result = MagneticBase.move(scene(clip("tail", 260, 10)), "a", f(80)).getOrFail()
        assertLayout(result, "v2", at("tail", 260, 270))
    }

    @Test
    fun `dropping a clip where it already is changes nothing`() {
        val t = scene(clip("x", 10, 10))
        assertEquals(t, MagneticBase.move(t, "b", f(105)).getOrFail())
    }

    @Test
    fun `a base clip cannot be dragged to another track`() {
        val t = timeline(track("v2"), track("v1", clip("a", 0, 100)))
        assertEquals(EditError.BaseClipCannotLeave("a"), MagneticBase.move(t, "a", f(0), toTrackId = "v2").errorOrFail())
    }

    @Test
    fun `an overlay dropped on the base is inserted there and its old lane keeps a gap`() {
        val t = scene(clip("x", 10, 20), clip("y", 40, 10))
        val result = MagneticBase.move(t, "x", f(100), toTrackId = "v1").getOrFail().assertBaseOk()
        assertLayout(result, "v1", at("a", 0, 100), at("x", 100, 120), at("b", 120, 170), at("c", 170, 270))
        assertLayout(result, "v2", at("y", 40, 50))
    }

    @Test
    fun `overlays move freely between overlay tracks`() {
        val t = timeline(track("v3", clip("p", 0, 10)), track("v2", clip("x", 50, 10)), track("v1", clip("a", 0, 100)))
        val result = MagneticBase.move(t, "x", f(20), toTrackId = "v3").getOrFail()
        assertLayout(result, "v3", at("p", 0, 10), at("x", 20, 30))
        assertEquals(t.track("v1"), result.track("v1"))
    }

    @Test
    fun `an audio clip moves freely and never touches the base`() {
        val t = timeline(track("a1", clip("m", 0, 50), type = TrackType.AUDIO), track("v1", clip("a", 0, 100)))
        val result = MagneticBase.move(t, "m", f(30)).getOrFail()
        assertLayout(result, "a1", at("m", 30, 80))
        assertEquals(t.track("v1"), result.track("v1"))
    }

    @Test
    fun `reorder closes legacy gaps first`() {
        val t = timeline(track("v1", clip("a", 0, 100), clip("b", 120, 50)))
        val result = MagneticBase.move(t, "a", f(100)).getOrFail().assertBaseOk()
        assertLayout(result, "v1", at("b", 0, 50), at("a", 50, 150))
    }

    // endregion

    // region trim

    @Test
    fun `extending the end of a base clip ripples the base and the overlays after it`() {
        val t = scene(clip("cross", 90, 30), clip("after", 160, 10))
        val result = MagneticBase.trim(t, "a", TrimEdge.END, f(130)).getOrFail().assertBaseOk()
        assertLayout(result, "v1", at("a", 0, 130), at("b", 130, 180), at("c", 180, 280))
        // "cross" starts before the old end (100) and stays; "after" shifts with the footage.
        assertLayout(result, "v2", at("cross", 90, 120), at("after", 190, 200))
        assertEquals(f(130), result.track("v1")!!.clips.first().sourceOut)
    }

    @Test
    fun `shortening the end removes the overlay frames and pulls later footage in`() {
        val t = scene(clip("inside", 75, 10), clip("cross", 90, 30), clip("after", 160, 10))
        val result = MagneticBase.trim(t, "a", TrimEdge.END, f(70)).getOrFail().assertBaseOk()
        assertLayout(result, "v1", at("a", 0, 70), at("b", 70, 120), at("c", 120, 220))
        // inside [70,100) is gone; "cross" keeps its part after 100, which slides to 70.
        assertLayout(result, "v2", at("cross", 70, 90), at("after", 130, 140))
    }

    @Test
    fun `shortening the start keeps the clip in place and removes its first frames`() {
        val t = scene(clip("x", 0, 40), clip("after", 160, 10))
        val result = MagneticBase.trim(t, "a", TrimEdge.START, f(30)).getOrFail().assertBaseOk()
        assertLayout(result, "v1", at("a", 0, 70), at("b", 70, 120), at("c", 120, 220))
        assertEquals(f(30), result.track("v1")!!.clips.first().sourceIn)
        // Overlay frames [0,30) went with the trimmed footage; the rest follows.
        assertLayout(result, "v2", at("x", 0, 10), at("after", 130, 140))
    }

    @Test
    fun `extending the start uses the source handle and pushes the footage right`() {
        val t = timeline(
            track("v2", clip("x", 100, 20)),
            track("v1", clip("a", 0, 100), clip("b", 100, 50, srcIn = 20)),
        )
        val result = MagneticBase.trim(t, "b", TrimEdge.START, f(80)).getOrFail().assertBaseOk()
        assertLayout(result, "v1", at("a", 0, 100), at("b", 100, 170))
        assertEquals(f(0), result.track("v1")!!.clips.last().sourceIn)
        // The overlay was over the start of b's old footage, so it moves 20 right with it.
        assertLayout(result, "v2", at("x", 120, 140))
    }

    @Test
    fun `base trims fail cleanly and leave the timeline unchanged`() {
        val t = scene()
        assertTrue(MagneticBase.trim(t, "a", TrimEdge.END, f(0)).errorOrFail() is EditError.InvalidTrim)
        assertEquals(EditError.SourceOutOfRange, MagneticBase.trim(t, "a", TrimEdge.END, f(150), sourceLength = 120).errorOrFail())
        assertEquals(EditError.NegativeStart, MagneticBase.trim(t, "a", TrimEdge.START, f(-10)).errorOrFail())
        assertEquals(EditError.ClipNotFound("zz"), MagneticBase.trim(t, "zz", TrimEdge.END, f(10)).errorOrFail())
    }

    @Test
    fun `a trim that changes nothing returns the same timeline`() {
        val t = scene()
        assertEquals(t, MagneticBase.trim(t, "b", TrimEdge.END, f(150)).getOrFail())
    }

    @Test
    fun `overlay trims keep the free-form rules and never touch the base`() {
        val t = scene(clip("x", 10, 20), clip("y", 40, 20))
        assertTrue(MagneticBase.trim(t, "x", TrimEdge.END, f(45)).errorOrFail() is EditError.Overlap)
        val result = MagneticBase.trim(t, "x", TrimEdge.END, f(35)).getOrFail()
        assertLayout(result, "v2", at("x", 10, 35), at("y", 40, 60))
        assertEquals(t.track("v1"), result.track("v1"))
    }

    // endregion

    @Test
    fun `split on the base moves nothing`() {
        val t = scene(clip("x", 90, 30))
        val result = TimelineOps.split(t, "v1", f(60), "a2").getOrFail().assertBaseOk()
        assertLayout(result, "v1", at("a", 0, 60), at("a2", 60, 100), at("b", 100, 150), at("c", 150, 250))
        assertEquals(t.track("v2"), result.track("v2"))
    }

    @Test
    fun `commands are single undo steps that restore the exact timeline`() {
        val start = scene(clip("x", 90, 30))
        var history = EditHistory(start, limit = 50)
        val commands = listOf(
            EditCommand.InsertBase(clip("n", 0, 20), f(100)),
            EditCommand.MoveClip("a", f(150)),
            EditCommand.TrimClip("n", TrimEdge.END, f(15)),
            EditCommand.DeleteClip("b"),
        )
        val snapshots = mutableListOf(history.timeline)
        for (command in commands) {
            history = history.execute(command).getOrFail()
            snapshots += history.timeline
            assertEquals(emptyList<String>(), history.timeline.invariantViolations())
            history.timeline.assertBaseOk()
        }
        for (expected in snapshots.asReversed()) {
            assertEquals(expected, history.timeline)
            history = history.undo()
        }
    }

    @Test
    fun `base violations report gaps and a late start`() {
        assertEquals(emptyList<String>(), MagneticBase.baseViolations(scene()))
        assertEquals(1, MagneticBase.baseViolations(timeline(track("v1", clip("a", 5, 10)))).size)
        assertEquals(1, MagneticBase.baseViolations(timeline(track("v1", clip("a", 0, 10), clip("b", 12, 10)))).size)
    }
}
