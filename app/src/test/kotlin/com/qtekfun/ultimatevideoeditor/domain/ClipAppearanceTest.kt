package com.qtekfun.ultimatevideoeditor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipAppearanceTest {
    private val base = timeline(track("v1", clip("a", 0, 100), clip("b", 120, 50)))
    private val moved = ClipTransform(positionX = 40.0, positionY = -10.0, scaleX = 0.5, scaleY = 0.5, rotationDegrees = 90.0, opacity = 0.5)

    @Test
    fun `new clips are identity and unity gain`() {
        val c = clip("a", 0, 10)
        assertTrue(c.transform.isIdentity)
        assertEquals(0.0, c.gainDb, 0.0)
    }

    @Test
    fun `set transform changes only that clip`() {
        val result = TimelineOps.setTransform(base, "b", moved).getOrFail()

        assertEquals(moved, result.track("v1")!!.clip("b")!!.transform)
        assertTrue(result.track("v1")!!.clip("a")!!.transform.isIdentity)
        assertLayout(result, "v1", at("a", 0, 100), at("b", 120, 170))
    }

    @Test
    fun `set gain changes only that clip`() {
        val result = TimelineOps.setGain(base, "a", -6.0).getOrFail()

        assertEquals(-6.0, result.track("v1")!!.clip("a")!!.gainDb, 0.0)
        assertEquals(0.0, result.track("v1")!!.clip("b")!!.gainDb, 0.0)
    }

    @Test
    fun `unknown clip is reported`() {
        assertEquals(EditError.ClipNotFound("zz"), TimelineOps.setTransform(base, "zz", moved).errorOrFail())
        assertEquals(EditError.ClipNotFound("zz"), TimelineOps.setGain(base, "zz", 0.0).errorOrFail())
    }

    @Test
    fun `values that cannot be rendered are rejected`() {
        val bad = listOf(
            moved.copy(scaleX = 0.0),
            moved.copy(scaleY = -1.0),
            moved.copy(opacity = 1.5),
            moved.copy(opacity = -0.1),
            moved.copy(positionX = Double.NaN),
            moved.copy(rotationDegrees = Double.POSITIVE_INFINITY),
        )
        for (transform in bad) {
            assertTrue(transform.problem() != null)
            assertTrue(TimelineOps.setTransform(base, "a", transform).errorOrFail() is EditError.InvalidAppearance)
        }
        assertTrue(TimelineOps.setGain(base, "a", 30.0).errorOrFail() is EditError.InvalidAppearance)
        assertTrue(TimelineOps.setGain(base, "a", Double.NaN).errorOrFail() is EditError.InvalidAppearance)
    }

    @Test
    fun `gain limits are inclusive`() {
        TimelineOps.setGain(base, "a", ClipGain.MIN_DB).getOrFail()
        TimelineOps.setGain(base, "a", ClipGain.MAX_DB).getOrFail()
    }

    @Test
    fun `split halves keep the transform and gain`() {
        val styled = TimelineOps.setTransform(TimelineOps.setGain(base, "a", -3.0).getOrFail(), "a", moved).getOrFail()

        val split = TimelineOps.split(styled, "v1", f(40), "a2").getOrFail()

        for (id in listOf("a", "a2")) {
            val clip = split.track("v1")!!.clip(id)!!
            assertEquals(moved, clip.transform)
            assertEquals(-3.0, clip.gainDb, 0.0)
        }
    }

    @Test
    fun `overwrite keeps the transform of the parts it leaves`() {
        val styled = TimelineOps.setTransform(base, "a", moved).getOrFail()

        val result = TimelineOps.overwrite(styled, "v1", clip("n", 30, 20)).getOrFail()

        assertEquals(moved, result.track("v1")!!.clip("a")!!.transform)
        assertEquals(moved, result.track("v1")!!.clip("a~n")!!.transform)
        assertTrue(result.track("v1")!!.clip("n")!!.transform.isIdentity)
    }

    @Test
    fun `overwrite rejects a clip with an invalid appearance`() {
        val bad = clip("n", 300, 20).copy(gainDb = 99.0)
        assertTrue(TimelineOps.overwrite(base, "v1", bad).errorOrFail() is EditError.InvalidClip)
    }

    @Test
    fun `moving and trimming keep the transform`() {
        val styled = TimelineOps.setTransform(base, "b", moved).getOrFail()

        val result = TimelineOps.trim(TimelineOps.move(styled, "b", f(200)).getOrFail(), "b", TrimEdge.END, f(230)).getOrFail()

        assertEquals(moved, result.track("v1")!!.clip("b")!!.transform)
    }

    @Test
    fun `invariants flag an invalid appearance`() {
        val broken = Timeline(listOf(Track("v1", TrackType.VIDEO, listOf(clip("a", 0, 10).copy(transform = moved.copy(scaleX = 0.0))))))
        assertFalse(broken.invariantViolations().isEmpty())
    }

    @Test
    fun `undo restores the previous transform and gain`() {
        val h0 = EditHistory(base)
        val h1 = h0.execute(EditCommand.SetTransform("a", moved)).getOrFail()
        val h2 = h1.execute(EditCommand.SetGain("a", -9.0)).getOrFail()

        assertEquals(moved, h2.timeline.track("v1")!!.clip("a")!!.transform)
        assertEquals(-9.0, h2.timeline.track("v1")!!.clip("a")!!.gainDb, 0.0)
        assertEquals(h1.timeline, h2.undo().timeline)
        assertEquals(base, h2.undo().undo().timeline)
        assertEquals(h2.timeline, h2.undo().undo().redo().redo().timeline)
    }

    @Test
    fun `set appearance is one undo step and all or nothing`() {
        val h = EditHistory(base).execute(EditCommand.SetAppearance("a", moved, -4.0)).getOrFail()

        val clip = h.timeline.track("v1")!!.clip("a")!!
        assertEquals(moved, clip.transform)
        assertEquals(-4.0, clip.gainDb, 0.0)
        assertEquals(1, h.undoDepth)
        assertEquals(base, h.undo().timeline)
        assertTrue(EditHistory(base).execute(EditCommand.SetAppearance("a", moved, 99.0)).errorOrFail() is EditError.InvalidAppearance)
        assertTrue(TimelineOps.setAppearance(base, "a", moved.copy(opacity = 2.0), 0.0).errorOrFail() is EditError.InvalidAppearance)
    }

    @Test
    fun `a rejected value leaves history untouched`() {
        val h = EditHistory(base)
        assertTrue(h.execute(EditCommand.SetTransform("a", moved.copy(scaleX = 0.0))).errorOrFail() is EditError.InvalidAppearance)
        assertFalse(h.canUndo)
    }
}
