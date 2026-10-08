package com.qtekfun.ultimatevideoeditor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderPlanKeyframesTest {

    private fun pose(x: Double, op: Double = 1.0) = ClipTransform(positionX = x, opacity = op)

    @Test
    fun `the plan carries keyframes and counts them from the clip's own start`() {
        val base = timeline(track("v1", clip("A", 100, 100)))
        val animated = TimelineOps.setKeyframe(base, "A", Keyframe(0, pose(0.0))).getOrFail().let {
            TimelineOps.setKeyframe(it, "A", Keyframe(50, pose(500.0))).getOrFail()
        }
        val clip = animated.renderClips().single()

        assertEquals(100L, clip.keyframeOriginFrame)
        assertEquals(0.0, clip.transformAt(100).positionX, 0.0)
        assertEquals(250.0, clip.transformAt(125).positionX, 1e-9)
        assertEquals(500.0, clip.transformAt(150).positionX, 0.0)
        assertEquals(500.0, clip.transformAt(199).positionX, 0.0)
    }

    @Test
    fun `a transition starts a clip early but keyframes still count from its first frame`() {
        val chain = timeline(track("v1", clip("A", 0, 100), clip("B", 100, 100, srcIn = 50)))
        val withKeys = TimelineOps.setKeyframe(chain, "B", Keyframe(0, pose(0.0))).getOrFail().let {
            TimelineOps.setKeyframe(it, "B", Keyframe(20, pose(200.0))).getOrFail()
        }
        val withTransition = TimelineOps.addTransition(withKeys, Transition("t", "A", "B", 10)).getOrFail()
        val (_, incoming) = withTransition.renderClips()

        assertEquals(95L, incoming.startFrame)
        assertEquals(100L, incoming.keyframeOriginFrame)
        // Before the clip's own first frame the first pose holds; clip frame 10 is project frame 110.
        assertEquals(0.0, incoming.transformAt(96).positionX, 0.0)
        assertEquals(100.0, incoming.transformAt(110).positionX, 1e-9)
    }

    @Test
    fun `appearance folds the animated opacity and the crossfade together`() {
        val chain = timeline(track("v1", clip("A", 0, 100), clip("B", 100, 100, srcIn = 50)))
        val withKeys = TimelineOps.setKeyframe(chain, "B", Keyframe(0, pose(0.0, op = 0.5))).getOrFail()
        val withTransition = TimelineOps.addTransition(withKeys, Transition("t", "A", "B", 10)).getOrFail()
        val (_, incoming) = withTransition.renderClips()

        // Frame 95 is the first frame of the fade: progress 0.05.
        assertEquals(0.5 * 0.05, incoming.opacityAt(95), 1e-9)
        assertEquals(incoming.opacityAt(95), incoming.appearanceAt(95).opacity, 0.0)
        assertEquals(0.5, incoming.appearanceAt(120).opacity, 1e-9)
    }

    @Test
    fun `clips without keyframes use their fixed transform`() {
        val t = TimelineOps.setTransform(timeline(track("v1", clip("A", 0, 100))), "A", pose(33.0, op = 0.25)).getOrFail()
        val clip = t.renderClips().single()

        assertTrue(clip.keyframes.isEmpty())
        assertEquals(33.0, clip.appearanceAt(10).positionX, 0.0)
        assertEquals(0.25, clip.appearanceAt(10).opacity, 0.0)
    }
}
