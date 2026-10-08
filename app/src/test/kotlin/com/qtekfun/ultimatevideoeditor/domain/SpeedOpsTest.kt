package com.qtekfun.ultimatevideoeditor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedOpsTest {

    private val base = timeline(track("v1", clip("a", 0, 100), clip("b", 150, 60, srcIn = 10)))

    private fun Timeline.clip(id: String): Clip = checkNotNull(trackOfClip(id)?.clip(id))

    // ---- setSpeed ----

    @Test
    fun `double speed halves the clip and keeps its source range`() {
        val out = TimelineOps.setSpeed(base, "a", 2, 1).getOrFail()
        assertLayout(out, "v1", at("a", 0, 50), at("b", 150, 210))
        assertEquals(f(0), out.clip("a").sourceIn)
        assertEquals(f(100), out.clip("a").sourceOut)
        assertEquals(50L, out.clip("a").retimedFrames)
    }

    @Test
    fun `half speed doubles the clip and leaves a later clip alone when there is room`() {
        val roomy = timeline(track("v1", clip("a", 0, 100), clip("b", 300, 60)))
        val out = TimelineOps.setSpeed(roomy, "a", 1, 2).getOrFail()
        assertLayout(out, "v1", at("a", 0, 200), at("b", 300, 360))
    }

    @Test
    fun `slowing a clip into the next one is an overlap unless it ripples`() {
        val error = TimelineOps.setSpeed(base, "a", 1, 2).errorOrFail()
        assertEquals(EditError.Overlap("b"), error)
        val out = TimelineOps.setSpeed(base, "a", 1, 2, ripple = true).getOrFail()
        assertLayout(out, "v1", at("a", 0, 200), at("b", 250, 310))
    }

    @Test
    fun `speeding up with ripple closes the gap after the clip`() {
        val out = TimelineOps.setSpeed(base, "a", 4, 1, ripple = true).getOrFail()
        assertLayout(out, "v1", at("a", 0, 25), at("b", 75, 135))
    }

    @Test
    fun `speeding up without ripple leaves a gap`() {
        val out = TimelineOps.setSpeed(base, "a", 4, 1).getOrFail()
        assertLayout(out, "v1", at("a", 0, 25), at("b", 150, 210))
    }

    @Test
    fun `returning to normal speed clears the retime`() {
        val fast = TimelineOps.setSpeed(base, "a", 2, 1).getOrFail()
        val normal = TimelineOps.setSpeed(fast, "a", 1, 1).getOrFail()
        assertNull(normal.clip("a").retimedFrames)
        assertFalse(normal.clip("a").isRetimed)
        assertEquals(base, normal)
    }

    @Test
    fun `speed is computed from the range with rounding to whole frames`() {
        val out = TimelineOps.setSpeed(timeline(track("v1", clip("a", 0, 100))), "a", 3, 1).getOrFail()
        assertEquals(33L, out.clip("a").durationFrames) // 100 / 3 = 33.3
        val slow = TimelineOps.setSpeed(timeline(track("v1", clip("a", 0, 100))), "a", 3, 2).getOrFail()
        assertEquals(67L, slow.clip("a").durationFrames) // 66.67
        val tiny = TimelineOps.setSpeed(timeline(track("v1", clip("a", 0, 3))), "a", 8, 1).getOrFail()
        assertEquals(1L, tiny.clip("a").durationFrames) // never shorter than a frame
    }

    @Test
    fun `speeds outside 0 point 1x to 100x are rejected`() {
        assertTrue(TimelineOps.setSpeed(base, "a", 101, 1).errorOrFail() is EditError.InvalidSpeed)
        assertTrue(TimelineOps.setSpeed(base, "a", 1, 11).errorOrFail() is EditError.InvalidSpeed)
        assertTrue(TimelineOps.setSpeed(base, "a", 0, 1).errorOrFail() is EditError.InvalidSpeed)
    }

    @Test
    fun `titles and one frame clips cannot change speed`() {
        val title = Clip("t", null, f(0), f(0), f(30), title = TitleContent("hi"))
        val withTitle = timeline(Track("t1", TrackType.TITLE, listOf(title)))
        assertTrue(TimelineOps.setSpeed(withTitle, "t", 2, 1).errorOrFail() is EditError.InvalidSpeed)
        assertTrue(TimelineOps.setSpeed(timeline(track("v1", clip("a", 0, 1))), "a", 2, 1).errorOrFail() is EditError.InvalidSpeed)
        assertEquals(EditError.ClipNotFound("zz"), TimelineOps.setSpeed(base, "zz", 2, 1).errorOrFail())
    }

    @Test
    fun `speed changes stretch keyframes and ramps with the clip`() {
        val pose = ClipTransform(scaleX = 2.0, scaleY = 2.0)
        val animated = Clip(
            "a", "a", f(0), f(0), f(100),
            keyframes = listOf(Keyframe(0, ClipTransform.IDENTITY), Keyframe(50, pose), Keyframe(99, ClipTransform.IDENTITY)),
            speedRamp = listOf(SpeedKey(0, 500), SpeedKey(99, 1500)),
        )
        val out = TimelineOps.setSpeed(timeline(track("v1", animated)), "a", 2, 1).getOrFail()
        val clip = out.clip("a")
        assertEquals(50L, clip.durationFrames)
        assertEquals(listOf(0L, 25L, 49L), clip.keyframes.map { it.frame })
        assertEquals(listOf(0L, 49L), clip.speedRamp.map { it.frame })
        assertTrue(out.invariantViolations().isEmpty())
    }

    @Test
    fun `a speed change keeps a transition that still has media`() {
        val tl = Timeline(
            listOf(track("v1", clip("a", 0, 100, srcIn = 0), clip("b", 100, 100, srcIn = 30))),
            listOf(Transition("x", "a", "b", 20)),
        )
        val slowed = TimelineOps.setSpeed(tl, "b", 1, 2, ripple = true).getOrFail()
        assertTrue(slowed.invariantViolations().isEmpty())
        assertEquals(1, slowed.transitions.size)
    }

    // ---- setReverse ----

    @Test
    fun `reverse flips the clip in place`() {
        val out = TimelineOps.setReverse(base, "a", true).getOrFail()
        assertLayout(out, "v1", at("a", 0, 100), at("b", 150, 210))
        assertTrue(out.clip("a").reverse)
        assertEquals(99L, out.clip("a").retime.sourceFrameAt(0))
        val back = TimelineOps.setReverse(out, "a", false).getOrFail()
        assertEquals(base, back)
    }

    @Test
    fun `a title cannot be reversed`() {
        val title = Clip("t", null, f(0), f(0), f(30), title = TitleContent("hi"))
        val withTitle = timeline(Track("t1", TrackType.TITLE, listOf(title)))
        assertTrue(TimelineOps.setReverse(withTitle, "t", true).errorOrFail() is EditError.InvalidSpeed)
    }

    // ---- setSpeedRamp ----

    @Test
    fun `a ramp keeps the clip's place and length`() {
        val out = TimelineOps.setSpeedRamp(base, "a", SpeedRamps.easeIn(100)).getOrFail()
        assertLayout(out, "v1", at("a", 0, 100), at("b", 150, 210))
        assertEquals(5, out.clip("a").speedRamp.size)
        assertTrue(out.clip("a").isRetimed)
        val cleared = TimelineOps.setSpeedRamp(out, "a", emptyList()).getOrFail()
        assertEquals(base, cleared)
    }

    @Test
    fun `invalid ramps are rejected`() {
        assertTrue(TimelineOps.setSpeedRamp(base, "a", listOf(SpeedKey(100, 1000))).errorOrFail() is EditError.InvalidSpeed)
        assertTrue(TimelineOps.setSpeedRamp(base, "a", listOf(SpeedKey(0, 5))).errorOrFail() is EditError.InvalidSpeed)
        assertTrue(TimelineOps.setSpeedRamp(timeline(track("v1", clip("a", 0, 1))), "a", listOf(SpeedKey(0, 1000))).errorOrFail() is EditError.InvalidSpeed)
    }

    // ---- split / trim / move / overwrite / ripple with speed ----

    @Test
    fun `splitting a fast clip tiles the source and the timeline`() {
        val fast = TimelineOps.setSpeed(base, "a", 2, 1).getOrFail()
        val out = TimelineOps.split(fast, "v1", f(20), "a2").getOrFail()
        assertLayout(out, "v1", at("a", 0, 20), at("a2", 20, 50), at("b", 150, 210))
        assertEquals(f(0), out.clip("a").sourceIn)
        assertEquals(out.clip("a").sourceOut, out.clip("a2").sourceIn)
        assertEquals(f(100), out.clip("a2").sourceOut)
        assertEquals(40L, out.clip("a").sourceSpan)
        assertEquals(60L, out.clip("a2").sourceSpan)
    }

    @Test
    fun `splitting a reversed clip puts the later part of the timeline on the lower source`() {
        val reversed = TimelineOps.setReverse(base, "a", true).getOrFail()
        val out = TimelineOps.split(reversed, "v1", f(30), "a2").getOrFail()
        assertEquals(f(70), out.clip("a").sourceIn)
        assertEquals(f(100), out.clip("a").sourceOut)
        assertEquals(f(0), out.clip("a2").sourceIn)
        assertEquals(f(70), out.clip("a2").sourceOut)
        assertTrue(out.clip("a").reverse && out.clip("a2").reverse)
        // Frames before and after the cut are what the unsplit clip showed.
        assertEquals(reversed.clip("a").retime.sourceFrameAt(29), out.clip("a").retime.sourceFrameAt(29))
        assertEquals(reversed.clip("a").retime.sourceFrameAt(30), out.clip("a2").retime.sourceFrameAt(0))
    }

    @Test
    fun `trimming the start of a fast clip removes source at the clip's speed`() {
        val fast = TimelineOps.setSpeed(base, "a", 2, 1).getOrFail()
        val out = TimelineOps.trim(fast, "a", TrimEdge.START, f(10)).getOrFail()
        val a = out.clip("a")
        assertEquals(f(10), a.timelineStart)
        assertEquals(40L, a.durationFrames)
        assertEquals(f(20), a.sourceIn)
        assertEquals(f(100), a.sourceOut)
    }

    @Test
    fun `trimming the end of a slow clip keeps the speed`() {
        val slow = TimelineOps.setSpeed(timeline(track("v1", clip("a", 0, 50))), "a", 1, 2).getOrFail()
        val out = TimelineOps.trim(slow, "a", TrimEdge.END, f(60)).getOrFail()
        val a = out.clip("a")
        assertEquals(60L, a.durationFrames)
        assertEquals(30L, a.sourceSpan)
        assertEquals(0.5, a.speed, 1e-9)
    }

    @Test
    fun `extending a fast clip needs source at its speed`() {
        val tl = timeline(track("v1", clip("a", 0, 100)))
        val fast = TimelineOps.setSpeed(tl, "a", 2, 1).getOrFail()
        assertEquals(EditError.SourceOutOfRange, TimelineOps.trim(fast, "a", TrimEdge.END, f(60), sourceLength = 110).errorOrFail())
        val ok = TimelineOps.trim(fast, "a", TrimEdge.END, f(60), sourceLength = 120).getOrFail()
        assertEquals(120L, ok.clip("a").sourceSpan)
    }

    @Test
    fun `extending a reversed clip at its end reads lower source frames`() {
        val tl = timeline(track("v1", clip("a", 0, 50, srcIn = 40)))
        val reversed = TimelineOps.setReverse(tl, "a", true).getOrFail()
        val out = TimelineOps.trim(reversed, "a", TrimEdge.END, f(70)).getOrFail()
        assertEquals(f(20), out.clip("a").sourceIn)
        assertEquals(f(90), out.clip("a").sourceOut)
        assertEquals(EditError.SourceOutOfRange, TimelineOps.trim(reversed, "a", TrimEdge.END, f(100)).errorOrFail())
    }

    @Test
    fun `a trim that would empty a retimed clip fails`() {
        val fast = TimelineOps.setSpeed(base, "a", 2, 1).getOrFail()
        assertTrue(TimelineOps.trim(fast, "a", TrimEdge.START, f(50)).errorOrFail() is EditError.InvalidTrim)
        assertTrue(TimelineOps.trim(fast, "a", TrimEdge.END, f(0)).errorOrFail() is EditError.InvalidTrim)
    }

    @Test
    fun `moving a retimed clip uses its timeline length for collisions`() {
        val fast = TimelineOps.setSpeed(base, "a", 2, 1).getOrFail() // a is 50 long
        val moved = TimelineOps.move(fast, "a", f(100)).getOrFail()
        assertLayout(moved, "v1", at("a", 100, 150), at("b", 150, 210))
        assertEquals(EditError.Overlap("b"), TimelineOps.move(fast, "a", f(101)).errorOrFail())
    }

    @Test
    fun `ripple delete shifts by the timeline length of a retimed clip`() {
        val fast = TimelineOps.setSpeed(base, "a", 2, 1).getOrFail()
        val out = TimelineOps.rippleDelete(fast, "a").getOrFail()
        assertLayout(out, "v1", at("b", 100, 160))
    }

    @Test
    fun `overwrite onto a retimed clip trims and splits it along the timeline`() {
        val fast = TimelineOps.setSpeed(base, "a", 2, 1).getOrFail() // a: 0..50 showing source 0..100
        val out = TimelineOps.overwrite(fast, "v1", clip("n", 10, 20, asset = "n")).getOrFail()
        assertLayout(out, "v1", at("a", 0, 10), at("n", 10, 30), at("a~n", 30, 50), at("b", 150, 210))
        assertEquals(f(20), out.clip("a").sourceOut)
        assertEquals(f(60), out.clip("a~n").sourceIn)
        assertEquals(f(100), out.clip("a~n").sourceOut)
    }

    @Test
    fun `overwrite rejects a clip with an invalid retime`() {
        val bad = Clip("n", "a", f(0), f(0), f(10), speedRamp = listOf(SpeedKey(50, 1000)))
        assertTrue(TimelineOps.overwrite(timeline(track("v1")), "v1", bad).errorOrFail() is EditError.InvalidClip)
        val sameLength = Clip("n", "a", f(0), f(0), f(10), retimedFrames = 10)
        assertTrue(TimelineOps.overwrite(timeline(track("v1")), "v1", sameLength).errorOrFail() is EditError.InvalidClip)
    }

    // ---- freeze frame ----

    @Test
    fun `a freeze frame splits the clip and holds one frame in between`() {
        val out = TimelineOps.freezeFrame(base, "v1", f(40), 30, "fz", "a2").getOrFail()
        assertLayout(out, "v1", at("a", 0, 40), at("fz", 40, 70), at("a2", 70, 130), at("b", 180, 240))
        val still = out.clip("fz")
        assertTrue(still.isFreeze)
        assertEquals(f(40), still.sourceIn)
        assertEquals(f(41), still.sourceOut)
        assertEquals(30L, still.durationFrames)
        assertEquals(f(40), out.clip("a").sourceOut)
        assertEquals(f(40), out.clip("a2").sourceIn)
        assertEquals(f(100), out.clip("a2").sourceOut)
    }

    @Test
    fun `a freeze at a clip's first frame inserts before it without splitting`() {
        val out = TimelineOps.freezeFrame(base, "v1", f(0), 10, "fz", "unused").getOrFail()
        assertLayout(out, "v1", at("fz", 0, 10), at("a", 10, 110), at("b", 160, 220))
        assertEquals(f(0), out.clip("fz").sourceIn)
    }

    @Test
    fun `freezing a fast reversed clip holds the frame the clip shows there`() {
        val fastReverse = TimelineOps.setReverse(TimelineOps.setSpeed(base, "a", 2, 1).getOrFail(), "a", true).getOrFail()
        val shown = fastReverse.clip("a").retime.sourceFrameAt(10)
        val out = TimelineOps.freezeFrame(fastReverse, "v1", f(10), 5, "fz", "a2").getOrFail()
        assertEquals(f(shown), out.clip("fz").sourceIn)
        assertTrue(out.invariantViolations().isEmpty())
    }

    @Test
    fun `a freeze keeps the clip's pose at that moment`() {
        val pose = ClipTransform(positionX = 50.0, opacity = 0.5)
        val animated = Clip("a", "a", f(0), f(0), f(100), keyframes = listOf(Keyframe(0, ClipTransform.IDENTITY), Keyframe(99, pose)))
        val out = TimelineOps.freezeFrame(timeline(track("v1", animated)), "v1", f(50), 20, "fz", "a2").getOrFail()
        assertEquals(animated.transformAt(50), out.clip("fz").transform)
        assertTrue(out.clip("fz").keyframes.isEmpty())
    }

    @Test
    fun `a freeze carries the outgoing transition to the second half`() {
        val tl = Timeline(
            listOf(track("v1", clip("a", 0, 100), clip("b", 100, 100, srcIn = 30))),
            listOf(Transition("x", "a", "b", 20)),
        )
        val out = TimelineOps.freezeFrame(tl, "v1", f(60), 10, "fz", "a2").getOrFail()
        assertEquals(listOf("a2"), out.transitions.map { it.fromClipId })
        assertTrue(out.invariantViolations().isEmpty())
    }

    @Test
    fun `freeze frame errors`() {
        assertEquals(EditError.SplitOutsideClip, TimelineOps.freezeFrame(base, "v1", f(120), 10, "fz", "a2").errorOrFail())
        assertEquals(EditError.TrackNotFound("zz"), TimelineOps.freezeFrame(base, "zz", f(10), 10, "fz", "a2").errorOrFail())
        assertTrue(TimelineOps.freezeFrame(base, "v1", f(10), 0, "fz", "a2").errorOrFail() is EditError.InvalidClip)
        assertEquals(EditError.DuplicateClipId("b"), TimelineOps.freezeFrame(base, "v1", f(10), 10, "b", "a2").errorOrFail())
        assertEquals(EditError.DuplicateClipId("b"), TimelineOps.freezeFrame(base, "v1", f(10), 10, "fz", "b").errorOrFail())
        val audio = timeline(track("a1", clip("s", 0, 50), type = TrackType.AUDIO))
        assertTrue(TimelineOps.freezeFrame(audio, "a1", f(10), 10, "fz", "a2").errorOrFail() is EditError.InvalidClip)
    }

    // ---- history ----

    @Test
    fun `speed edits undo and redo exactly`() {
        var history = EditHistory(base)
        history = (history.execute(EditCommand.SetSpeed("a", 2, 1)) as EditResult.Success).value
        history = (history.execute(EditCommand.SetReverse("a", true)) as EditResult.Success).value
        history = (history.execute(EditCommand.SetSpeedRamp("a", SpeedRamps.bell(50))) as EditResult.Success).value
        history = (history.execute(EditCommand.FreezeFrame("v1", f(10), 5, "fz", "a2")) as EditResult.Success).value
        val states = ArrayList<Timeline>()
        var cursor = history
        while (cursor.canUndo) {
            states += cursor.timeline
            cursor = cursor.undo()
        }
        assertEquals(base, cursor.timeline)
        var redone = cursor
        for (expected in states.asReversed()) {
            redone = redone.redo()
            assertEquals(expected, redone.timeline)
        }
        assertFalse(redone.canRedo)
    }

    // ---- transitions with retimed clips ----

    @Test
    fun `a transition's handle needs source at the clip's speed`() {
        // a plays source 0..100 at 2x (50 timeline frames). Its transition runs 10 frames past the cut,
        // which is 20 source frames past frame 99.
        val tl = Timeline(
            listOf(track("v1", clip("a", 0, 100), clip("b", 100, 60, srcIn = 40))),
            emptyList(),
        )
        val fast = TimelineOps.setSpeed(tl, "a", 2, 1, ripple = true).getOrFail()
        assertTrue(TimelineOps.addTransition(fast, Transition("x", "a", "b", 20), outgoingSourceLength = 118).errorOrFail() is EditError.InvalidTransition)
        TimelineOps.addTransition(fast, Transition("x", "a", "b", 20), outgoingSourceLength = 125).getOrFail()
    }

    @Test
    fun `a transition before a reversed incoming clip needs media past its end`() {
        val tl = Timeline(
            listOf(track("v1", clip("a", 0, 50), clip("b", 50, 50, srcIn = 10))),
            emptyList(),
        )
        val reversed = TimelineOps.setReverse(tl, "b", true).getOrFail()
        // b reversed plays source 59 down to 10; before its first frame it would show 60+, which exists.
        TimelineOps.addTransition(reversed, Transition("x", "a", "b", 10)).getOrFail()
    }

    @Test
    fun `the render plan maps extended clips through the retime`() {
        val tl = Timeline(
            listOf(track("v1", clip("a", 0, 100), clip("b", 100, 100, srcIn = 40))),
            emptyList(),
        )
        val fast = TimelineOps.setSpeed(tl, "a", 2, 1, ripple = true).getOrFail()
        val withTransition = TimelineOps.addTransition(fast, Transition("x", "a", "b", 10), outgoingSourceLength = 200).getOrFail()
        val clips = withTransition.renderClips().associateBy { it.clipId }
        val a = checkNotNull(clips["a"])
        // a keeps playing 5 frames past its end at 2x: source frame 100 + 2*(frame - 50).
        assertEquals(0L, a.sourceFrameAt(0))
        assertEquals(98L, a.sourceFrameAt(49))
        assertEquals(100L, a.sourceFrameAt(50))
        assertEquals(108L, a.sourceFrameAt(54))
        val b = checkNotNull(clips["b"])
        assertEquals(35L, b.sourceFrameAt(45)) // starts 5 frames early
        assertEquals(40L, b.sourceFrameAt(50))
        assertFalse(b.isReverse)
    }
}
