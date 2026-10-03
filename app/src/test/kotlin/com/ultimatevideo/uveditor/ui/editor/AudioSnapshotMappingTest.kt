package com.ultimatevideo.uveditor.ui.editor

import com.ultimatevideo.uveditor.data.model.MediaAssetDto
import com.ultimatevideo.uveditor.domain.Clip
import com.ultimatevideo.uveditor.domain.FrameIndex
import com.ultimatevideo.uveditor.domain.FrameRate
import com.ultimatevideo.uveditor.domain.TimelineOps
import com.ultimatevideo.uveditor.domain.TitleContent
import com.ultimatevideo.uveditor.domain.Transition
import com.ultimatevideo.uveditor.domain.getOrFail
import com.ultimatevideo.uveditor.domain.Timeline
import com.ultimatevideo.uveditor.domain.TrackType
import com.ultimatevideo.uveditor.domain.clip
import com.ultimatevideo.uveditor.domain.timeline
import com.ultimatevideo.uveditor.domain.track
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

    @Test
    fun `clip keys match the keys of the timeline canvas`() {
        val tl = timeline(track("v1", clip("c1", 0, 100, asset = "a")))

        val spec = snapshot(tl, asset("a", true))

        assertEquals(keys.keyFor("c1"), spec.clips.single().clipKey)
    }
}
