package com.qtekfun.ultimatevideoeditor.domain

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimationTimingTest {
    private val fps30 = FrameRate(30, 1)

    // 100 ms, 200 ms, 300 ms: ends at 100, 300 and 600 ms.
    private val timing = AnimationTiming(listOf(100, 200, 300))

    @Test
    fun `the animation frame follows the project frame and loops`() {
        assertEquals(600_000L, timing.periodMicros)
        // 30 fps: clip frame f is at f * 33.33 ms.
        assertEquals(listOf(0, 0, 0, 1, 1, 1, 1, 1, 1, 2), (0L..9L).map { timing.frameIndexAt(it, fps30) })
        assertEquals(2, timing.frameIndexAt(17, fps30)) // 566 ms
        assertEquals(0, timing.frameIndexAt(18, fps30)) // 600 ms: second pass starts
        assertEquals(1, timing.frameIndexAt(21, fps30)) // 700 ms
        assertEquals(timing.frameIndexAt(5, fps30), timing.frameIndexAt(5 + 18 * 7, fps30))
    }

    @Test
    fun `an edge falls on the later frame so a frame is shown for exactly its delay`() {
        // Exactly 100 ms (clip frame 3 at 30 fps) belongs to the second frame.
        assertEquals(0, timing.indexAtMicros(99_999))
        assertEquals(1, timing.indexAtMicros(100_000))
        assertEquals(1, timing.indexAtMicros(299_999))
        assertEquals(2, timing.indexAtMicros(300_000))
        assertEquals(2, timing.indexAtMicros(599_999))
    }

    @Test
    fun `segments merge frames that show the same picture and cover the range exactly`() {
        assertEquals(
            listOf(
                AnimationSegment(0, 3, 0),
                AnimationSegment(3, 9, 1),
                AnimationSegment(9, 18, 2),
                AnimationSegment(18, 19, 0),
            ),
            timing.segments(0, 19, fps30),
        )
        assertEquals(emptyList<AnimationSegment>(), timing.segments(4, 4, fps30))
        assertEquals(listOf(AnimationSegment(5, 6, 1)), timing.segments(5, 6, fps30))
        // A range that starts mid-animation (a transition that begins before the clip's own start).
        assertEquals(listOf(AnimationSegment(4, 9, 1), AnimationSegment(9, 12, 2)), timing.segments(4, 12, fps30))
    }

    @Test
    fun `segments agree with the frame by frame answer for random animations and rates`() {
        val rng = Random(7)
        val rates = listOf(FrameRate(24000, 1001), FrameRate(25, 1), FrameRate(30000, 1001), FrameRate(60, 1), FrameRate(120, 1))
        repeat(200) {
            val delays = List(2 + rng.nextInt(9)) { 11 + rng.nextInt(700) }
            val t = AnimationTiming(delays)
            val fps = rates[rng.nextInt(rates.size)]
            val from = rng.nextInt(50).toLong()
            val to = from + 1 + rng.nextInt(400)
            val segments = t.segments(from, to, fps)
            assertEquals(from, segments.first().startFrame)
            assertEquals(to, segments.last().endFrame)
            for ((a, b) in segments.zipWithNext()) {
                assertEquals(a.endFrame, b.startFrame)
                assertTrue("neighbours must differ so they were merged", a.index != b.index)
            }
            for (s in segments) {
                assertTrue(s.endFrame > s.startFrame)
                for (f in s.startFrame until s.endFrame) assertEquals(s.index, t.frameIndexAt(f, fps))
            }
        }
    }

    @Test
    fun `a single frame never moves and one pass can be a single project frame long`() {
        val still = AnimationTiming(listOf(500))
        assertFalse(still.isAnimated)
        assertEquals(listOf(AnimationSegment(0, 90, 0)), still.segments(0, 90, fps30))
        val fast = AnimationTiming(listOf(11, 11))
        assertTrue(fast.isAnimated)
        assertEquals(2, fast.frameCount)
    }

    @Test
    fun `delays of ten milliseconds or less count as a tenth of a second like in browsers`() {
        assertEquals(100, AnimationTiming.effectiveDelay(0))
        assertEquals(100, AnimationTiming.effectiveDelay(10))
        assertEquals(11, AnimationTiming.effectiveDelay(11))
        assertEquals(20, AnimationTiming.effectiveDelay(20))
        assertEquals(AnimationTiming.MAX_DELAY_MS, AnimationTiming.effectiveDelay(Int.MAX_VALUE))
        assertEquals(listOf(100, 20, 300), AnimationTiming.ofRaw(listOf(0, 20, 300))!!.delaysMs)
    }

    @Test
    fun `a finite number of plays holds the last frame afterwards`() {
        val twice = AnimationTiming(listOf(100, 200, 300), plays = 2)
        // 30 fps: a pass is 18 frames; the second pass ends at frame 36 (1200 ms).
        assertEquals(1, twice.frameIndexAt(21, fps30)) // second pass still plays
        assertEquals(2, twice.frameIndexAt(35, fps30)) // 1166 ms: second pass, last frame
        assertEquals(2, twice.frameIndexAt(36, fps30)) // 1200 ms: held on the last frame
        assertEquals(2, twice.frameIndexAt(500, fps30))
        val once = AnimationTiming(listOf(100, 200, 300), plays = 1)
        assertEquals(listOf(0, 1, 2, 2), listOf(0L, 3L, 9L, 18L).map { once.frameIndexAt(it, fps30) })
        // 0 plays means forever: the same as the default.
        assertEquals(timing.frameIndexAt(18 * 50, fps30), AnimationTiming(listOf(100, 200, 300), 0).frameIndexAt(18 * 50, fps30))
    }

    @Test
    fun `segments of a finite animation end in one long segment of the last frame`() {
        val once = AnimationTiming(listOf(100, 200, 300), plays = 1)
        assertEquals(
            listOf(AnimationSegment(0, 3, 0), AnimationSegment(3, 9, 1), AnimationSegment(9, 100, 2)),
            once.segments(0, 100, fps30),
        )
        val twice = AnimationTiming(listOf(100, 200, 300), plays = 2)
        val s = twice.segments(0, 90, fps30)
        assertEquals(2, s.last().index)
        for (f in 0L until 90L) assertEquals(twice.frameIndexAt(f, fps30), s.first { f >= it.startFrame && f < it.endFrame }.index)
    }

    @Test
    fun `plays from a file are clamped and negative plays are refused`() {
        assertEquals(3, AnimationTiming.ofRaw(listOf(100, 100), 3)!!.plays)
        assertEquals(AnimationTiming.MAX_PLAYS, AnimationTiming.ofRaw(listOf(100, 100), Int.MAX_VALUE)!!.plays)
        assertEquals(AnimationTiming.INFINITE, AnimationTiming.ofRaw(listOf(100, 100))!!.plays)
        assertThrows(IllegalArgumentException::class.java) { AnimationTiming(listOf(100, 100), -1) }
    }

    @Test
    fun `fewer than two frames is not an animation`() {
        assertNull(AnimationTiming.ofRaw(emptyList()))
        assertNull(AnimationTiming.ofRaw(listOf(80)))
    }

    @Test
    fun `invalid timings and clip frames are refused`() {
        assertThrows(IllegalArgumentException::class.java) { AnimationTiming(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { AnimationTiming(listOf(100, 0)) }
        assertThrows(IllegalArgumentException::class.java) { AnimationTiming(listOf(100, AnimationTiming.MAX_DELAY_MS + 1)) }
        assertThrows(IllegalArgumentException::class.java) { timing.frameIndexAt(-1, fps30) }
        assertThrows(IllegalArgumentException::class.java) { timing.segments(5, 4, fps30) }
    }
}
