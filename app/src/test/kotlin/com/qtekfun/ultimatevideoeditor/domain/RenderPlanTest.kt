package com.qtekfun.ultimatevideoeditor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderPlanTest {

    private fun twoClips(srcA: String = "a", srcB: String = "b") = timeline(
        track(
            "v1",
            clip("A", 0, 100, asset = srcA),
            clip("B", 100, 100, srcIn = 50, asset = srcB),
        ),
    )

    private fun withTransition(duration: Long = 10, srcA: String = "a", srcB: String = "b"): Timeline =
        TimelineOps.addTransition(twoClips(srcA, srcB), Transition("t", "A", "B", duration)).getOrFail()

    @Test
    fun `without transitions the plan matches the clips`() {
        val plan = twoClips().renderClips()

        assertEquals(listOf(0L to 100L, 100L to 200L), plan.map { it.startFrame to it.endFrame })
        assertEquals(listOf(0L, 50L), plan.map { it.sourceInFrame })
        assertTrue(plan.all { it.crossfadeInFrames == 0L && it.crossfadeOutFrames == 0L })
    }

    @Test
    fun `a transition extends both clips around the cut`() {
        val (a, b) = withTransition(10).renderClips()

        // Outgoing runs 5 frames past its end, incoming starts 5 frames early reading earlier media.
        assertEquals(0L to 105L, a.startFrame to a.endFrame)
        assertEquals(95L to 200L, b.startFrame to b.endFrame)
        assertEquals(45L, b.sourceInFrame)
        assertEquals(0L, a.crossfadeInFrames)
        assertEquals(10L, a.crossfadeOutFrames)
        assertEquals(10L, b.crossfadeInFrames)
        assertEquals(0L, b.crossfadeOutFrames)
    }

    @Test
    fun `source mapping stays continuous across the extension`() {
        val (a, b) = withTransition(10).renderClips()

        // The frame shown at the cut is the same as without the transition.
        assertEquals(100L, a.sourceFrameAt(100))
        assertEquals(50L, b.sourceFrameAt(100))
        assertEquals(104L, a.sourceFrameAt(104))
        assertEquals(45L, b.sourceFrameAt(95))
    }

    @Test
    fun `a clip in the middle of a chain is extended on both ends`() {
        val chain = timeline(track("v1", clip("A", 0, 100), clip("B", 100, 100, srcIn = 50), clip("C", 200, 100, srcIn = 50)))
        val withBoth = TimelineOps.addTransition(
            TimelineOps.addTransition(chain, Transition("t1", "A", "B", 10)).getOrFail(),
            Transition("t2", "B", "C", 20),
        ).getOrFail()

        val b = withBoth.renderClips().first { it.clipId == "B" }

        assertEquals(95L to 210L, b.startFrame to b.endFrame)
        assertEquals(10L, b.crossfadeInFrames)
        assertEquals(20L, b.crossfadeOutFrames)
    }

    @Test
    fun `crossfade progress is symmetric and never reaches the ends inside the fade`() {
        assertEquals(0.05, CrossfadeCurve.progress(0, 10), 1e-12)
        assertEquals(0.95, CrossfadeCurve.progress(9, 10), 1e-12)
        assertEquals(1.0, CrossfadeCurve.progress(10, 10), 0.0)
        assertEquals(1.0, CrossfadeCurve.progress(50, 10), 0.0)
        assertEquals(1.0, CrossfadeCurve.progress(3, 0), 0.0)
        for (k in 0L until 10L) {
            assertEquals(1.0, CrossfadeCurve.progress(k, 10) + CrossfadeCurve.progress(9 - k, 10), 1e-12)
        }
    }

    @Test
    fun `audio crossfade is equal power`() {
        for (k in 0L until 20L) {
            val power = CrossfadeCurve.fadeInGain(k, 20).let { it * it } + CrossfadeCurve.fadeOutGain(k, 20).let { it * it }
            assertEquals(1.0, power, 1e-12)
        }
    }

    @Test
    fun `incoming opacity ramps up while the outgoing stays opaque`() {
        val (a, b) = withTransition(10).renderClips()

        assertEquals(1.0, a.opacityAt(100), 0.0)
        assertEquals(0.05, b.opacityAt(95), 1e-12)
        assertEquals(0.55, b.opacityAt(100), 1e-12)
        assertEquals(1.0, b.opacityAt(105), 0.0)
        assertEquals(1.0, b.opacityAt(150), 0.0)
    }

    @Test
    fun `clip opacity scales the ramp`() {
        val half = TimelineOps.setTransform(withTransition(10), "B", ClipTransform(opacity = 0.5)).getOrFail()

        val b = half.renderClips().first { it.clipId == "B" }

        assertEquals(0.275, b.opacityAt(100), 1e-12)
    }

    @Test
    fun `incoming clip is above the outgoing one inside the overlap`() {
        val plan = withTransition(10).renderClips()

        assertEquals(listOf("A"), visualClipsAt(plan, 90).map { it.clipId })
        assertEquals(listOf("A", "B"), visualClipsAt(plan, 100).map { it.clipId })
        assertEquals(listOf("B"), visualClipsAt(plan, 105).map { it.clipId })
    }

    @Test
    fun `layers are ordered bottom track first and titles take their track position`() {
        val title = Clip("T", null, f(0), f(0), f(200), title = TitleContent("hi"))
        val plan = timeline(
            track("t1", title, type = TrackType.TITLE),
            track("v1", clip("A", 0, 200)),
            track("v2", clip("B", 0, 200, asset = "b")),
            track("a1", clip("S", 0, 200, asset = "s"), type = TrackType.AUDIO),
        ).renderClips()

        assertEquals(listOf("B", "A", "T"), visualClipsAt(plan, 10).map { it.clipId })
        assertEquals(-1, plan.first { it.clipId == "S" }.layer)
        assertEquals(listOf(0, 1, 2, -1), plan.map { it.layer })
    }

    @Test
    fun `titles read from zero and carry their text`() {
        val title = Clip("T", null, f(10), f(0), f(30), title = TitleContent("hi"))
        val plan = timeline(track("t1", title, type = TrackType.TITLE)).renderClips()

        assertEquals(RenderKind.TITLE, plan.single().kind)
        assertEquals(0L, plan.single().sourceInFrame)
        assertEquals("hi", plan.single().title!!.text)
    }

    @Test
    fun `cuts of the same media get separate decoder lanes during a transition`() {
        val same = withTransition(10, srcA = "x", srcB = "x").renderClips()
        val different = withTransition(10, srcA = "x", srcB = "y").renderClips()

        assertEquals(listOf(0, 1), same.map { it.lane })
        assertEquals(listOf(0, 0), different.map { it.lane })
    }

    @Test
    fun `lanes alternate along a chain of the same media`() {
        val chain = timeline(
            track(
                "v1",
                clip("A", 0, 100, asset = "x"),
                clip("B", 100, 100, srcIn = 50, asset = "x"),
                clip("C", 200, 100, srcIn = 50, asset = "x"),
            ),
        )
        val withBoth = TimelineOps.addTransition(
            TimelineOps.addTransition(chain, Transition("t1", "A", "B", 10)).getOrFail(),
            Transition("t2", "B", "C", 10),
        ).getOrFail()

        assertEquals(listOf(0, 1, 0), withBoth.renderClips().map { it.lane })
    }

    @Test
    fun `extended clips of one track never overlap beyond their transition`() {
        val chain = timeline(track("v1", clip("A", 0, 100), clip("B", 100, 12, srcIn = 50), clip("C", 112, 100, srcIn = 50)))
        val withBoth = TimelineOps.addTransition(
            TimelineOps.addTransition(chain, Transition("t1", "A", "B", 12)).getOrFail(),
            Transition("t2", "B", "C", 12),
        ).getOrFail()

        val (a, _, c) = withBoth.renderClips()

        // A's tail (up to 106) is before C's early start (106): at most touching.
        assertTrue(a.endFrame <= c.startFrame)
    }
}
