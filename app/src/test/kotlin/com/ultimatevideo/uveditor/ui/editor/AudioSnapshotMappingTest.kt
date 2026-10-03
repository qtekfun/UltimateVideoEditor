package com.ultimatevideo.uveditor.ui.editor

import com.ultimatevideo.uveditor.data.model.MediaAssetDto
import com.ultimatevideo.uveditor.domain.FrameRate
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
    fun `clips of media without audio and clips without media are silent`() {
        val tl = timeline(
            track("v1", clip("c1", 0, 100, asset = "mute"), clip("t1", 100, 10, asset = null)),
        )

        assertTrue(snapshot(tl, asset("mute", false)).clips.isEmpty())
    }

    @Test
    fun `clip keys match the keys of the timeline canvas`() {
        val tl = timeline(track("v1", clip("c1", 0, 100, asset = "a")))

        val spec = snapshot(tl, asset("a", true))

        assertEquals(keys.keyFor("c1"), spec.clips.single().clipKey)
    }
}
