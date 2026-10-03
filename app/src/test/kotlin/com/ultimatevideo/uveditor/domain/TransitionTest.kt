package com.ultimatevideo.uveditor.domain

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitionTest {

    /** A [0,100) reading source 0..100, B [100,200) reading source 50..150, C [200,300) reading 50..150. */
    private fun base() = timeline(
        track("v1", clip("A", 0, 100), clip("B", 100, 100, srcIn = 50), clip("C", 200, 100, srcIn = 50)),
    )

    private val ab = Transition("t1", "A", "B", 10)

    @Test
    fun `a transition is centred on the cut and does not move clips`() {
        val result = TimelineOps.addTransition(base(), ab, outgoingSourceLength = 300).getOrFail()

        assertLayout(result, "v1", at("A", 0, 100), at("B", 100, 200), at("C", 200, 300))
        assertEquals(95L until 105L, result.regionOf(ab))
        assertEquals(f(100), result.cutOf(ab))
        assertEquals(ab, result.transitionBetween("A", "B"))
    }

    @Test
    fun `an odd duration gives the extra frame to the incoming side`() {
        val odd = Transition("t1", "A", "B", 9)

        assertEquals(4, odd.preFrames)
        assertEquals(5, odd.postFrames)
        assertEquals(96L until 105L, TimelineOps.addTransition(base(), odd).getOrFail().regionOf(odd))
    }

    @Test
    fun `clips must be adjacent on the same track`() {
        val gap = timeline(track("v1", clip("A", 0, 100), clip("B", 120, 100, srcIn = 50)))
        val otherTrack = timeline(track("v1", clip("A", 0, 100)), track("v2", clip("B", 100, 100, srcIn = 50)))
        val reversed = Transition("t1", "B", "A", 10)

        assertTrue(TimelineOps.addTransition(gap, ab).errorOrFail() is EditError.InvalidTransition)
        assertTrue(TimelineOps.addTransition(otherTrack, ab).errorOrFail() is EditError.InvalidTransition)
        assertTrue(TimelineOps.addTransition(base(), reversed).errorOrFail() is EditError.InvalidTransition)
    }

    @Test
    fun `unknown clips and duplicate ids and pairs are rejected`() {
        val withOne = TimelineOps.addTransition(base(), ab).getOrFail()

        assertTrue(TimelineOps.addTransition(base(), Transition("t1", "A", "nope", 10)).errorOrFail() is EditError.InvalidTransition)
        assertEquals(EditError.DuplicateTransitionId("t1"), TimelineOps.addTransition(withOne, Transition("t1", "B", "C", 10)).errorOrFail())
        assertTrue(TimelineOps.addTransition(withOne, Transition("t2", "A", "B", 6)).errorOrFail() is EditError.InvalidTransition)
    }

    @Test
    fun `too short and too long durations are rejected`() {
        val shortClips = timeline(track("v1", clip("A", 0, 4), clip("B", 4, 4, srcIn = 50)))

        assertTrue(TimelineOps.addTransition(base(), Transition("t1", "A", "B", 1)).errorOrFail() is EditError.InvalidTransition)
        assertTrue(TimelineOps.addTransition(base(), Transition("t1", "A", "B", 0)).errorOrFail() is EditError.InvalidTransition)
        assertTrue(TimelineOps.addTransition(shortClips, Transition("t1", "A", "B", 10)).errorOrFail() is EditError.InvalidTransition)
        TimelineOps.addTransition(shortClips, Transition("t1", "A", "B", 8)).getOrFail()
    }

    @Test
    fun `the incoming clip needs media before its in point`() {
        val noHandle = timeline(track("v1", clip("A", 0, 100), clip("B", 100, 100, srcIn = 3)))

        assertTrue(TimelineOps.addTransition(noHandle, ab).errorOrFail() is EditError.InvalidTransition)
        TimelineOps.addTransition(noHandle, Transition("t1", "A", "B", 6)).getOrFail()
    }

    @Test
    fun `the outgoing clip needs media after its out point`() {
        // A reads source 0..100 of a 102-frame asset: only 2 spare frames, the transition needs 5.
        assertTrue(TimelineOps.addTransition(base(), ab, outgoingSourceLength = 102).errorOrFail() is EditError.InvalidTransition)
        TimelineOps.addTransition(base(), ab, outgoingSourceLength = 105).getOrFail()
    }

    @Test
    fun `duration changes are bounded like additions`() {
        val withOne = TimelineOps.addTransition(base(), ab).getOrFail()

        val longer = TimelineOps.setTransitionDuration(withOne, "t1", 40).getOrFail()
        assertEquals(40, longer.transition("t1")!!.durationFrames)
        assertTrue(TimelineOps.setTransitionDuration(withOne, "t1", 500).errorOrFail() is EditError.InvalidTransition)
        assertTrue(TimelineOps.setTransitionDuration(withOne, "t1", 1).errorOrFail() is EditError.InvalidTransition)
        assertTrue(TimelineOps.setTransitionDuration(withOne, "t1", 20, outgoingSourceLength = 105).errorOrFail() is EditError.InvalidTransition)
        assertEquals(EditError.TransitionNotFound("zz"), TimelineOps.setTransitionDuration(withOne, "zz", 10).errorOrFail())
    }

    @Test
    fun `remove deletes the transition and only it`() {
        val two = TimelineOps.addTransition(
            TimelineOps.addTransition(base(), ab).getOrFail(),
            Transition("t2", "B", "C", 10),
        ).getOrFail()

        val result = TimelineOps.removeTransition(two, "t1").getOrFail()

        assertNull(result.transition("t1"))
        assertNotNull(result.transition("t2"))
        assertEquals(EditError.TransitionNotFound("t1"), TimelineOps.removeTransition(result, "t1").errorOrFail())
    }

    @Test
    fun `two transitions on one clip must not overlap each other`() {
        val tight = timeline(
            track("v1", clip("A", 0, 100), clip("B", 100, 8, srcIn = 50), clip("C", 108, 100, srcIn = 50)),
        )
        val first = TimelineOps.addTransition(tight, Transition("t1", "A", "B", 8)).getOrFail()

        // B is 8 frames long: its incoming half takes 4, so an outgoing transition may take at most 4 before it.
        assertTrue(TimelineOps.addTransition(first, Transition("t2", "B", "C", 10)).errorOrFail() is EditError.InvalidTransition)
        TimelineOps.addTransition(first, Transition("t2", "B", "C", 6)).getOrFail()
    }

    @Test
    fun `titles can crossfade without any media handle`() {
        fun title(id: String, start: Long) = Clip(id, null, f(start), f(0), f(30), title = TitleContent("x"))
        val titles = timeline(track("t1", title("T1", 0), title("T2", 30), type = TrackType.TITLE))

        val result = TimelineOps.addTransition(titles, Transition("x", "T1", "T2", 12)).getOrFail()

        assertEquals(24L until 36L, result.regionOf(result.transition("x")!!))
    }

    // --- interactions with other edits -------------------------------------------------------

    private fun withAb() = TimelineOps.addTransition(base(), ab).getOrFail()

    @Test
    fun `splitting the outgoing clip hands the transition to its right half`() {
        val result = TimelineOps.split(withAb(), "v1", f(50), "A2").getOrFail()

        assertLayout(result, "v1", at("A", 0, 50), at("A2", 50, 100), at("B", 100, 200), at("C", 200, 300))
        assertEquals("A2", result.transitions.single().fromClipId)
        assertEquals("B", result.transitions.single().toClipId)
    }

    @Test
    fun `splitting the incoming clip keeps the transition on its left half`() {
        val result = TimelineOps.split(withAb(), "v1", f(150), "B2").getOrFail()

        assertEquals("B", result.transitions.single().toClipId)
        assertTrue(result.invariantViolations().isEmpty())
    }

    @Test
    fun `splitting so the outgoing half is too short drops the transition`() {
        val result = TimelineOps.split(withAb(), "v1", f(97), "A2").getOrFail()

        // A2 is 3 frames long, shorter than the 5 frames the transition reaches back.
        assertTrue(result.transitions.isEmpty())
        assertTrue(result.invariantViolations().isEmpty())
    }

    @Test
    fun `trimming the far edges keeps a transition that still fits`() {
        val trimmedA = TimelineOps.trim(withAb(), "A", TrimEdge.START, f(20)).getOrFail()
        val trimmedB = TimelineOps.trim(trimmedA, "B", TrimEdge.END, f(180)).getOrFail()

        assertEquals(1, trimmedB.transitions.size)
    }

    @Test
    fun `trimming the far edge until the transition does not fit drops it`() {
        val result = TimelineOps.trim(withAb(), "A", TrimEdge.START, f(97)).getOrFail()

        assertTrue(result.transitions.isEmpty())
    }

    @Test
    fun `trimming the cut edge opens a gap and drops the transition`() {
        val shorter = TimelineOps.trim(withAb(), "A", TrimEdge.END, f(90)).getOrFail()
        val later = TimelineOps.trim(withAb(), "B", TrimEdge.START, f(110)).getOrFail()

        assertTrue(shorter.transitions.isEmpty())
        assertTrue(later.transitions.isEmpty())
    }

    @Test
    fun `extending the outgoing clip over its neighbour still collides`() {
        assertTrue(TimelineOps.trim(withAb(), "A", TrimEdge.END, f(110)).errorOrFail() is EditError.Overlap)
    }

    @Test
    fun `moving a clip away drops its transitions and moving along keeps none stale`() {
        val moved = TimelineOps.move(withAb(), "B", f(400)).getOrFail()

        assertTrue(moved.transitions.isEmpty())
        assertTrue(moved.invariantViolations().isEmpty())
    }

    @Test
    fun `moving a clip within its own slot keeps the transition`() {
        val same = TimelineOps.move(withAb(), "B", f(100)).getOrFail()

        assertEquals(1, same.transitions.size)
    }

    @Test
    fun `ripple delete of an earlier clip shifts the pair and keeps the transition`() {
        val withEarlier = timeline(
            track("v1", clip("Z", 0, 50), clip("A", 50, 100), clip("B", 150, 100, srcIn = 50)),
        )
        val withT = TimelineOps.addTransition(withEarlier, ab).getOrFail()

        val result = TimelineOps.rippleDelete(withT, "Z").getOrFail()

        assertEquals(1, result.transitions.size)
        assertEquals(95L until 105L, result.regionOf(result.transitions.single()))
    }

    @Test
    fun `ripple delete of a transition clip drops the transition`() {
        assertTrue(TimelineOps.rippleDelete(withAb(), "A").getOrFail().transitions.isEmpty())
        assertTrue(TimelineOps.rippleDelete(withAb(), "B").getOrFail().transitions.isEmpty())
    }

    @Test
    fun `ripple append that closes a gap before a clip leaves valid transitions only`() {
        val gap = timeline(track("v1", clip("A", 0, 100), clip("B", 100, 100, srcIn = 50), clip("C", 260, 40, srcIn = 50)))
        val withT = TimelineOps.addTransition(gap, ab).getOrFail()

        val result = TimelineOps.rippleAppend(withT, "C").getOrFail()

        assertEquals(1, result.transitions.size)
        assertLayout(result, "v1", at("A", 0, 100), at("B", 100, 200), at("C", 200, 240))
    }

    @Test
    fun `overwriting across the cut drops the transition`() {
        val over = clip("N", 90, 30, asset = "other")

        val result = TimelineOps.overwrite(withAb(), "v1", over).getOrFail()

        assertTrue(result.transitions.isEmpty())
    }

    @Test
    fun `overwriting elsewhere keeps the transition`() {
        val far = clip("N", 205, 20, asset = "other")

        val result = TimelineOps.overwrite(withAb(), "v1", far).getOrFail()

        assertEquals(1, result.transitions.size)
    }

    @Test
    fun `undo and redo restore transitions exactly`() {
        val history = EditHistory(base())
        val added = history.execute(EditCommand.AddTransition(ab, outgoingSourceLength = 300)).getOrFail()
        val resized = added.execute(EditCommand.SetTransitionDuration("t1", 30)).getOrFail()
        val removed = resized.execute(EditCommand.RemoveTransition("t1")).getOrFail()

        assertTrue(removed.timeline.transitions.isEmpty())
        assertEquals(30, removed.undo().timeline.transition("t1")!!.durationFrames)
        assertEquals(10, removed.undo().undo().timeline.transition("t1")!!.durationFrames)
        assertTrue(removed.undo().undo().undo().timeline.transitions.isEmpty())
        assertEquals(removed.timeline, removed.undo().redo().timeline)
    }

    @Test
    fun `an edit that drops a transition is undone together with the transition`() {
        val history = EditHistory(withAb())
        val moved = history.execute(EditCommand.Move("B", f(400))).getOrFail()

        assertTrue(moved.timeline.transitions.isEmpty())
        assertEquals(1, moved.undo().timeline.transitions.size)
    }

    @Test
    fun `random edits never leave an invalid timeline and undo restores it`() {
        repeat(40) { seed ->
            val random = Random(seed)
            var history = EditHistory(base())
            var nextId = 0
            repeat(120) {
                val clips = history.timeline.tracks.flatMap { it.clips }
                val pick = clips.randomOrNull(random)
                val command: EditCommand? = when (random.nextInt(8)) {
                    0 -> pick?.let { EditCommand.Split("v1", f(random.nextLong(0, 320)), "n${nextId++}") }
                    1 -> pick?.let { EditCommand.Move(it.id, f(random.nextLong(0, 360))) }
                    2 -> pick?.let { EditCommand.Trim(it.id, TrimEdge.values().random(random), f(random.nextLong(0, 360)), 400) }
                    3 -> pick?.let { EditCommand.RippleDelete(it.id) }
                    4 -> pick?.let { EditCommand.RippleAppend(it.id) }
                    5 -> if (clips.size >= 2) {
                        val sorted = history.timeline.tracks.first().clips
                        val i = random.nextInt(sorted.size - 1)
                        EditCommand.AddTransition(
                            Transition("t${nextId++}", sorted[i].id, sorted[i + 1].id, random.nextLong(2, 40)),
                            400,
                        )
                    } else {
                        null
                    }
                    6 -> history.timeline.transitions.randomOrNull(random)?.let { EditCommand.SetTransitionDuration(it.id, random.nextLong(2, 40), 400) }
                    else -> history.timeline.transitions.randomOrNull(random)?.let { EditCommand.RemoveTransition(it.id) }
                }
                if (command != null) {
                    val before = history.timeline
                    val outcome = history.execute(command)
                    if (outcome is EditResult.Success) {
                        history = outcome.value
                        assertEquals("seed $seed ${history.timeline.invariantViolations()}", emptyList<String>(), history.timeline.invariantViolations())
                        assertEquals(before, history.undo().timeline)
                    } else {
                        assertEquals(before, history.timeline)
                    }
                }
            }
        }
    }
}
