package com.ultimatevideo.uveditor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioToolsTest {

    private fun scene() = timeline(
        track("v1", clip("a", 0, 100), clip("b", 100, 60)),
        track("a1", clip("m", 0, 200), type = TrackType.AUDIO),
    )

    private val tools = ClipAudio(
        pan = -0.4,
        fadeInFrames = 10,
        fadeOutFrames = 12,
        eq = ClipEq(highPassHz = 90.0).withBand(2, EqBand(3000.0, 4.0, 1.5)),
        denoise = Denoise(0.6, List(Denoise.BINS) { 0.01f }),
        normalizeDb = -3.5,
        targetLufs = -16.0,
    )

    // region values

    @Test
    fun `the neutral settings are neutral and everything else is not`() {
        assertTrue(ClipAudio.NONE.isNeutral && TrackAudio.NONE.isNeutral && ClipEq.FLAT.isFlat)
        assertFalse(ClipAudio(pan = 0.1).isNeutral)
        assertFalse(ClipEq(lowPassHz = 8000.0).isFlat)
        assertFalse(ClipEq().withBand(1, EqBand(400.0, 3.0)).isFlat)
        assertFalse(TrackAudio(mute = true).isNeutral)
    }

    @Test
    fun `clip audio values are range checked`() {
        assertNull(tools.problem(100))
        assertNull(ClipAudio.NONE.problem(1))
        assertTrue(ClipAudio(pan = 1.5).problem(100) != null)
        assertTrue(ClipAudio(pan = Double.NaN).problem(100) != null)
        assertTrue(ClipAudio(fadeInFrames = 11).problem(10) != null)
        assertTrue(ClipAudio(fadeOutFrames = -1).problem(10) != null)
        assertTrue(ClipAudio(normalizeDb = 50.0).problem(10) != null)
        assertTrue(ClipAudio(targetLufs = 5.0).problem(10) != null)
        assertTrue(ClipAudio(eq = ClipEq(highPassHz = 5.0)).problem(10) != null)
        assertTrue(ClipAudio(eq = ClipEq().withBand(0, EqBand(100.0, 30.0))).problem(10) != null)
        assertTrue(ClipAudio(eq = ClipEq().withBand(0, EqBand(100.0, 3.0, 0.0))).problem(10) != null)
        assertTrue(ClipAudio(eq = ClipEq(bands = ClipEq.DEFAULT_BANDS.take(2))).problem(10) != null)
        assertTrue(ClipAudio(denoise = Denoise(0.0, List(Denoise.BINS) { 0f })).problem(10) != null)
        assertTrue(ClipAudio(denoise = Denoise(0.5, List(10) { 0f })).problem(10) != null)
        assertTrue(ClipAudio(denoise = Denoise(0.5, List(Denoise.BINS) { -1f })).problem(10) != null)
    }

    @Test
    fun `track audio and ducking values are range checked`() {
        assertNull(TrackAudio(volumeDb = -12.0, compressor = BusCompressor()).problem())
        assertTrue(TrackAudio(volumeDb = 40.0).problem() != null)
        assertTrue(TrackAudio(compressor = BusCompressor(ratio = 0.5)).problem() != null)
        assertTrue(TrackAudio(compressor = BusCompressor(thresholdDb = 5.0)).problem() != null)
        assertNull(Ducking().problem())
        assertTrue(Ducking(amountDb = 60.0).problem() != null)
        assertTrue(Ducking(attackMs = 0.0).problem() != null)
        assertTrue(Ducking(thresholdDb = 3.0).problem() != null)
    }

    @Test
    fun `solo silences every other track`() {
        val t = timeline(
            track("v1"),
            track("a1", type = TrackType.AUDIO),
            track("a2", type = TrackType.AUDIO),
        )
        assertTrue(t.tracks.all { t.isTrackAudible(it) })

        val soloed = TimelineOps.setTrackAudio(t, "a1", TrackAudio(solo = true)).getOrFail()
        assertTrue(soloed.anySolo)
        assertTrue(soloed.isTrackAudible(soloed.track("a1")!!))
        assertFalse(soloed.isTrackAudible(soloed.track("v1")!!))
        assertFalse(soloed.isTrackAudible(soloed.track("a2")!!))

        // Mute wins over solo, and a mute alone only silences that track.
        val both = TimelineOps.setTrackAudio(soloed, "a1", TrackAudio(solo = true, mute = true)).getOrFail()
        assertFalse(both.isTrackAudible(both.track("a1")!!))
        val muted = TimelineOps.setTrackAudio(t, "a2", TrackAudio(mute = true)).getOrFail()
        assertTrue(muted.isTrackAudible(muted.track("a1")!!))
        assertFalse(muted.isTrackAudible(muted.track("a2")!!))
    }

    // endregion

    // region operations and undo

    @Test
    fun `clip audio is set, validated and undone as one step`() {
        val history = EditHistory(scene())
        val applied = history.execute(EditCommand.SetClipAudio("a", tools)).getOrFail()
        assertEquals(tools, applied.timeline.trackOfClip("a")!!.clip("a")!!.audio)
        assertEquals(ClipAudio.NONE, applied.timeline.trackOfClip("b")!!.clip("b")!!.audio)
        assertEquals(scene(), applied.undo().timeline)
        assertEquals(tools, applied.undo().redo().timeline.trackOfClip("a")!!.clip("a")!!.audio)

        assertTrue(TimelineOps.setClipAudio(scene(), "a", ClipAudio(pan = 2.0)).errorOrFail() is EditError.InvalidAudio)
        assertTrue(TimelineOps.setClipAudio(scene(), "a", ClipAudio(fadeInFrames = 101)).errorOrFail() is EditError.InvalidAudio)
        assertEquals(EditError.ClipNotFound("zz"), TimelineOps.setClipAudio(scene(), "zz", tools).errorOrFail())
    }

    @Test
    fun `a title has no sound settings`() {
        val t = timeline(track("t1", clip("title", 0, 30, asset = null).copy(title = TitleContent("Hi")), type = TrackType.TITLE))
        assertTrue(TimelineOps.setClipAudio(t, "title", ClipAudio(pan = 0.5)).errorOrFail() is EditError.InvalidAudio)
    }

    @Test
    fun `track audio and ducking are set and undone`() {
        val history = EditHistory(scene())
        val track = TrackAudio(volumeDb = -9.0, role = AudioRole.MUSIC, compressor = BusCompressor(thresholdDb = -24.0))
        val a = history.execute(EditCommand.SetTrackAudio("a1", track)).getOrFail()
        assertEquals(track, a.timeline.track("a1")!!.audio)
        assertEquals(TrackAudio.NONE, a.timeline.track("v1")!!.audio)
        val b = a.execute(EditCommand.SetDucking(Ducking(amountDb = 12.0))).getOrFail()
        assertEquals(12.0, b.timeline.ducking!!.amountDb, 0.0)
        assertNull(b.execute(EditCommand.SetDucking(null)).getOrFail().timeline.ducking)
        assertEquals(scene(), b.undo().undo().timeline)

        assertEquals(EditError.TrackNotFound("zz"), TimelineOps.setTrackAudio(scene(), "zz", track).errorOrFail())
        assertTrue(TimelineOps.setTrackAudio(scene(), "a1", TrackAudio(volumeDb = 99.0)).errorOrFail() is EditError.InvalidAudio)
        assertTrue(TimelineOps.setDucking(scene(), Ducking(amountDb = 99.0)).errorOrFail() is EditError.InvalidAudio)
    }

    // endregion

    // region cutting keeps the settings sensible

    @Test
    fun `split keeps pan, EQ and noise suppression on both halves and puts each fade on its own side`() {
        val t = TimelineOps.setClipAudio(scene(), "m", tools.copy(fadeInFrames = 20, fadeOutFrames = 30)).getOrFail()
        val cut = TimelineOps.split(t, "a1", FrameIndex(80), "m2").getOrFail()
        val left = cut.trackOfClip("m")!!.clip("m")!!.audio
        val right = cut.trackOfClip("m2")!!.clip("m2")!!.audio
        assertEquals(tools.pan, left.pan, 0.0)
        assertEquals(tools.pan, right.pan, 0.0)
        assertEquals(tools.eq, left.eq)
        assertEquals(tools.eq, right.eq)
        assertEquals(tools.denoise, left.denoise)
        assertEquals(tools.denoise, right.denoise)
        assertEquals(tools.normalizeDb, right.normalizeDb, 0.0)
        // The fade-in belongs to the start of the original clip, the fade-out to its end.
        assertEquals(20L, left.fadeInFrames)
        assertEquals(0L, left.fadeOutFrames)
        assertEquals(0L, right.fadeInFrames)
        assertEquals(30L, right.fadeOutFrames)
    }

    @Test
    fun `a fade handle longer than a short half is clipped to it`() {
        val t = TimelineOps.setClipAudio(scene(), "m", ClipAudio(fadeInFrames = 50)).getOrFail()
        val cut = TimelineOps.split(t, "a1", FrameIndex(30), "m2").getOrFail()
        val left = cut.trackOfClip("m")!!.clip("m")!!
        assertEquals(30L, left.audio.fadeInFrames) // shortened to the 30-frame half
        assertNull(left.audio.problem(left.durationFrames))
    }

    @Test
    fun `a fade handle follows its edge when the clip is trimmed and never outgrows the clip`() {
        val t = TimelineOps.setClipAudio(scene(), "a", ClipAudio(fadeInFrames = 30, fadeOutFrames = 10)).getOrFail()
        val trimmed = TimelineOps.trim(t, "a", TrimEdge.START, FrameIndex(12)).getOrFail().trackOfClip("a")!!.clip("a")!!
        assertEquals(30L, trimmed.audio.fadeInFrames)
        assertEquals(10L, trimmed.audio.fadeOutFrames)
        val shorter = TimelineOps.trim(t, "a", TrimEdge.END, FrameIndex(95)).getOrFail().trackOfClip("a")!!.clip("a")!!
        assertEquals(30L, shorter.audio.fadeInFrames)
        assertEquals(10L, shorter.audio.fadeOutFrames)
        // Trimmed down to 8 frames, both handles are clipped to what is left.
        val tiny = TimelineOps.trim(t, "a", TrimEdge.END, FrameIndex(8)).getOrFail().trackOfClip("a")!!.clip("a")!!
        assertEquals(8L, tiny.audio.fadeInFrames)
        assertEquals(8L, tiny.audio.fadeOutFrames)
        assertNull(tiny.audio.problem(tiny.durationFrames))
    }

    @Test
    fun `overwriting the middle of a clip keeps settings on both remains and the fades on the outer ends`() {
        val t = TimelineOps.setClipAudio(scene(), "m", tools.copy(fadeInFrames = 20, fadeOutFrames = 30)).getOrFail()
        val over = TimelineOps.overwrite(t, "a1", clip("n", 60, 40, asset = "z")).getOrFail()
        val clips = over.track("a1")!!.clips
        assertEquals(3, clips.size)
        val (left, _, right) = clips
        assertEquals(20L, left.audio.fadeInFrames)
        assertEquals(0L, left.audio.fadeOutFrames)
        assertEquals(0L, right.audio.fadeInFrames)
        assertEquals(30L, right.audio.fadeOutFrames)
        assertEquals(tools.eq, left.audio.eq)
        assertEquals(tools.eq, right.audio.eq)
        assertEquals(emptyList<String>(), over.invariantViolations())
    }

    @Test
    fun `settings survive moving a clip`() {
        val t = TimelineOps.setClipAudio(scene(), "b", tools.copy(fadeInFrames = 5, fadeOutFrames = 5)).getOrFail()
        val moved = TimelineOps.move(t, "b", FrameIndex(300)).getOrFail().trackOfClip("b")!!.clip("b")!!
        assertEquals(FrameIndex(300), moved.timelineStart)
        assertEquals(tools.copy(fadeInFrames = 5, fadeOutFrames = 5), moved.audio)
    }

    // endregion
}
