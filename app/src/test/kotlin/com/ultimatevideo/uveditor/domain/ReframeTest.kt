package com.ultimatevideo.uveditor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReframeTest {
    private val widescreen = 16.0 / 9.0

    @Test
    fun `16 by 9 footage fills a vertical canvas and keeps the point centred`() {
        // Canvas 1080x1920, footage 16:9: contain-fit is 1080x607.5, so cover needs scale 1920/607.5.
        val pose = Reframe.poseFor(0.5, 0.5, widescreen, 1080, 1920, 1.0, ClipTransform.IDENTITY)
        assertEquals(1920.0 / 607.5, pose.scaleX, 1e-9)
        assertEquals(0.0, pose.positionX, 1e-9)
        assertTrue(Reframe.covers(pose, widescreen, 1080, 1920))
    }

    @Test
    fun `a point on the left moves the picture right until it is centred`() {
        val pose = Reframe.poseFor(0.25, 0.5, widescreen, 1080, 1920, 1.0, ClipTransform.IDENTITY)
        val (x, _) = TrackMath.toCanvas(0.25, 0.5, widescreen, 1080, 1920, pose)
        assertEquals(0.0, x, 1e-6)
        assertTrue(pose.positionX > 0)
        assertTrue(Reframe.covers(pose, widescreen, 1080, 1920))
    }

    @Test
    fun `a point near the edge is limited so no edge of the canvas is left uncovered`() {
        val pose = Reframe.poseFor(0.0, 0.5, widescreen, 1080, 1920, 1.0, ClipTransform.IDENTITY)
        assertTrue(Reframe.covers(pose, widescreen, 1080, 1920))
        val (x, _) = TrackMath.toCanvas(0.0, 0.5, widescreen, 1080, 1920, pose)
        assertEquals(-540.0, x, 1e-6) // the picture's left edge sits on the canvas's left edge
    }

    @Test
    fun `zoom enlarges the picture and is limited`() {
        val one = Reframe.poseFor(0.5, 0.5, widescreen, 1080, 1920, 1.0, ClipTransform.IDENTITY)
        val two = Reframe.poseFor(0.5, 0.5, widescreen, 1080, 1920, 2.0, ClipTransform.IDENTITY)
        assertEquals(one.scaleX * 2, two.scaleX, 1e-9)
        val huge = Reframe.poseFor(0.5, 0.5, widescreen, 1080, 1920, 99.0, ClipTransform.IDENTITY)
        assertEquals(one.scaleX * Reframe.MAX_ZOOM, huge.scaleX, 1e-9)
    }

    @Test
    fun `footage already matching the canvas needs no enlargement`() {
        val pose = Reframe.poseFor(0.5, 0.5, 1920.0 / 1080.0, 1920, 1080, 1.0, ClipTransform.IDENTITY)
        assertEquals(1.0, pose.scaleX, 1e-9)
        assertEquals(0.0, pose.positionX, 1e-9)
        assertEquals(0.0, pose.positionY, 1e-9)
    }

    @Test
    fun `opacity of the clip is kept`() {
        val base = ClipTransform(opacity = 0.4)
        assertEquals(0.4, Reframe.poseFor(0.3, 0.6, widescreen, 1080, 1920, 1.0, base).opacity, 1e-12)
    }

    private fun scene() = timeline(track("v1", clip("a", 0, 100)))

    @Test
    fun `one point sets a fixed pose without keyframes`() {
        val result = ReframeClip("a", listOf(ReframePoint(0, 0.3, 0.5)), widescreen, 1080, 1920).apply(scene()).getOrFail()
        val clip = result.track("v1")!!.clip("a")!!
        assertTrue(clip.keyframes.isEmpty())
        assertTrue(clip.transform.scaleX > 1.0)
    }

    @Test
    fun `several points write eased keyframes and replace the old ones`() {
        val withOld = TimelineOps.setKeyframe(scene(), "a", Keyframe(5, ClipTransform(positionX = 9.0))).getOrFail()
        val points = listOf(ReframePoint(0, 0.3, 0.5), ReframePoint(50, 0.7, 0.5), ReframePoint(99, 0.5, 0.5))
        val result = ReframeClip("a", points, widescreen, 1080, 1920).apply(withOld).getOrFail()
        val keys = result.track("v1")!!.clip("a")!!.keyframes
        assertEquals(listOf(0L, 50L, 99L), keys.map { it.frame })
        assertTrue(keys.all { it.interpolation == Interpolation.EASE })
        assertTrue(keys[0].transform.positionX > keys[1].transform.positionX)
    }

    @Test
    fun `bad input is refused`() {
        assertTrue(ReframeClip("a", emptyList(), widescreen, 1080, 1920).apply(scene()).errorOrFail() is EditError.InvalidClip)
        assertTrue(ReframeClip("a", listOf(ReframePoint(0, .5, .5)), 0.0, 1080, 1920).apply(scene()).errorOrFail() is EditError.InvalidClip)
        assertEquals(EditError.ClipNotFound("zz"), ReframeClip("zz", listOf(ReframePoint(0, .5, .5)), widescreen, 1080, 1920).apply(scene()).errorOrFail())
    }

    @Test
    fun `reframing is one undo step`() {
        val before = scene()
        val points = listOf(ReframePoint(0, 0.3, 0.5), ReframePoint(50, 0.7, 0.5))
        val done = EditHistory(before).execute(ReframeClip("a", points, widescreen, 1080, 1920)).getOrFail()
        assertEquals(before, done.undo().timeline)
    }
}
