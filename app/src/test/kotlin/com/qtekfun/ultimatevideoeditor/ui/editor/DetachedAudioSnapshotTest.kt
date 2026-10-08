package com.qtekfun.ultimatevideoeditor.ui.editor

import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto
import com.qtekfun.ultimatevideoeditor.domain.DetachAudio
import com.qtekfun.ultimatevideoeditor.domain.EditCommand
import com.qtekfun.ultimatevideoeditor.domain.EditHistory
import com.qtekfun.ultimatevideoeditor.domain.FrameRate
import com.qtekfun.ultimatevideoeditor.domain.Timeline
import com.qtekfun.ultimatevideoeditor.domain.TrackType
import com.qtekfun.ultimatevideoeditor.domain.clip
import com.qtekfun.ultimatevideoeditor.domain.f
import com.qtekfun.ultimatevideoeditor.domain.getOrFail
import com.qtekfun.ultimatevideoeditor.domain.timeline
import com.qtekfun.ultimatevideoeditor.domain.track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mixer's clip list is built once and used by the preview and by the export, so a detached sound is heard exactly once
 * in both: from the audio-lane clip, never from the silenced video clip as well.
 */
class DetachedAudioSnapshotTest {

    private val asset = MediaAssetDto("a", "content://a", 300, 30, 1, "Rec709-SDR", hasVideo = true, hasAudio = true)
    private val keys = KeyRegistry()
    private val assetKeys = KeyRegistry()

    private fun snapshot(tl: Timeline) = audioSnapshotOf(tl, listOf(asset), FrameRate(30, 1), keys::keyFor, assetKeys::keyFor)

    private fun scene(): EditHistory = EditHistory(timeline(track("v1", clip("A", 0, 60, srcIn = 10)), track("a1", type = TrackType.AUDIO)))

    @Test
    fun `before detaching the video clip plays its own sound`() {
        assertEquals(listOf(keys.keyFor("A")), snapshot(scene().timeline).clips.map { it.clipKey })
    }

    @Test
    fun `after detaching only the audio clip is in the mix, with the same range and position`() {
        val h = scene().execute(DetachAudio("A", "aud", "a1")).getOrFail()
        val spec = snapshot(h.timeline).clips.single()
        assertEquals(keys.keyFor("aud"), spec.clipKey)
        assertEquals(0L, spec.startFrame)
        assertEquals(60L, spec.durationFrames)
        assertEquals(10L, spec.sourceInFrame)
        assertEquals(assetKeys.keyFor("a"), spec.assetKey)
    }

    @Test
    fun `a video whose detached audio was deleted is silent`() {
        val h = scene().execute(DetachAudio("A", "aud", "a1")).getOrFail().execute(EditCommand.DeleteClip("aud")).getOrFail()
        assertTrue(snapshot(h.timeline).clips.isEmpty())
    }

    @Test
    fun `a cut piece of the detached audio plays only where it is`() {
        val h = scene().execute(DetachAudio("A", "aud", "a1")).getOrFail()
            .execute(EditCommand.Split("a1", f(20), "aud~p")).getOrFail()
            .execute(EditCommand.DeleteClip("aud")).getOrFail()
        val specs = snapshot(h.timeline).clips
        assertEquals(1, specs.size)
        assertEquals(20L, specs.single().startFrame)
        assertEquals(30L, specs.single().sourceInFrame)
    }
}
