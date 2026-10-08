package com.qtekfun.ultimatevideoeditor.ui.editor

import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto
import com.qtekfun.ultimatevideoeditor.domain.Clip
import com.qtekfun.ultimatevideoeditor.domain.FrameIndex
import com.qtekfun.ultimatevideoeditor.domain.FrameRate
import com.qtekfun.ultimatevideoeditor.domain.SpeedRamps
import com.qtekfun.ultimatevideoeditor.domain.TimelineOps
import com.qtekfun.ultimatevideoeditor.domain.TitleContent
import com.qtekfun.ultimatevideoeditor.domain.Transition
import com.qtekfun.ultimatevideoeditor.domain.getOrFail
import com.qtekfun.ultimatevideoeditor.domain.Timeline
import com.qtekfun.ultimatevideoeditor.domain.TrackType
import com.qtekfun.ultimatevideoeditor.domain.clip
import com.qtekfun.ultimatevideoeditor.domain.timeline
import com.qtekfun.ultimatevideoeditor.domain.track
import com.qtekfun.ultimatevideoeditor.engine.audio.RetimeKnot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSnapshotMappingTest {

    private fun asset(id: String, hasAudio: Boolean) =
        MediaAssetDto(id, "content://$id", 300, 30, 1, "Rec709-SDR", hasVideo = true, hasAudio = hasAudio)

    private val keys = KeyRegistry()
    private val assetKeys = KeyRegistry()

    private fun snapshot(tl: Timeline, vararg assets: MediaAssetDto) =
        audioSnapshotOf(tl, assets.toList(), FrameRate(30, 1), keys::keyFor, assetKeys::keyFor)

    @Test
    fun `video clips with audio and audio track clips are both audible`() {
        val tl = timeline(
            track("v1", clip("c1", 0, 100, srcIn = 10, asset = "a")),
            track("a1", clip("c2", 50, 40, srcIn = 5, asset = "b"), type = TrackType.AUDIO),
        )

        val spec = snapshot(tl, asset("a", true), asset("b", true))

        assertEquals(2, spec.clips.size)
        val first = spec.clips.first { it.clipKey == keys.keyFor("c1") }
        assertEquals(0L, first.startFrame)
        assertEquals(100L, first.durationFrames)
        assertEquals(10L, first.sourceInFrame)
        assertEquals(30 to 1, first.sourceFpsNum to first.sourceFpsDen)
        assertEquals(assetKeys.keyFor("a"), first.assetKey)
    }

    @Test
    fun `clip gain reaches the mixer`() {
        val tl = timeline(
            track("v1", clip("c1", 0, 100, asset = "a").copy(gainDb = -6.0), clip("c2", 100, 50, asset = "a")),
        )

        val spec = snapshot(tl, asset("a", true))

        assertEquals(-6f, spec.clips.first { it.clipKey == keys.keyFor("c1") }.gainDb, 0f)
        assertEquals(0f, spec.clips.first { it.clipKey == keys.keyFor("c2") }.gainDb, 0f)
    }

    @Test
    fun `clips of media without audio and clips without media are silent`() {
        val tl = timeline(
            track("v1", clip("c1", 0, 100, asset = "mute"), clip("t1", 100, 10, asset = null)),
        )

        assertTrue(snapshot(tl, asset("mute", false)).clips.isEmpty())
    }

    @Test
    fun `a transition overlaps the clips and fades them`() {
        val base = timeline(track("v1", clip("c1", 0, 100, asset = "a"), clip("c2", 100, 100, srcIn = 50, asset = "a")))
        val tl = TimelineOps.addTransition(base, Transition("t", "c1", "c2", 10)).getOrFail()

        val spec = snapshot(tl, asset("a", true))

        val out = spec.clips.first { it.clipKey == keys.keyFor("c1") }
        val incoming = spec.clips.first { it.clipKey == keys.keyFor("c2") }
        assertEquals(0L to 105L, out.startFrame to (out.startFrame + out.durationFrames))
        assertEquals(10L, out.fadeOutFrames)
        assertEquals(0L, out.fadeInFrames)
        assertEquals(95L to 200L, incoming.startFrame to (incoming.startFrame + incoming.durationFrames))
        assertEquals(45L, incoming.sourceInFrame)
        assertEquals(10L, incoming.fadeInFrames)
        // Both fades cover the same frames: 95 until 105.
        assertEquals(out.startFrame + out.durationFrames - out.fadeOutFrames, incoming.startFrame)
        assertEquals(incoming.startFrame + incoming.fadeInFrames, out.startFrame + out.durationFrames)
    }

    @Test
    fun `titles are never audible`() {
        val title = Clip("T", null, FrameIndex(0), FrameIndex(0), FrameIndex(50), title = TitleContent("x"))
        val tl = timeline(track("t1", title, type = TrackType.TITLE))

        assertTrue(snapshot(tl).clips.isEmpty())
    }

    private fun retimed(vararg edits: (Timeline) -> Timeline): Timeline =
        edits.fold(timeline(track("v1", clip("c1", 0, 100, srcIn = 20, asset = "a")))) { tl, edit -> edit(tl) }

    private fun speed(num: Long, den: Long) = { tl: Timeline -> TimelineOps.setSpeed(tl, "c1", num, den).getOrFail() }

    private fun reverse() = { tl: Timeline -> TimelineOps.setReverse(tl, "c1", true).getOrFail() }

    @Test
    fun `a fast clip sends the mapping instead of a source in point`() {
        val spec = snapshot(retimed(speed(2, 1)), asset("a", true)).clips.single()

        assertEquals(50L, spec.durationFrames)
        assertEquals(listOf(RetimeKnot(0, 20.0), RetimeKnot(50, 120.0)), spec.retimeKnots)
    }

    @Test
    fun `a reversed clip's positions fall from its end`() {
        val spec = snapshot(retimed(reverse()), asset("a", true)).clips.single()

        assertEquals(listOf(RetimeKnot(0, 120.0), RetimeKnot(100, 20.0)), spec.retimeKnots)
    }

    @Test
    fun `speeds outside the audible range are muted`() {
        assertEquals(1, snapshot(retimed(speed(4, 1)), asset("a", true)).clips.size)
        assertEquals(1, snapshot(retimed(speed(1, 4)), asset("a", true)).clips.size)
        assertTrue(snapshot(retimed(speed(5, 1)), asset("a", true)).clips.isEmpty())
        assertTrue(snapshot(retimed(speed(1, 5)), asset("a", true)).clips.isEmpty())
        assertTrue(snapshot(retimed(speed(8, 1)), asset("a", true)).clips.isEmpty())
    }

    @Test
    fun `a freeze frame is silent`() {
        val tl = TimelineOps.freezeFrame(timeline(track("v1", clip("c1", 0, 100, asset = "a"))), "v1", FrameIndex(40), 30, "fz", "c2").getOrFail()

        val spec = snapshot(tl, asset("a", true))

        assertEquals(setOf(keys.keyFor("c1"), keys.keyFor("c2")), spec.clips.map { it.clipKey }.toSet())
    }

    @Test
    fun `a ramp is sampled and one too fast stretch mutes the clip`() {
        val gentle = { tl: Timeline -> TimelineOps.setSpeedRamp(tl, "c1", SpeedRamps.bell(100)).getOrFail() }
        val knots = snapshot(retimed(gentle), asset("a", true)).clips.single().retimeKnots
        assertTrue(knots.size > 8)
        assertEquals(0L, knots.first().frame)
        assertEquals(100L, knots.last().frame)
        assertEquals(120.0, knots.last().sourceFrame, 1e-9)
        assertTrue(knots.zipWithNext().all { (a, b) -> b.sourceFrame > a.sourceFrame })
        // The same ramp on a 3x clip peaks at 4.8 source frames per frame: too fast to play.
        assertTrue(snapshot(retimed(speed(3, 1), { tl -> TimelineOps.setSpeedRamp(tl, "c1", SpeedRamps.bell(33)).getOrFail() }), asset("a", true)).clips.isEmpty())
    }

    @Test
    fun `a transition tail of a retimed clip continues its speed`() {
        val base = timeline(track("v1", clip("c1", 0, 100, asset = "a"), clip("c2", 100, 100, srcIn = 40, asset = "a")))
        val fast = TimelineOps.setSpeed(base, "c1", 2, 1, ripple = true).getOrFail() // c1: 0..50 over source 0..100
        val tl = TimelineOps.addTransition(fast, Transition("t", "c1", "c2", 10), outgoingSourceLength = 300).getOrFail()

        val out = snapshot(tl, asset("a", true)).clips.first { it.clipKey == keys.keyFor("c1") }

        // c1 plays 5 frames past its end at 2x: source 100..110.
        assertEquals(55L, out.durationFrames)
        assertEquals(listOf(RetimeKnot(0, 0.0), RetimeKnot(55, 110.0)), out.retimeKnots)
    }

    @Test
    fun `clip keys match the keys of the timeline canvas`() {
        val tl = timeline(track("v1", clip("c1", 0, 100, asset = "a")))

        val spec = snapshot(tl, asset("a", true))

        assertEquals(keys.keyFor("c1"), spec.clips.single().clipKey)
    }


    // region audio tools

    private fun withAudio(tl: Timeline, clipId: String, audio: com.qtekfun.ultimatevideoeditor.domain.ClipAudio) =
        TimelineOps.setClipAudio(tl, clipId, audio).getOrFail()

    @Test
    fun `tracks reach the mixer in timeline order and clips point at their own track`() {
        val tl = timeline(
            track("t1", clip("title", 0, 30, asset = null).copy(title = TitleContent("x")), type = TrackType.TITLE),
            track("v1", clip("c1", 0, 100, asset = "a")),
            track("a1", clip("c2", 0, 100, asset = "b"), type = TrackType.AUDIO),
        )

        val spec = snapshot(tl, asset("a", true), asset("b", true))

        assertEquals(2, spec.tracks.size) // the title track carries no sound
        assertEquals(0, spec.clips.first { it.clipKey == keys.keyFor("c1") }.trackIndex)
        assertEquals(1, spec.clips.first { it.clipKey == keys.keyFor("c2") }.trackIndex)
        assertEquals(stableTrackKey("v1"), spec.tracks[0].trackKey)
        assertEquals(stableTrackKey("a1"), spec.tracks[1].trackKey)
        assertEquals(stableTrackKey("v1"), stableTrackKey("v1"))
    }

    @Test
    fun `track volume, mute, solo, role and compressor are folded into the track specs`() {
        val base = timeline(
            track("v1", clip("c1", 0, 100, asset = "a")),
            track("a1", clip("c2", 0, 100, asset = "b"), type = TrackType.AUDIO),
            track("a2", clip("c3", 0, 100, asset = "b"), type = TrackType.AUDIO),
        )
        val tl = TimelineOps.setTrackAudio(base, "a1", com.qtekfun.ultimatevideoeditor.domain.TrackAudio(volumeDb = -9.0, role = com.qtekfun.ultimatevideoeditor.domain.AudioRole.VOICE, compressor = com.qtekfun.ultimatevideoeditor.domain.BusCompressor(-20.0, 4.0, 5.0, 80.0, 2.0))).getOrFail()
        val spec = snapshot(tl, asset("a", true), asset("b", true))

        assertEquals(-9f, spec.tracks[1].gainDb, 0f)
        assertEquals(com.qtekfun.ultimatevideoeditor.engine.audio.TrackRole.VOICE, spec.tracks[1].role)
        assertEquals(com.qtekfun.ultimatevideoeditor.engine.audio.CompressorSpec(-20f, 4f, 5f, 80f, 2f), spec.tracks[1].compressor)
        assertTrue(spec.tracks.none { it.muted })

        // Soloing one track mutes the others (the mixer ramps the change).
        val soloed = TimelineOps.setTrackAudio(tl, "a2", com.qtekfun.ultimatevideoeditor.domain.TrackAudio(solo = true)).getOrFail()
        val s = snapshot(soloed, asset("a", true), asset("b", true))
        assertEquals(listOf(true, true, false), s.tracks.map { it.muted })
        // An explicit mute works on its own.
        val muted = TimelineOps.setTrackAudio(base, "a2", com.qtekfun.ultimatevideoeditor.domain.TrackAudio(mute = true)).getOrFail()
        assertEquals(listOf(false, false, true), snapshot(muted, asset("a", true), asset("b", true)).tracks.map { it.muted })
    }

    @Test
    fun `ducking is forwarded only when it has an amount`() {
        val base = timeline(track("a1", clip("c", 0, 100, asset = "a"), type = TrackType.AUDIO))
        assertNull(snapshot(base, asset("a", true)).ducking)
        val on = TimelineOps.setDucking(base, com.qtekfun.ultimatevideoeditor.domain.Ducking(12.0, -30.0, 25.0, 500.0)).getOrFail()
        assertEquals(com.qtekfun.ultimatevideoeditor.engine.audio.DuckingSpec(12f, -30f, 25f, 500f), snapshot(on, asset("a", true)).ducking)
        val zero = TimelineOps.setDucking(base, com.qtekfun.ultimatevideoeditor.domain.Ducking(0.0)).getOrFail()
        assertNull(snapshot(zero, asset("a", true)).ducking)
    }

    @Test
    fun `pan, EQ, noise suppression and the normalise gain reach the clip spec`() {
        val audio = com.qtekfun.ultimatevideoeditor.domain.ClipAudio(
            pan = -0.5,
            eq = com.qtekfun.ultimatevideoeditor.domain.ClipEq(highPassHz = 80.0).withBand(3, com.qtekfun.ultimatevideoeditor.domain.EqBand(6000.0, -4.0, 2.0)),
            denoise = com.qtekfun.ultimatevideoeditor.domain.Denoise(0.7, List(com.qtekfun.ultimatevideoeditor.domain.Denoise.BINS) { 0.02f }),
            normalizeDb = 3.0,
            targetLufs = -16.0,
        )
        val tl = withAudio(timeline(track("v1", clip("c", 0, 100, asset = "a").copy(gainDb = -5.0))), "c", audio)

        val c = snapshot(tl, asset("a", true)).clips.single()

        assertEquals(-0.5f, c.pan, 0f)
        assertEquals(80f, c.eq.highPassHz, 0f)
        assertEquals(com.qtekfun.ultimatevideoeditor.engine.audio.EqBandSpec(6000f, -4f, 2f), c.eq.bands[3])
        assertEquals(0.7f, c.denoiseStrength, 1e-6f)
        assertEquals(com.qtekfun.ultimatevideoeditor.domain.Denoise.BINS, c.noiseProfile.size)
        assertEquals(-2f, c.gainDb, 1e-6f) // volume -5 dB plus 3 dB of normalisation
    }

    @Test
    fun `a flat EQ and no noise suppression send nothing extra`() {
        val tl = timeline(track("v1", clip("c", 0, 100, asset = "a")))
        val c = snapshot(tl, asset("a", true)).clips.single()
        assertEquals(com.qtekfun.ultimatevideoeditor.engine.audio.EqSpec.FLAT, c.eq)
        assertEquals(0f, c.denoiseStrength, 0f)
        assertTrue(c.noiseProfile.isEmpty())
        assertEquals(0f, c.pan, 0f)
        assertEquals(0L to 0L, c.userFadeInFrames to c.userFadeOutFrames)
    }

    @Test
    fun `fade handles become the clips own fades and the gain is clamped`() {
        val tl = withAudio(
            timeline(track("v1", clip("c", 0, 100, asset = "a").copy(gainDb = 20.0))),
            "c",
            com.qtekfun.ultimatevideoeditor.domain.ClipAudio(fadeInFrames = 12, fadeOutFrames = 30, normalizeDb = 20.0),
        )
        val c = snapshot(tl, asset("a", true)).clips.single()

        assertEquals(12L, c.userFadeInFrames)
        assertEquals(30L, c.userFadeOutFrames)
        assertEquals(24f, c.gainDb, 0f) // 20 + 20 dB would be 40: clamped to what the mixer accepts
    }

    @Test
    fun `the fade shape goes to the mixer and the default stays equal power`() {
        fun shapeOf(shape: com.qtekfun.ultimatevideoeditor.domain.FadeShape) = snapshot(
            withAudio(
                timeline(track("v1", clip("c", 0, 100, asset = "a"))), "c",
                com.qtekfun.ultimatevideoeditor.domain.ClipAudio(fadeInFrames = 10, fadeShape = shape),
            ),
            asset("a", true),
        ).clips.single().fadeShape
        assertEquals(0, shapeOf(com.qtekfun.ultimatevideoeditor.domain.FadeShape.EQUAL_POWER))
        assertEquals(1, shapeOf(com.qtekfun.ultimatevideoeditor.domain.FadeShape.LINEAR))
        assertEquals(2, shapeOf(com.qtekfun.ultimatevideoeditor.domain.FadeShape.LOGARITHMIC))
    }

    @Test
    fun `where a transition already ramps an edge the clip fade handle is dropped`() {
        val base = timeline(track("v1", clip("c1", 0, 100, asset = "a"), clip("c2", 100, 100, srcIn = 50, asset = "a")))
        val faded = withAudio(withAudio(base, "c1", com.qtekfun.ultimatevideoeditor.domain.ClipAudio(fadeInFrames = 10, fadeOutFrames = 10)), "c2", com.qtekfun.ultimatevideoeditor.domain.ClipAudio(fadeInFrames = 10, fadeOutFrames = 10))
        val tl = TimelineOps.addTransition(faded, Transition("t", "c1", "c2", 10)).getOrFail()

        val spec = snapshot(tl, asset("a", true))
        val out = spec.clips.first { it.clipKey == keys.keyFor("c1") }
        val incoming = spec.clips.first { it.clipKey == keys.keyFor("c2") }

        assertEquals(10L, out.userFadeInFrames)   // the start of c1 is not part of the transition
        assertEquals(0L, out.userFadeOutFrames)   // its end is: the crossfade is the fade
        assertEquals(0L, incoming.userFadeInFrames)
        assertEquals(10L, incoming.userFadeOutFrames)
        assertTrue(out.fadeOutFrames > 0 && incoming.fadeInFrames > 0)
    }

    // endregion
}
