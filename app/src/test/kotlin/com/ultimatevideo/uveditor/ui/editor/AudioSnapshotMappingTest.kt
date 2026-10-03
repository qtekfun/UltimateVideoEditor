package com.ultimatevideo.uveditor.ui.editor

import com.ultimatevideo.uveditor.data.model.MediaAssetDto
import com.ultimatevideo.uveditor.domain.Clip
import com.ultimatevideo.uveditor.domain.FrameIndex
import com.ultimatevideo.uveditor.domain.FrameRate
import com.ultimatevideo.uveditor.domain.SpeedRamps
import com.ultimatevideo.uveditor.domain.TimelineOps
import com.ultimatevideo.uveditor.domain.TitleContent
import com.ultimatevideo.uveditor.domain.Transition
import com.ultimatevideo.uveditor.domain.getOrFail
import com.ultimatevideo.uveditor.domain.Timeline
import com.ultimatevideo.uveditor.domain.TrackType
import com.ultimatevideo.uveditor.domain.clip
import com.ultimatevideo.uveditor.domain.timeline
import com.ultimatevideo.uveditor.domain.track
import com.ultimatevideo.uveditor.engine.audio.RetimeKnot
import org.junit.Assert.assertEquals
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
}
