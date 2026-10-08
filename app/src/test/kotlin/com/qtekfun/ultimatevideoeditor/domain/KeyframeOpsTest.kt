package com.qtekfun.ultimatevideoeditor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyframeOpsTest {

    private fun pose(x: Double, op: Double = 1.0) = ClipTransform(positionX = x, opacity = op)

    private fun key(frame: Long, x: Double, mode: Interpolation = Interpolation.LINEAR) = Keyframe(frame, pose(x), mode)

    /** One clip "a" of 100 frames at 50..150 with keyframes at clip frames 0, 40 and 99. */
    private fun animated(): Timeline {
        val base = timeline(track("v1", clip("a", 50, 100)))
        var t = base
        for (k in listOf(key(0, 0.0), key(40, 400.0), key(99, 990.0))) {
            t = TimelineOps.setKeyframe(t, "a", k).getOrFail()
        }
        return t
    }

    private fun Timeline.clipA(): Clip = checkNotNull(track("v1")!!.clip("a"))

    @Test
    fun `set keyframe adds and replaces`() {
        var t = animated()
        assertEquals(listOf(0L, 40L, 99L), t.clipA().keyframes.map { it.frame })

        t = TimelineOps.setKeyframe(t, "a", key(40, 111.0)).getOrFail()
        assertEquals(3, t.clipA().keyframes.size)
        assertEquals(111.0, Keyframes.at(t.clipA().keyframes, 40)!!.transform.positionX, 0.0)
        assertTrue(t.invariantViolations().isEmpty())
    }

    @Test
    fun `keyframes outside the clip or with bad poses are rejected`() {
        val t = timeline(track("v1", clip("a", 0, 100)))

        assertTrue(TimelineOps.setKeyframe(t, "a", key(-1, 0.0)).errorOrFail() is EditError.InvalidKeyframe)
        assertTrue(TimelineOps.setKeyframe(t, "a", key(100, 0.0)).errorOrFail() is EditError.InvalidKeyframe)
        assertTrue(TimelineOps.setKeyframe(t, "a", Keyframe(1, pose(0.0, op = 3.0))).errorOrFail() is EditError.InvalidKeyframe)
        assertEquals(EditError.ClipNotFound("zz"), TimelineOps.setKeyframe(t, "zz", key(0, 0.0)).errorOrFail())
    }

    @Test
    fun `audio clips cannot be animated`() {
        val t = timeline(track("a1", clip("s", 0, 100), type = TrackType.AUDIO))

        assertTrue(TimelineOps.setKeyframe(t, "s", key(0, 0.0)).errorOrFail() is EditError.InvalidKeyframe)
    }

    @Test
    fun `remove keeps the rest and the last removal freezes the pose`() {
        var t = TimelineOps.removeKeyframe(animated(), "a", 40).getOrFail()
        assertEquals(listOf(0L, 99L), t.clipA().keyframes.map { it.frame })

        t = TimelineOps.removeKeyframe(t, "a", 0).getOrFail()
        t = TimelineOps.removeKeyframe(t, "a", 99).getOrFail()
        assertTrue(t.clipA().keyframes.isEmpty())
        // The pose of the last removed keyframe stays as the fixed transform.
        assertEquals(990.0, t.clipA().transform.positionX, 0.0)
        assertEquals(EditError.KeyframeNotFound(5), TimelineOps.removeKeyframe(t, "a", 5).errorOrFail())
    }

    @Test
    fun `move keyframe replaces one at the target`() {
        var t = TimelineOps.moveKeyframe(animated(), "a", 40, 60).getOrFail()
        assertEquals(listOf(0L, 60L, 99L), t.clipA().keyframes.map { it.frame })
        assertEquals(400.0, Keyframes.at(t.clipA().keyframes, 60)!!.transform.positionX, 0.0)

        t = TimelineOps.moveKeyframe(t, "a", 60, 99).getOrFail()
        assertEquals(listOf(0L, 99L), t.clipA().keyframes.map { it.frame })
        assertTrue(TimelineOps.moveKeyframe(t, "a", 0, 100).errorOrFail() is EditError.InvalidKeyframe)
        assertEquals(EditError.KeyframeNotFound(7), TimelineOps.moveKeyframe(t, "a", 7, 8).errorOrFail())
    }

    @Test
    fun `interpolation is set per keyframe`() {
        val t = TimelineOps.setKeyframeInterpolation(animated(), "a", 40, Interpolation.HOLD).getOrFail()

        assertEquals(Interpolation.HOLD, Keyframes.at(t.clipA().keyframes, 40)!!.interpolation)
        assertEquals(Interpolation.LINEAR, Keyframes.at(t.clipA().keyframes, 0)!!.interpolation)
    }

    @Test
    fun `clear removes every keyframe and keeps the fixed transform`() {
        val before = animated().clipA().transform
        val t = TimelineOps.clearKeyframes(animated(), "a").getOrFail()

        assertTrue(t.clipA().keyframes.isEmpty())
        assertEquals(before, t.clipA().transform)
    }

    @Test
    fun `moving a clip keeps its keyframes in clip frames`() {
        val t = TimelineOps.move(animated(), "a", f(300)).getOrFail()

        assertEquals(listOf(0L, 40L, 99L), t.clipA().keyframes.map { it.frame })
        assertEquals(400.0, t.clipA().transformAtProjectFrame(f(340)).positionX, 1e-9)
    }

    @Test
    fun `split keeps the animation continuous on both halves`() {
        val before = animated()
        val t = TimelineOps.split(before, "v1", f(50 + 70), "b").getOrFail()
        val left = t.track("v1")!!.clip("a")!!
        val right = t.track("v1")!!.clip("b")!!

        assertTrue(t.invariantViolations().isEmpty())
        for (frame in 0L until 70L) {
            assertEquals(before.clipA().transformAt(frame).positionX, left.transformAt(frame).positionX, 1e-9)
        }
        for (frame in 0L until 30L) {
            assertEquals(before.clipA().transformAt(frame + 70).positionX, right.transformAt(frame).positionX, 1e-9)
        }
    }

    @Test
    fun `trimming the start keeps the pose at each project frame`() {
        val before = animated()
        val t = TimelineOps.trim(before, "a", TrimEdge.START, f(50 + 25)).getOrFail()

        assertTrue(t.invariantViolations().isEmpty())
        for (project in 75L until 150L) {
            assertEquals(
                before.clipA().transformAtProjectFrame(f(project)).positionX,
                t.clipA().transformAtProjectFrame(f(project)).positionX,
                1e-9,
            )
        }
    }

    @Test
    fun `extending the start shifts keyframes so they stay on the same content`() {
        val before = timeline(track("v1", clip("a", 50, 100, srcIn = 30)))
        var t = TimelineOps.setKeyframe(before, "a", key(10, 100.0)).getOrFail()
        t = TimelineOps.trim(t, "a", TrimEdge.START, f(40)).getOrFail()

        assertEquals(listOf(20L), t.clipA().keyframes.map { it.frame })
        assertEquals(40L, t.clipA().timelineStart.value)
    }

    @Test
    fun `trimming the end drops keyframes past it and keeps the pose`() {
        val before = animated()
        val t = TimelineOps.trim(before, "a", TrimEdge.END, f(50 + 60)).getOrFail()

        assertTrue(t.invariantViolations().isEmpty())
        // The first 60 frames look the same: the animation was linear toward the dropped keyframe.
        for (frame in 0L until 60L) {
            assertEquals(before.clipA().transformAt(frame).positionX, t.clipA().transformAt(frame).positionX, 1e-9)
        }
        assertTrue(t.clipA().keyframes.all { it.frame < 60 })
    }

    @Test
    fun `extending the end leaves keyframes alone`() {
        val before = animated()
        val t = TimelineOps.trim(before, "a", TrimEdge.END, f(50 + 120), sourceLength = 500).getOrFail()

        assertEquals(before.clipA().keyframes, t.clipA().keyframes)
    }

    @Test
    fun `overwrite trims the keyframes of the pieces it leaves`() {
        val before = animated()
        val over = clip("new", 90, 20)  // covers old clip frames 40..59
        val t = TimelineOps.overwrite(before, "v1", over).getOrFail()
        val left = t.track("v1")!!.clip("a")!!
        val right = t.track("v1")!!.clips.first { it.id.startsWith("a~") }

        assertTrue(t.invariantViolations().isEmpty())
        assertEquals(40L, left.durationFrames)
        for (frame in 0L until 40L) {
            assertEquals(before.clipA().transformAt(frame).positionX, left.transformAt(frame).positionX, 1e-9)
        }
        for (frame in 0L until right.durationFrames) {
            assertEquals(before.clipA().transformAt(frame + 60).positionX, right.transformAt(frame).positionX, 1e-9)
        }
    }

    @Test
    fun `overwrite refuses a clip whose keyframes do not fit`() {
        val bad = clip("n", 0, 10).copy(keyframes = listOf(key(10, 0.0)))
        val t = timeline(track("v1"))

        assertTrue(TimelineOps.overwrite(t, "v1", bad).errorOrFail() is EditError.InvalidClip)
    }

    @Test
    fun `titles can be animated too`() {
        val titleTrack = Track("t1", TrackType.TITLE, listOf(Clip("ti", null, f(0), f(0), f(60), title = TitleContent("Hi"))))
        val t = TimelineOps.setKeyframe(Timeline(listOf(titleTrack)), "ti", key(30, 100.0)).getOrFail()

        assertEquals(listOf(30L), t.track("t1")!!.clip("ti")!!.keyframes.map { it.frame })
        val split = TimelineOps.split(t, "t1", f(20), "ti2").getOrFail()
        assertTrue(split.invariantViolations().isEmpty())
    }

    @Test
    fun `keyframe edits are single undo steps`() {
        var history = EditHistory(timeline(track("v1", clip("a", 0, 100))))
        history = history.execute(EditCommand.SetKeyframe("a", key(10, 5.0))).getOrFail()
        history = history.execute(EditCommand.SetKeyframeInterpolation("a", 10, Interpolation.EASE)).getOrFail()
        history = history.execute(EditCommand.MoveKeyframe("a", 10, 20)).getOrFail()
        history = history.execute(EditCommand.RemoveKeyframe("a", 20)).getOrFail()

        assertTrue(history.timeline.track("v1")!!.clip("a")!!.keyframes.isEmpty())
        history = history.undo()
        assertEquals(listOf(20L), history.timeline.track("v1")!!.clip("a")!!.keyframes.map { it.frame })
        history = history.undo()
        assertEquals(listOf(10L), history.timeline.track("v1")!!.clip("a")!!.keyframes.map { it.frame })
        history = history.undo().undo()
        assertTrue(history.timeline.track("v1")!!.clip("a")!!.keyframes.isEmpty())
        assertTrue(!history.canUndo)
    }

    @Test
    fun `batch applies in order as one step and fails as a whole`() {
        val start = timeline(track("v1", clip("a", 0, 100)))
        val batch = EditCommand.Batch(
            listOf(EditCommand.SetKeyframe("a", key(5, 1.0)), EditCommand.SetGain("a", -3.0)),
        )
        val history = EditHistory(start).execute(batch).getOrFail()

        assertEquals(1, history.undoDepth)
        assertEquals(-3.0, history.timeline.track("v1")!!.clip("a")!!.gainDb, 0.0)
        assertEquals(1, history.timeline.track("v1")!!.clip("a")!!.keyframes.size)

        val failing = EditCommand.Batch(listOf(EditCommand.SetKeyframe("a", key(5, 1.0)), EditCommand.SetGain("a", 999.0)))
        assertTrue(failing.apply(start) is EditResult.Failure)
        assertTrue(history.undo().timeline == start)
    }

    @Test
    fun `remap canvas scales positions of clips and keyframes`() {
        val withFixed = TimelineOps.setTransform(animated(), "a", ClipTransform(positionX = 100.0, positionY = 40.0)).getOrFail()
        val t = TimelineOps.remapCanvas(withFixed, 1920, 1080, 1080, 1920).getOrFail()
        val c = t.clipA()

        assertEquals(100.0 * 1080 / 1920, c.transform.positionX, 1e-9)
        assertEquals(40.0 * 1920 / 1080, c.transform.positionY, 1e-9)
        assertEquals(400.0 * 1080 / 1920, Keyframes.at(c.keyframes, 40)!!.transform.positionX, 1e-9)
        assertEquals(1.0, c.transform.scaleX, 0.0)
        assertTrue(TimelineOps.remapCanvas(t, 0, 1, 1, 1).errorOrFail() is EditError.InvalidAppearance)
    }
}
