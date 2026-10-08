package com.qtekfun.ultimatevideoeditor.domain

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RetimeTest {

    private fun retimed(span: Long, length: Long, srcIn: Long = 0, reverse: Boolean = false, ramp: List<SpeedKey> = emptyList()) =
        Clip(
            "c", "a", f(0), f(srcIn), f(srcIn + span),
            retimedFrames = length.takeIf { it != span },
            reverse = reverse,
            speedRamp = ramp,
        )

    private fun frames(clip: Clip): List<Long> = (0 until clip.durationFrames).map { clip.retime.sourceFrameAt(it) }

    @Test
    fun `a plain clip maps frame t to source in plus t`() {
        val clip = clip("c", 10, 20, srcIn = 5)
        assertFalse(clip.isRetimed)
        assertTrue(clip.retime.isIdentity)
        assertEquals((5L until 25L).toList(), frames(clip))
    }

    @Test
    fun `double speed skips every other frame and halves the length`() {
        val clip = retimed(span = 100, length = 50, srcIn = 10)
        assertEquals(50L, clip.durationFrames)
        assertEquals((0 until 50).map { 10L + 2 * it }, frames(clip))
    }

    @Test
    fun `half speed shows every source frame twice`() {
        val clip = retimed(span = 10, length = 20)
        assertEquals((0 until 20).map { (it / 2).toLong() }, frames(clip))
    }

    @Test
    fun `non integer speed uses exact integer floor division`() {
        // 3 source frames over 2 timeline frames is 1.5x.
        val clip = retimed(span = 30, length = 20)
        assertEquals((0 until 20).map { (it * 3 / 2).toLong() }, frames(clip))
    }

    @Test
    fun `reverse plays the range backwards from its last frame`() {
        val clip = retimed(span = 10, length = 10, srcIn = 4, reverse = true)
        assertEquals((13L downTo 4L).toList(), frames(clip))
    }

    @Test
    fun `reverse combined with speed stays inside the range`() {
        val clip = retimed(span = 100, length = 40, srcIn = 20, reverse = true)
        val shown = frames(clip)
        assertEquals(119L, shown.first())
        assertTrue(shown.all { it in 20L..119L })
        assertEquals(shown.sortedDescending(), shown)
    }

    @Test
    fun `a one frame range is a freeze frame whatever the position`() {
        val clip = retimed(span = 1, length = 30, srcIn = 42)
        assertTrue(clip.isFreeze)
        assertEquals(List(30) { 42L }, frames(clip))
        assertEquals(42L, clip.retime.sourceFrameAt(-5))
        assertEquals(42L, clip.retime.sourceFrameAt(300))
    }

    @Test
    fun `positions outside the clip continue the same speed`() {
        val clip = retimed(span = 100, length = 50, srcIn = 10)
        assertEquals(8L, clip.retime.sourceFrameAt(-1))
        assertEquals(110L, clip.retime.sourceFrameAt(50))
        val slow = retimed(span = 10, length = 20, srcIn = 10)
        assertEquals(9L, slow.retime.sourceFrameAt(-1)) // floor(-0.5) = -1
    }

    @Test
    fun `a ramp keeps the first and last frames and never runs backwards`() {
        val clip = retimed(span = 120, length = 60, srcIn = 30, ramp = SpeedRamps.easeIn(60))
        val shown = frames(clip)
        assertEquals(30L, shown.first())
        assertTrue(shown.last() in 140L..149L)
        assertEquals(shown.sorted(), shown)
        assertTrue(shown.all { it in 30L..149L })
    }

    @Test
    fun `an ease in ramp starts slower than it ends`() {
        val clip = retimed(span = 200, length = 100, ramp = SpeedRamps.easeIn(100))
        val shown = frames(clip)
        val early = shown[10] - shown[0]
        val late = shown[99] - shown[89]
        assertTrue("early $early late $late", early < late)
    }

    @Test
    fun `an ease out ramp starts faster than it ends`() {
        val clip = retimed(span = 200, length = 100, ramp = SpeedRamps.easeOut(100))
        val shown = frames(clip)
        assertTrue(shown[10] - shown[0] > shown[99] - shown[89])
    }

    @Test
    fun `a bell ramp is fastest in the middle`() {
        val clip = retimed(span = 200, length = 100, ramp = SpeedRamps.bell(100))
        val shown = frames(clip)
        val start = shown[10] - shown[0]
        val middle = shown[55] - shown[45]
        val end = shown[99] - shown[89]
        assertTrue(middle > start && middle > end)
    }

    @Test
    fun `a ramp that reverses reads the range from its end`() {
        val clip = retimed(span = 100, length = 50, srcIn = 0, reverse = true, ramp = SpeedRamps.bell(50))
        val shown = frames(clip)
        assertEquals(99L, shown.first())
        assertEquals(shown.sortedDescending(), shown)
    }

    @Test
    fun `ramp problems are reported`() {
        assertNull(SpeedRamps.problem(listOf(SpeedKey(0, 1000), SpeedKey(10, 500)), 20))
        assertEquals("speed key at 20 is outside the clip", SpeedRamps.problem(listOf(SpeedKey(20, 1000)), 20))
        assertEquals("speed keys must be in strictly increasing order", SpeedRamps.problem(listOf(SpeedKey(5, 1000), SpeedKey(5, 900)), 20))
        assertTrue(SpeedRamps.problem(listOf(SpeedKey(0, 10)), 20)!!.contains("weight"))
        assertTrue(SpeedRamps.problem(listOf(SpeedKey(0, 9000)), 20)!!.contains("weight"))
    }

    @Test
    fun `cropping keeps the shape of a ramp over the kept range`() {
        val ramp = listOf(SpeedKey(0, 400), SpeedKey(99, 1600))
        val cropped = SpeedRamps.cropped(ramp, 40, 80)
        assertEquals(0L, cropped.first().frame)
        assertEquals(39L, cropped.last().frame)
        assertEquals(SpeedRamps.weightAt(ramp, 40.0).toInt(), cropped.first().weightPermille)
        assertEquals(SpeedRamps.weightAt(ramp, 79.0).toInt(), cropped.last().weightPermille)
    }

    @Test
    fun `scaling a ramp stretches its keys and keeps them increasing`() {
        val scaled = SpeedRamps.scaled(listOf(SpeedKey(0, 500), SpeedKey(50, 1500), SpeedKey(99, 800)), 100, 25)
        assertEquals(listOf(0L, 12L, 24L), scaled.map { it.frame })
        val crushed = SpeedRamps.scaled(listOf(SpeedKey(0, 500), SpeedKey(1, 1500), SpeedKey(2, 800)), 3, 1)
        assertEquals(listOf(0L), crushed.map { it.frame })
    }

    @Test
    fun `speed limits accept 0 point 1x to 100x`() {
        assertNull(SpeedLimits.problem(1, 10))
        assertNull(SpeedLimits.problem(8, 1))
        assertNull(SpeedLimits.problem(100, 1))
        assertNull(SpeedLimits.problem(1, 1))
        assertTrue(SpeedLimits.problem(1, 11) != null)
        assertTrue(SpeedLimits.problem(1001, 10) != null)
        assertTrue(SpeedLimits.problem(0, 1) != null)
    }

    // ---- cropping: halves of a retimed clip tile exactly ----

    private fun assertTiles(clip: Clip, at: Long, tolerance: Long) {
        val left = clip.cropped(0, at)
        val right = clip.cropped(at, clip.durationFrames)
        assertEquals(clip.durationFrames, left.durationFrames + right.durationFrames)
        assertEquals(at, left.durationFrames)
        // The halves meet; when the cut is inside a held source frame (slow motion) both show it.
        val gap = if (clip.reverse) right.sourceOut - left.sourceIn else left.sourceOut - right.sourceIn
        assertTrue("halves meet (gap $gap)", gap == 0L || (gap == 1L && left.sourceSpan == 1L))
        if (clip.reverse) {
            assertEquals(clip.sourceIn, right.sourceIn)
            assertEquals(clip.sourceOut, left.sourceOut)
        } else {
            assertEquals(clip.sourceIn, left.sourceIn)
            assertEquals(clip.sourceOut, right.sourceOut)
        }
        val original = frames(clip)
        val together = frames(left) + frames(right)
        assertEquals(original.size, together.size)
        for (i in original.indices) {
            assertTrue("frame $i: ${original[i]} vs ${together[i]}", abs(original[i] - together[i]) <= tolerance)
        }
    }

    @Test
    fun `splitting a constant speed clip tiles its source exactly`() {
        for ((span, length) in listOf(100L to 50L, 30L to 20L, 10L to 20L, 97L to 41L, 7L to 100L)) {
            val clip = retimed(span, length, srcIn = 13)
            for (at in 1 until length) assertTiles(clip, at, tolerance = 1)
        }
    }

    @Test
    fun `splitting a reversed clip tiles its source exactly`() {
        val clip = retimed(span = 90, length = 45, srcIn = 5, reverse = true)
        for (at in 1L until 45L) assertTiles(clip, at, tolerance = 1)
    }

    @Test
    fun `splitting a ramped clip tiles its source`() {
        val clip = retimed(span = 150, length = 70, srcIn = 3, ramp = SpeedRamps.bell(70))
        for (at in 1L until 70L) assertTiles(clip, at, tolerance = 3)
        val reversed = retimed(span = 150, length = 70, srcIn = 3, reverse = true, ramp = SpeedRamps.easeIn(70))
        for (at in 1L until 70L) assertTiles(reversed, at, tolerance = 3)
    }

    @Test
    fun `splitting a freeze frame keeps both halves frozen on the same frame`() {
        val clip = retimed(span = 1, length = 30, srcIn = 8)
        val left = clip.cropped(0, 12)
        val right = clip.cropped(12, 30)
        assertEquals(12L, left.durationFrames)
        assertEquals(18L, right.durationFrames)
        assertEquals(f(8), left.sourceIn)
        assertEquals(f(8), right.sourceIn)
        assertTrue(left.isFreeze && right.isFreeze)
    }

    @Test
    fun `a stretch so slow it never reaches a new frame holds the current one`() {
        val clip = retimed(span = 3, length = 90)
        val piece = clip.cropped(10, 15) // 5 frames of a 0.033x stretch
        assertEquals(1L, piece.sourceSpan)
        assertEquals(5L, piece.durationFrames)
        assertTrue(piece.sourceIn >= f(0) && piece.sourceOut <= f(4))
    }

    @Test
    fun `growing a retimed clip continues its speed`() {
        val clip = retimed(span = 100, length = 50, srcIn = 20)
        val longer = clip.cropped(0, 80)
        assertEquals(f(20), longer.sourceIn)
        assertEquals(f(180), longer.sourceOut)
        assertEquals(80L, longer.durationFrames)
        val earlier = clip.cropped(-10, 50)
        assertEquals(f(0), earlier.sourceIn)
        assertEquals(60L, earlier.durationFrames)
    }

    @Test
    fun `cropping a plain clip is plain arithmetic on the source range`() {
        val clip = clip("c", 0, 100, srcIn = 40)
        val piece = clip.cropped(25, 60)
        assertEquals(f(65), piece.sourceIn)
        assertEquals(f(100), piece.sourceOut)
        assertNull(piece.retimedFrames)
        assertFalse(piece.isRetimed)
    }

    @Test
    fun `a length equal to the range is stored as no retime`() {
        val piece = retimed(span = 3, length = 2).cropped(0, 1) // 1.5x: one frame of 3 over 2, here 1 over 1
        assertEquals(1L, piece.sourceSpan)
        assertEquals(1L, piece.durationFrames)
        assertNull(piece.retimedFrames)
        assertNull(retimed(span = 50, length = 50).retimedFrames)
    }

    @Test
    fun `knots of a constant speed are just the two ends`() {
        val knots = retimed(span = 100, length = 40).retime.knots(maxStepFrames = 8)
        assertEquals(listOf(0L to 0.0, 40L to 100.0), knots)
    }

    @Test
    fun `knots of a ramp are close together and rise monotonically to the span`() {
        val knots = retimed(span = 100, length = 40, ramp = SpeedRamps.bell(40)).retime.knots(maxStepFrames = 8)
        assertEquals(0L, knots.first().first)
        assertEquals(40L, knots.last().first)
        assertEquals(100.0, knots.last().second, 1e-9)
        assertTrue(knots.zipWithNext().all { (a, b) -> b.first - a.first <= 8 && b.second > a.second })
    }
}
