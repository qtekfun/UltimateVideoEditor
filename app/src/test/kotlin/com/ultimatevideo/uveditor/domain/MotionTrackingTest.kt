package com.ultimatevideo.uveditor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class MotionTrackingTest {
    private val cw = 1920
    private val ch = 1080
    private val identity = ClipTransform.IDENTITY

    private fun frame(source: Long, cx: Double, cy: Double, lost: Boolean = false) = TrackFrame(source, cx, cy, 0.1, 0.1, if (lost) 0.0 else 1.0, lost)

    /** A target that moves right across the picture: cx = frame / 1000, cy fixed at 0.5. */
    private fun movingPath(from: Long, to: Long, aspect: Double = 16.0 / 9.0) = TrackPath(aspect, (from..to).map { frame(it, it / 1000.0, 0.5) })

    private fun near(expected: Double, actual: Double, eps: Double = 1e-6) = assertTrue("expected $expected but was $actual", abs(expected - actual) <= eps)

    // region canvas maths

    @Test
    fun `the picture centre of an untransformed clip is the canvas centre`() {
        val (x, y) = TrackMath.toCanvas(0.5, 0.5, 16.0 / 9.0, cw, ch, identity)
        near(0.0, x)
        near(0.0, y)
    }

    @Test
    fun `a wide picture fills the canvas width and a tall one its height`() {
        val wide = TrackMath.fitSize(21.0 / 9.0, cw, ch)
        near(cw.toDouble(), wide.first)
        assertTrue(wide.second < ch)
        val tall = TrackMath.fitSize(9.0 / 16.0, cw, ch)
        near(ch.toDouble(), tall.second)
        assertTrue(tall.first < cw)
    }

    @Test
    fun `picture corners land on the fitted frame corners`() {
        val (x, y) = TrackMath.toCanvas(1.0, 0.0, 16.0 / 9.0, cw, ch, identity)
        near(cw / 2.0, x, 1e-6)
        near(-ch / 2.0, y, 1e-6)
    }

    @Test
    fun `scale rotation and position move the point as the compositor does`() {
        // Right edge middle: local (+fitW/2, 0). Scale 2 -> (+fitW, 0). Rotate 90 deg clockwise (y down) -> (0, +fitW). Then move.
        val pose = ClipTransform(positionX = 100.0, positionY = -50.0, scaleX = 2.0, scaleY = 2.0, rotationDegrees = 90.0)
        val (x, y) = TrackMath.toCanvas(1.0, 0.5, 16.0 / 9.0, cw, ch, pose)
        near(100.0, x, 1e-6)
        near(-50.0 + cw.toDouble(), y, 1e-6)
    }

    @Test
    fun `fromCanvas inverts toCanvas for any pose and aspect`() {
        val poses = listOf(
            identity,
            ClipTransform(positionX = 300.0, positionY = -120.0, scaleX = 0.5, scaleY = 0.5),
            ClipTransform(positionX = -40.0, positionY = 80.0, scaleX = 1.7, scaleY = 1.2, rotationDegrees = 37.0),
            ClipTransform(rotationDegrees = -135.0, scaleX = 3.0, scaleY = 3.0),
        )
        for (aspect in listOf(16.0 / 9.0, 4.0 / 3.0, 9.0 / 16.0, 1.0)) {
            for (pose in poses) {
                for ((u, v) in listOf(0.1 to 0.9, 0.5 to 0.5, 0.83 to 0.2)) {
                    val (x, y) = TrackMath.toCanvas(u, v, aspect, cw, ch, pose)
                    val (u2, v2) = TrackMath.fromCanvas(x, y, aspect, cw, ch, pose)
                    near(u, u2, 1e-9)
                    near(v, v2, 1e-9)
                }
            }
        }
    }

    // endregion

    // region path and frame mapping

    @Test
    fun `a path answers by source frame and holds at both ends`() {
        val path = movingPath(100, 200)
        assertEquals(150L, path.at(150)?.sourceFrame)
        assertEquals(100L, path.at(50)?.sourceFrame)
        assertEquals(200L, path.at(900)?.sourceFrame)
        assertNull(TrackPath(1.0, emptyList()).at(5))
        assertTrue(path.covers(105, 195))
        assertFalse(path.covers(50, 195))
    }

    @Test
    fun `canvasPath follows the source frame of a trimmed clip`() {
        // The clip starts at project frame 10 and plays source frames from 40.
        val tracked = clip("c", start = 10, len = 20, srcIn = 40)
        val points = TrackMath.canvasPath(movingPath(0, 100), tracked, cw, ch)
        assertEquals(20, points.size)
        assertEquals(10L, points.first().frame)
        // project frame 10 -> source 40 -> cx 0.040
        val expectedFirst = TrackMath.toCanvas(0.040, 0.5, 16.0 / 9.0, cw, ch, identity)
        near(expectedFirst.first, points.first().x)
        // project frame 29 -> source 59
        val expectedLast = TrackMath.toCanvas(0.059, 0.5, 16.0 / 9.0, cw, ch, identity)
        near(expectedLast.first, points.last().x)
    }

    @Test
    fun `canvasPath follows a sped up clip`() {
        // 2x: 100 source frames play in 50 project frames, so each project frame advances two source frames.
        val tracked = clip("c", start = 0, len = 100).copy(retimedFrames = 50)
        val points = TrackMath.canvasPath(movingPath(0, 120), tracked, cw, ch)
        assertEquals(50, points.size)
        val at10 = TrackMath.toCanvas(0.020, 0.5, 16.0 / 9.0, cw, ch, identity)
        near(at10.first, points[10].x, 1e-6)
        val at49 = TrackMath.toCanvas(0.098, 0.5, 16.0 / 9.0, cw, ch, identity)
        near(at49.first, points[49].x, 1e-6)
    }

    @Test
    fun `canvasPath follows a reversed clip`() {
        val tracked = clip("c", start = 0, len = 50).copy(reverse = true)
        val points = TrackMath.canvasPath(movingPath(0, 60), tracked, cw, ch)
        // Reversed: the first project frame shows the last source frame (49).
        val first = TrackMath.toCanvas(0.049, 0.5, 16.0 / 9.0, cw, ch, identity)
        near(first.first, points.first().x, 1e-6)
        assertTrue(points.first().x > points.last().x)
    }

    @Test
    fun `canvasPath follows the tracked clip's own pose and animation`() {
        val tracked = clip("c", start = 0, len = 10).copy(
            keyframes = listOf(
                Keyframe(0, ClipTransform(positionX = 0.0)),
                Keyframe(9, ClipTransform(positionX = 90.0)),
            ),
        )
        val points = TrackMath.canvasPath(movingPath(0, 20), tracked, cw, ch)
        val base0 = TrackMath.toCanvas(0.0, 0.5, 16.0 / 9.0, cw, ch, identity).first
        val base9 = TrackMath.toCanvas(0.009, 0.5, 16.0 / 9.0, cw, ch, identity).first
        near(base0, points[0].x, 1e-6)
        near(base9 + 90.0, points[9].x, 1e-6)
    }

    @Test
    fun `lost frames are flagged in the path`() {
        val path = TrackPath(1.0, listOf(frame(0, 0.5, 0.5), frame(1, 0.5, 0.5, lost = true), frame(2, 0.5, 0.5, lost = true)))
        assertEquals(2, path.lostCount)
        val points = TrackMath.canvasPath(path, clip("c", start = 0, len = 3), cw, ch)
        assertEquals(listOf(false, true, true), points.map { it.lost })
    }

    // endregion

    // region decimation

    @Test
    fun `a straight steady path keeps only its ends`() {
        val frames = LongArray(100) { it.toLong() }
        val xs = DoubleArray(100) { it * 3.0 }
        val ys = DoubleArray(100) { 10.0 - it * 0.5 }
        assertEquals(listOf(0, 99), TrackMath.decimate(frames, xs, ys, 1.0))
    }

    @Test
    fun `a corner is kept and every dropped sample stays within the tolerance`() {
        val n = 101
        val frames = LongArray(n) { it.toLong() }
        val xs = DoubleArray(n) { if (it <= 50) it * 4.0 else 200.0 }
        val ys = DoubleArray(n) { if (it <= 50) 0.0 else (it - 50) * 4.0 }
        val kept = TrackMath.decimate(frames, xs, ys, 1.0)
        assertTrue(50 in kept)
        assertTrue(kept.size < 10)
        for (i in 0 until n) {
            val hi = kept.first { it >= i }
            val lo = kept.last { it <= i }
            val t = if (hi == lo) 0.0 else (i - lo).toDouble() / (hi - lo)
            val px = xs[lo] + (xs[hi] - xs[lo]) * t
            val py = ys[lo] + (ys[hi] - ys[lo]) * t
            assertTrue("sample $i off by ${hypot(xs[i] - px, ys[i] - py)}", hypot(xs[i] - px, ys[i] - py) <= 1.0 + 1e-9)
        }
    }

    @Test
    fun `short paths are returned whole`() {
        assertEquals(emptyList<Int>(), TrackMath.decimate(LongArray(0), DoubleArray(0), DoubleArray(0), 1.0))
        assertEquals(listOf(0), TrackMath.decimate(LongArray(1), DoubleArray(1), DoubleArray(1), 1.0))
        assertEquals(listOf(0, 1), TrackMath.decimate(longArrayOf(0, 5), doubleArrayOf(0.0, 9.0), doubleArrayOf(0.0, 9.0), 1.0))
    }

    // endregion

    // region attach

    @Test
    fun `an attached clip sits on the target at every frame within the tolerance`() {
        val tracked = clip("v", start = 0, len = 100)
        val attached = clip("t", start = 20, len = 40, asset = null).copy(title = TitleContent("Hi"))
        val path = movingPath(0, 120)
        val keys = TrackMath.attachKeyframes(path, tracked, attached, cw, ch)
        assertNull(Keyframes.problem(keys, attached.durationFrames))
        assertEquals(0L, keys.first().frame)
        assertEquals(39L, keys.last().frame)
        // A steady horizontal drift is two keys.
        assertEquals(2, keys.size)
        for (k in 0 until 40) {
            val pose = Keyframes.evaluate(keys, k.toLong(), attached.transform)
            val expected = TrackMath.toCanvas((20 + k) / 1000.0, 0.5, path.aspect, cw, ch, identity)
            assertTrue(abs(pose.positionX - expected.first) <= 1.0 + 1e-9)
            assertTrue(abs(pose.positionY - expected.second) <= 1.0 + 1e-9)
        }
    }

    @Test
    fun `the attached clip keeps its scale, rotation, opacity and offset`() {
        val tracked = clip("v", start = 0, len = 50)
        val base = ClipTransform(scaleX = 1.5, scaleY = 1.5, rotationDegrees = 12.0, opacity = 0.6)
        val attached = clip("t", start = 0, len = 30, asset = null).copy(title = TitleContent("Hi"), transform = base)
        val keys = TrackMath.attachKeyframes(movingPath(0, 60), tracked, attached, cw, ch, offsetX = 10.0, offsetY = -20.0)
        for (key in keys) {
            assertEquals(1.5, key.transform.scaleX, 0.0)
            assertEquals(12.0, key.transform.rotationDegrees, 0.0)
            assertEquals(0.6, key.transform.opacity, 0.0)
        }
        val first = TrackMath.toCanvas(0.0, 0.5, 16.0 / 9.0, cw, ch, identity)
        near(first.first + 10.0, keys.first().transform.positionX)
        near(first.second - 20.0, keys.first().transform.positionY)
    }

    @Test
    fun `existing keyframes of the attached clip keep their frames and interpolation`() {
        val tracked = clip("v", start = 0, len = 50)
        val existing = listOf(
            Keyframe(0, ClipTransform(opacity = 0.0), Interpolation.EASE),
            Keyframe(10, ClipTransform(opacity = 1.0), Interpolation.HOLD),
        )
        val attached = clip("t", start = 0, len = 30, asset = null).copy(title = TitleContent("Hi"), keyframes = existing)
        val keys = TrackMath.attachKeyframes(movingPath(0, 60), tracked, attached, cw, ch)
        assertEquals(Interpolation.EASE, keys.first { it.frame == 0L }.interpolation)
        assertEquals(Interpolation.HOLD, keys.first { it.frame == 10L }.interpolation)
        assertEquals(0.0, keys.first { it.frame == 0L }.transform.opacity, 1e-9)
        assertEquals(1.0, keys.first { it.frame == 10L }.transform.opacity, 1e-9)
    }

    @Test
    fun `outside the tracked clip the first and last positions hold`() {
        val tracked = clip("v", start = 20, len = 20)
        val attached = clip("t", start = 0, len = 60, asset = null).copy(title = TitleContent("Hi"))
        val keys = TrackMath.attachKeyframes(movingPath(0, 60), tracked, attached, cw, ch)
        val before = Keyframes.evaluate(keys, 0, attached.transform)
        val atStart = Keyframes.evaluate(keys, 20, attached.transform)
        val after = Keyframes.evaluate(keys, 59, attached.transform)
        val atEnd = Keyframes.evaluate(keys, 39, attached.transform)
        near(atStart.positionX, before.positionX, 1.0)
        near(atEnd.positionX, after.positionX, 1.0)
    }

    @Test
    fun `no path gives no keyframes`() {
        val tracked = clip("v", start = 0, len = 10)
        val attached = clip("t", start = 0, len = 10, asset = null).copy(title = TitleContent("Hi"))
        assertTrue(TrackMath.attachKeyframes(TrackPath(1.0, emptyList()), tracked, attached, cw, ch).isEmpty())
    }

    // endregion

    // region operations and undo

    private fun scene() = timeline(
        track("v2", clip("sticker", start = 0, len = 30, asset = null).copy(title = TitleContent("Hi"))),
        track("v1", clip("v", start = 0, len = 100)),
        track("a1", clip("m", start = 0, len = 100), type = TrackType.AUDIO),
    )

    private val seed = TrackSeed(sourceFrame = 10, cx = 0.5, cy = 0.5, w = 0.1, h = 0.1)

    @Test
    fun `a motion track can be added to a video clip and removed`() {
        val added = MotionTrackOps.add(scene(), MotionTrack("mt1", "v", "Track 1", seed)).getOrFail()
        assertEquals(listOf("mt1"), added.motionTracks.map { it.id })
        assertNotNull(added.motionTrack("mt1"))
        val removed = MotionTrackOps.remove(added, "mt1").getOrFail()
        assertTrue(removed.motionTracks.isEmpty())
    }

    @Test
    fun `invalid motion tracks are rejected`() {
        val base = scene()
        assertTrue(MotionTrackOps.add(base, MotionTrack("mt1", "nope", "T", seed)).errorOrFail() is EditError.ClipNotFound)
        assertTrue(MotionTrackOps.add(base, MotionTrack("mt1", "m", "T", seed)).errorOrFail() is EditError.InvalidClip)  // audio
        assertTrue(MotionTrackOps.add(base, MotionTrack("mt1", "sticker", "T", seed)).errorOrFail() is EditError.InvalidClip)  // title
        assertTrue(MotionTrackOps.add(base, MotionTrack("mt1", "v", "T", seed.copy(cx = 1.5))).errorOrFail() is EditError.InvalidClip)
        assertTrue(MotionTrackOps.add(base, MotionTrack("mt1", "v", "T", seed.copy(w = 0.0))).errorOrFail() is EditError.InvalidClip)
        assertTrue(MotionTrackOps.add(base, MotionTrack("", "v", "T", seed)).errorOrFail() is EditError.InvalidClip)
        val once = MotionTrackOps.add(base, MotionTrack("mt1", "v", "T", seed)).getOrFail()
        assertTrue(MotionTrackOps.add(once, MotionTrack("mt1", "v", "T2", seed)).errorOrFail() is EditError.InvalidClip)
        assertTrue(MotionTrackOps.remove(base, "nope").errorOrFail() is EditError.InvalidClip)
    }

    @Test
    fun `attaching replaces the keyframes and is one exact undo step`() {
        val base = MotionTrackOps.add(scene(), MotionTrack("mt1", "v", "Track 1", seed)).getOrFail()
        val tracked = checkNotNull(base.trackOfClip("v")?.clip("v"))
        val attached = checkNotNull(base.trackOfClip("sticker")?.clip("sticker"))
        val keys = TrackMath.attachKeyframes(movingPath(0, 100), tracked, attached, cw, ch)
        val history = EditHistory(base)
        val after = history.execute(EditCommand.AttachToMotionTrack("sticker", keys)).getOrFail()
        assertEquals(keys, after.timeline.trackOfClip("sticker")?.clip("sticker")?.keyframes)
        assertTrue(after.canUndo)
        val undone = after.undo()
        assertEquals(base, undone.timeline)
        assertFalse(undone.canUndo)
        assertEquals(after.timeline, undone.redo().timeline)
    }

    @Test
    fun `attaching rejects an audio clip, no keys and keys outside the clip`() {
        val base = scene()
        assertTrue(MotionTrackOps.attach(base, "m", listOf(Keyframe(0, identity))).errorOrFail() is EditError.InvalidKeyframe)
        assertTrue(MotionTrackOps.attach(base, "sticker", emptyList()).errorOrFail() is EditError.InvalidKeyframe)
        assertTrue(MotionTrackOps.attach(base, "sticker", listOf(Keyframe(500, identity))).errorOrFail() is EditError.InvalidKeyframe)
        assertTrue(MotionTrackOps.attach(base, "nope", listOf(Keyframe(0, identity))).errorOrFail() is EditError.ClipNotFound)
    }

    @Test
    fun `adding and removing a motion track undo exactly`() {
        val history = EditHistory(scene())
        val added = history.execute(EditCommand.AddMotionTrack(MotionTrack("mt1", "v", "Track 1", seed))).getOrFail()
        assertEquals(1, added.timeline.motionTracks.size)
        val removed = added.execute(EditCommand.RemoveMotionTrack("mt1")).getOrFail()
        assertTrue(removed.timeline.motionTracks.isEmpty())
        assertEquals(added.timeline, removed.undo().timeline)
        assertEquals(scene(), added.undo().timeline)
    }

    @Test
    fun `deleting the tracked clip drops its motion tracks`() {
        val withTrack = MotionTrackOps.add(scene(), MotionTrack("mt1", "v", "Track 1", seed)).getOrFail()
        val deleted = TimelineOps.rippleDelete(withTrack, "v").getOrFail()
        assertTrue(deleted.motionTracks.isEmpty())
        val untouched = TimelineOps.rippleDelete(withTrack, "sticker").getOrFail()
        assertEquals(1, untouched.motionTracks.size)
    }

    // endregion
}
