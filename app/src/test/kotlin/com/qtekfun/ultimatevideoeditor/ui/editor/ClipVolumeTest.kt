package com.qtekfun.ultimatevideoeditor.ui.editor

import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto
import com.qtekfun.ultimatevideoeditor.domain.ClipGain
import com.qtekfun.ultimatevideoeditor.domain.TrackType
import com.qtekfun.ultimatevideoeditor.domain.clip
import com.qtekfun.ultimatevideoeditor.domain.timeline
import com.qtekfun.ultimatevideoeditor.domain.track
import org.junit.Assert.assertEquals
import org.junit.Test

/** Which selected clips the toolbar's volume button changes, and what its slider means. */
class ClipVolumeTest {
    private fun asset(id: String, hasAudio: Boolean = true) =
        MediaAssetDto(id, "content://m/$id", 300, 30, 1, "Rec709-SDR", hasVideo = true, hasAudio = hasAudio)

    private val assets = listOf(asset("withSound"), asset("silent", hasAudio = false))

    @Test
    fun `an audio clip and a video clip with its own sound count, a detached video, a silent file and a title do not`() {
        val detached = clip("detached", 100, 50, asset = "withSound").copy(audioDetached = true)
        val timeline = timeline(
            track("v1", clip("video", 0, 100, asset = "withSound"), detached, clip("mute-file", 150, 50, asset = "silent")),
            track("a1", clip("audio", 0, 100, asset = "withSound"), type = TrackType.AUDIO),
            track("t1", clip("title", 0, 100, asset = null), type = TrackType.TITLE),
        )

        val ids = listOf("video", "detached", "mute-file", "audio", "title", "gone")
        assertEquals(listOf("video", "audio"), ClipVolume.soundClips(timeline, assets, ids).map { it.id })
    }

    @Test
    fun `an empty or unrelated selection has no sound clips, so the button stays off`() {
        val timeline = timeline(track("v1", clip("video", 0, 100, asset = "withSound")))
        assertEquals(emptyList<String>(), ClipVolume.soundClips(timeline, assets, emptyList()).map { it.id })
        assertEquals(emptyList<String>(), ClipVolume.soundClips(timeline, assets, listOf("nope")).map { it.id })
    }

    @Test
    fun `the slider maps to dB with a mute at the bottom and tenths of a dB elsewhere`() {
        assertEquals(ClipGain.MIN_DB, ClipVolume.gainOf(ClipVolume.SLIDER_MIN), 0.0)
        assertEquals(ClipGain.MIN_DB, ClipVolume.gainOf(-75f), 0.0)
        assertEquals(0.0, ClipVolume.gainOf(0f), 0.0)
        assertEquals(-6.3, ClipVolume.gainOf(-6.3f), 1e-9)
        assertEquals(-6.3, ClipVolume.gainOf(-6.2999f), 1e-9)
        assertEquals(12.0, ClipVolume.gainOf(20f), 0.0) // never above the top of the slider
    }

    @Test
    fun `a clip's gain shows on the slider and a muted clip sits at the bottom`() {
        assertEquals(-6f, ClipVolume.sliderOf(-6.0), 0f)
        assertEquals(ClipVolume.SLIDER_MIN, ClipVolume.sliderOf(ClipGain.MIN_DB), 0f)
        assertEquals(ClipVolume.SLIDER_MAX, ClipVolume.sliderOf(24.0), 0f)
    }
}
