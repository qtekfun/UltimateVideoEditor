package com.qtekfun.ultimatevideoeditor.ui.editor

import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto
import com.qtekfun.ultimatevideoeditor.domain.ClipAudio
import com.qtekfun.ultimatevideoeditor.domain.FrameRate
import com.qtekfun.ultimatevideoeditor.domain.TimelineOps
import com.qtekfun.ultimatevideoeditor.domain.VoicePreset
import com.qtekfun.ultimatevideoeditor.domain.clip
import com.qtekfun.ultimatevideoeditor.domain.getOrFail
import com.qtekfun.ultimatevideoeditor.domain.timeline
import com.qtekfun.ultimatevideoeditor.domain.track
import com.qtekfun.ultimatevideoeditor.engine.audio.VoiceSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSnapshotMappingTest {

    private val keys = KeyRegistry()
    private val assetKeys = KeyRegistry()

    private fun asset(id: String) = MediaAssetDto(id, "content://$id", 300, 30, 1, "Rec709-SDR", hasVideo = true, hasAudio = true)

    private fun snapshotOf(audio: ClipAudio?) = run {
        var tl = timeline(track("v1", clip("c1", 0, 100, asset = "a")))
        if (audio != null) tl = TimelineOps.setClipAudio(tl, "c1", audio).getOrFail()
        audioSnapshotOf(tl, listOf(asset("a")), FrameRate(30, 1), keys::keyFor, assetKeys::keyFor)
    }

    @Test
    fun `a clip without a voice effect carries none`() {
        assertEquals(VoiceSpec.NONE, snapshotOf(null).clips.single().voice)
        assertEquals(VoiceSpec.NONE, snapshotOf(ClipAudio(pan = 0.3)).clips.single().voice)
    }

    @Test
    fun `the preset's sliders reach the mixer as engine settings`() {
        val spec = snapshotOf(ClipAudio(voice = VoicePreset.ECHO.defaults().with(0, 400.0))).clips.single().voice
        assertEquals(400f, spec.echoMs, 0f)
        assertEquals(0.45f, spec.echoFeedback, 1e-6f)
        assertEquals(0.4f, spec.echoMix, 1e-6f)
        assertEquals(0f, spec.pitchSemitones, 0f)

        val pitch = snapshotOf(ClipAudio(voice = VoicePreset.CHIPMUNK.defaults().with(0, 8.0))).clips.single().voice
        assertEquals(8f, pitch.pitchSemitones, 0f)
        assertEquals(8f, pitch.formantSemitones, 0f)
    }

    @Test
    fun `every preset at its defaults produces a snapshot the engine can encode`() {
        for (preset in VoicePreset.entries) {
            val snapshot = snapshotOf(ClipAudio(voice = preset.defaults()))
            assertTrue(snapshot.clips.single().voice != VoiceSpec.NONE)
            assertTrue(snapshot.encode().remaining() > 0)
        }
    }
}
