package com.qtekfun.ultimatevideoeditor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class KeyframesTest {

    private fun pose(x: Double, y: Double = 0.0, sx: Double = 1.0, sy: Double = 1.0, rot: Double = 0.0, op: Double = 1.0) =
        ClipTransform(x, y, sx, sy, rot, op)

    private fun key(frame: Long, p: ClipTransform, mode: Interpolation = Interpolation.LINEAR) = Keyframe(frame, p, mode)

    /** The same vectors as `keyframesMatchTheKotlinVectors` in the native host tests. */
    private val shared = listOf(
        key(0, pose(0.0, 0.0, 1.0, 1.0, 0.0, 1.0), Interpolation.LINEAR),
        key(10, pose(100.0, -50.0, 2.0, 3.0, 90.0, 0.5), Interpolation.EASE),
        key(20, pose(300.0, 50.0, 1.0, 1.0, 180.0, 0.0), Interpolation.HOLD),
        key(30, pose(0.0, 0.0, 1.0, 1.0, 0.0, 1.0), Interpolation.LINEAR),
    )
    private val base = pose(7.0, 8.0)

    private fun assertPose(expected: ClipTransform, actual: ClipTransform) {
        assertEquals("positionX", expected.positionX, actual.positionX, 1e-9)
        assertEquals("positionY", expected.positionY, actual.positionY, 1e-9)
        assertEquals("scaleX", expected.scaleX, actual.scaleX, 1e-9)
        assertEquals("scaleY", expected.scaleY, actual.scaleY, 1e-9)
        assertEquals("rotation", expected.rotationDegrees, actual.rotationDegrees, 1e-9)
        assertEquals("opacity", expected.opacity, actual.opacity, 1e-9)
    }

    @Test
    fun `kotlin and native share vectors`() {
        assertPose(base, Keyframes.evaluate(emptyList(), 5, base))
        assertPose(shared[0].transform, Keyframes.evaluate(shared, -3, base))
        assertPose(shared[0].transform, Keyframes.evaluate(shared, 0, base))
        assertPose(pose(50.0, -25.0, 1.5, 2.0, 45.0, 0.75), Keyframes.evaluate(shared, 5, base))
        assertPose(shared[1].transform, Keyframes.evaluate(shared, 10, base))
        assertPose(pose(200.0, 0.0, 1.5, 2.0, 135.0, 0.25), Keyframes.evaluate(shared, 15, base))
        assertEquals(120.8, Keyframes.evaluate(shared, 12, base).positionX, 1e-9)
        assertPose(shared[2].transform, Keyframes.evaluate(shared, 25, base))
        assertPose(shared[3].transform, Keyframes.evaluate(shared, 30, base))
        assertPose(shared[3].transform, Keyframes.evaluate(shared, 1000, base))
    }

    @Test
    fun `a single keyframe holds before and after`() {
        val keys = listOf(key(5, pose(10.0)))

        assertEquals(10.0, Keyframes.evaluate(keys, 0, base).positionX, 0.0)
        assertEquals(10.0, Keyframes.evaluate(keys, 5, base).positionX, 0.0)
        assertEquals(10.0, Keyframes.evaluate(keys, 99, base).positionX, 0.0)
    }

    @Test
    fun `hold keeps the earlier pose until the next keyframe`() {
        val keys = listOf(key(0, pose(0.0), Interpolation.HOLD), key(10, pose(100.0)))

        assertEquals(0.0, Keyframes.evaluate(keys, 9, base).positionX, 0.0)
        assertEquals(100.0, Keyframes.evaluate(keys, 10, base).positionX, 0.0)
    }

    @Test
    fun `ease starts and ends slowly`() {
        val keys = listOf(key(0, pose(0.0), Interpolation.EASE), key(10, pose(100.0)))

        val early = Keyframes.evaluate(keys, 1, base).positionX
        val late = Keyframes.evaluate(keys, 9, base).positionX
        assertEquals(2.8, early, 1e-9)  // weight 0.1 -> 0.028
        assertEquals(97.2, late, 1e-9)
        assertEquals(50.0, Keyframes.evaluate(keys, 5, base).positionX, 1e-9)
    }

    @Test
    fun `rotation interpolates through full turns`() {
        val keys = listOf(key(0, pose(0.0, rot = 0.0)), key(10, pose(0.0, rot = 720.0)))

        assertEquals(360.0, Keyframes.evaluate(keys, 5, base).rotationDegrees, 1e-9)
    }

    @Test
    fun `problems are found`() {
        assertNull(Keyframes.problem(shared, 31))
        assertNotNull(Keyframes.problem(shared, 30))  // the keyframe at 30 is one past the last frame
        assertNotNull(Keyframes.problem(listOf(key(-1, pose(0.0))), 10))
        assertNotNull(Keyframes.problem(listOf(key(5, pose(0.0)), key(5, pose(1.0))), 10))
        assertNotNull(Keyframes.problem(listOf(key(6, pose(0.0)), key(5, pose(1.0))), 10))
        assertNotNull(Keyframes.problem(listOf(key(1, pose(0.0, op = 2.0))), 10))
    }

    @Test
    fun `set replaces at the same frame and keeps order`() {
        val keys = Keyframes.set(shared, key(15, pose(1.0)))
        assertEquals(listOf(0L, 10L, 15L, 20L, 30L), keys.map { it.frame })

        val replaced = Keyframes.set(keys, key(15, pose(2.0)))
        assertEquals(5, replaced.size)
        assertEquals(2.0, replaced.first { it.frame == 15L }.transform.positionX, 0.0)
    }

    @Test
    fun `previous and next keyframe frames are strict`() {
        assertEquals(10L, Keyframes.previousFrame(shared, 15))
        assertEquals(0L, Keyframes.previousFrame(shared, 10))
        assertNull(Keyframes.previousFrame(shared, 0))
        assertEquals(20L, Keyframes.nextFrame(shared, 10))
        assertNull(Keyframes.nextFrame(shared, 30))
    }

    @Test
    fun `cropping to the whole clip changes nothing`() {
        assertEquals(shared, Keyframes.cropped(shared, 0, 31, base))
    }

    @Test
    fun `cropping the start keeps the animation over the kept range`() {
        // Keep old frames 5..30: new frame 0 is old frame 5, whose linear value is a keyframe now.
        val cropped = Keyframes.cropped(shared, 5, 31, base)

        assertEquals(listOf(0L, 5L, 15L, 25L), cropped.map { it.frame })
        for (newFrame in 0L..25L) {
            assertPose(Keyframes.evaluate(shared, newFrame + 5, base), Keyframes.evaluate(cropped, newFrame, base))
        }
    }

    @Test
    fun `cropping the end keeps the animation up to the new last frame`() {
        val cropped = Keyframes.cropped(shared, 0, 13, base)  // old frames 0..12

        assertEquals(listOf(0L, 10L, 12L), cropped.map { it.frame })
        // Exact on the linear segment and at both ends; the cut ease segment keeps its mode over the
        // remaining two frames, so only its middle frame differs from the original curve.
        for (frame in (0L..10L) + 12L) {
            assertPose(Keyframes.evaluate(shared, frame, base), Keyframes.evaluate(cropped, frame, base))
        }
        assertEquals(Interpolation.EASE, cropped.first { it.frame == 10L }.interpolation)
    }

    @Test
    fun `linear and hold segments crop exactly in the middle`() {
        val keys = listOf(key(0, pose(0.0)), key(20, pose(200.0), Interpolation.HOLD), key(40, pose(0.0)))
        val cropped = Keyframes.cropped(keys, 5, 35, base)

        for (newFrame in 0L until 30L) {
            assertPose(Keyframes.evaluate(keys, newFrame + 5, base), Keyframes.evaluate(cropped, newFrame, base))
        }
    }

    @Test
    fun `growing a clip at its start shifts the keyframes later`() {
        val cropped = Keyframes.cropped(shared, -10, 31, base)

        assertEquals(listOf(10L, 20L, 30L, 40L), cropped.map { it.frame })
        // The new frames before the first keyframe show the first pose.
        assertPose(shared[0].transform, Keyframes.evaluate(cropped, 0, base))
    }

    @Test
    fun `a range holding no keyframe keeps the pose it had there`() {
        // Between the keyframes at 10 and 20 is an ease; keep only old frames 12..14.
        val cropped = Keyframes.cropped(shared, 12, 15, base)

        assertEquals(listOf(0L, 2L), cropped.map { it.frame })
        assertPose(Keyframes.evaluate(shared, 12, base), Keyframes.evaluate(cropped, 0, base))
        assertPose(Keyframes.evaluate(shared, 14, base), Keyframes.evaluate(cropped, 2, base))
    }

    @Test
    fun `remapping scales positions by the canvas ratios`() {
        val keys = listOf(key(0, pose(100.0, 50.0)))
        val remapped = Keyframes.remapped(keys, 0.5, 2.0)

        assertEquals(50.0, remapped.single().transform.positionX, 0.0)
        assertEquals(100.0, remapped.single().transform.positionY, 0.0)
        assertEquals(1.0, remapped.single().transform.scaleX, 0.0)
    }
}
