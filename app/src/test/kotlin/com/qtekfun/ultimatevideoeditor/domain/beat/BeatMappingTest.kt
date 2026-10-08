package com.qtekfun.ultimatevideoeditor.domain.beat

import com.qtekfun.ultimatevideoeditor.domain.Clip
import com.qtekfun.ultimatevideoeditor.domain.FrameIndex
import com.qtekfun.ultimatevideoeditor.domain.FrameRate
import com.qtekfun.ultimatevideoeditor.domain.MarkerKind
import com.qtekfun.ultimatevideoeditor.domain.StillKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BeatMappingTest {
    private val fps = FrameRate(30, 1)

    /** A beat every half second (15 frames at 30 fps) for 10 seconds. */
    private val beats = (0..20).map { it * 500_000L }

    private fun frames(clip: Clip) = BeatMapping.markersFor(clip, beats, fps) { "b$it" }.map { it.frame.value }

    private fun media(start: Long, srcIn: Long, srcOut: Long) =
        Clip("c", "asset", FrameIndex(start), FrameIndex(srcIn), FrameIndex(srcOut))

    @Test
    fun `beats inside the trimmed range land at the clip's place on the timeline`() {
        // Source frames 30..119 are on the timeline from frame 100; beats at source 30, 45 ... 105.
        assertEquals(listOf(100L, 115L, 130L, 145L, 160L, 175L), frames(media(100, 30, 120)))
    }

    @Test
    fun `a beat on the very first and the last frame is kept and the one after the end is not`() {
        val clip = media(0, 0, 45)
        assertEquals(listOf(0L, 15L, 30L), frames(clip))
    }

    @Test
    fun `a reversed clip plays its beats backwards`() {
        val clip = media(100, 30, 120).copy(reverse = true)
        // Timeline frame t shows source 119 - t, so source 105 is at t = 14 and source 30 at t = 89.
        assertEquals(listOf(114L, 129L, 144L, 159L, 174L, 189L), frames(clip).sorted())
    }

    @Test
    fun `a sped up clip squeezes the beats together`() {
        val clip = media(0, 30, 120).copy(retimedFrames = 45)
        // 2x: timeline frame t shows source 30 + 2t, so beats 7.5 frames apart land on frames 0, 7 or 8 ...
        val result = frames(clip)
        assertEquals(6, result.size)
        assertTrue(result.zipWithNext { a, b -> b - a }.all { it in 7L..8L })
        assertEquals(0L, result.first())
    }

    @Test
    fun `a slowed clip spreads the beats out`() {
        val clip = media(0, 30, 60).copy(retimedFrames = 60)
        // 0.5x: 30 source frames over 60 timeline frames, beats at source 30 and 45 (and 60 is outside).
        assertEquals(listOf(0L, 30L), frames(clip))
    }

    @Test
    fun `clips without audible media get no markers`() {
        assertEquals(emptyList<Long>(), frames(Clip("t", null, FrameIndex(0), FrameIndex.ZERO, FrameIndex(60))))
        assertEquals(emptyList<Long>(), frames(Clip("s", "img", FrameIndex(0), FrameIndex.ZERO, FrameIndex(60), still = StillKind.PHOTO)))
        // A freeze holds one source frame, so beats do not repeat across it.
        assertEquals(emptyList<Long>(), frames(media(0, 30, 31).copy(retimedFrames = 40)))
    }

    @Test
    fun `markers are beat markers with the ids asked for`() {
        val markers = BeatMapping.markersFor(media(0, 0, 40), beats, fps) { "beat-$it" }
        assertTrue(markers.all { it.kind == MarkerKind.BEAT })
        assertEquals(listOf("beat-0", "beat-1", "beat-2"), markers.map { it.id })
    }

    @Test
    fun `a fractional frame rate rounds beat times to the nearest frame`() {
        val ntsc = FrameRate(30000, 1001)
        // 0.5 s is 14.985 frames: the beat belongs to frame 15.
        val markers = BeatMapping.markersFor(media(0, 0, 60), listOf(500_000L), ntsc) { "b$it" }
        assertEquals(listOf(15L), markers.map { it.frame.value })
    }
}
