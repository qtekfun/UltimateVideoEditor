package com.qtekfun.ultimatevideoeditor.data

import com.qtekfun.ultimatevideoeditor.data.model.ClipAudioDto
import com.qtekfun.ultimatevideoeditor.data.model.ClipDto
import com.qtekfun.ultimatevideoeditor.data.model.ProjectDto
import com.qtekfun.ultimatevideoeditor.data.model.ProjectSettingsDto
import com.qtekfun.ultimatevideoeditor.data.model.TrackDto
import com.qtekfun.ultimatevideoeditor.data.model.VoiceFxDto
import com.qtekfun.ultimatevideoeditor.domain.ClipAudio
import com.qtekfun.ultimatevideoeditor.domain.TimelineOps
import com.qtekfun.ultimatevideoeditor.domain.VoiceFx
import com.qtekfun.ultimatevideoeditor.domain.VoicePreset
import com.qtekfun.ultimatevideoeditor.domain.getOrFail
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceMapperTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val settings = ProjectSettingsDto(1920, 1080, 30, 1, "Rec709-SDR")

    private fun clipDto(audio: ClipAudioDto? = null) = ClipDto(
        id = "c", assetId = "a1", timelineStartFrame = 0, sourceInFrame = 0, sourceOutFrame = 100, audio = audio,
    )

    private fun project(audio: ClipAudioDto? = null) =
        ProjectDto(id = "p", name = "P", settings = settings, tracks = listOf(TrackDto("v", "video", 0, listOf(clipDto(audio)))))

    @Test
    fun `every preset survives a round trip through JSON`() {
        for (preset in VoicePreset.entries) {
            val voice = VoiceFx(preset, preset.sliders.map { (it.min + it.max) / 2.0 })
            val start = TimelineMapper.toTimeline(project())
            val edited = TimelineOps.setClipAudio(start, "c", ClipAudio(pan = 0.1, voice = voice)).getOrFail()

            val dto = TimelineMapper.toDto(project(), edited, emptyList())
            val text = json.encodeToString(dto)
            assertTrue(text.contains("\"voice\""))
            assertTrue(text.contains("\"${preset.name.lowercase()}\""))
            val reloaded = TimelineMapper.toTimeline(json.decodeFromString<ProjectDto>(text))

            assertEquals(voice, reloaded.trackOfClip("c")!!.clip("c")!!.audio.voice)
            assertEquals(edited, reloaded)
        }
    }

    @Test
    fun `a clip without a voice effect writes no voice field and old files load unchanged`() {
        val plain = TimelineMapper.toTimeline(project(ClipAudioDto(pan = 0.3)))
        val written = json.encodeToString(TimelineMapper.toDto(project(), plain, emptyList()))
        assertFalse(written.contains("voice"))
        assertNull(plain.trackOfClip("c")!!.clip("c")!!.audio.voice)

        val oldJson = """{"id":"p","name":"P","settings":{"width":1920,"height":1080,"fpsNum":30,"fpsDen":1,"colorSpace":"Rec709-SDR"},
            "tracks":[{"id":"v","type":"video","order":0,"clips":[{"id":"c","assetId":"a1","timelineStartFrame":0,"sourceInFrame":0,"sourceOutFrame":50,
            "audio":{"pan":0.5,"denoise":null}}]}]}"""
        val loaded = TimelineMapper.toTimeline(json.decodeFromString<ProjectDto>(oldJson))
        assertEquals(0.5, loaded.trackOfClip("c")!!.clip("c")!!.audio.pan, 0.0)
        assertNull(loaded.trackOfClip("c")!!.clip("c")!!.audio.voice)
    }

    @Test
    fun `a voice effect alone is enough to write the audio block`() {
        val start = TimelineMapper.toTimeline(project())
        val edited = TimelineOps.setClipAudio(start, "c", ClipAudio(voice = VoicePreset.WHISPER.defaults())).getOrFail()
        val written = json.encodeToString(TimelineMapper.toDto(project(), edited, emptyList()))
        assertTrue(written.contains("\"audio\""))
        assertTrue(written.contains("whisper"))
    }

    @Test
    fun `an unknown preset or impossible values in a file are reported as corrupt`() {
        fun load(voice: VoiceFxDto) = TimelineMapper.toTimeline(project(ClipAudioDto(voice = voice)))
        assertThrows(ProjectError.Corrupt::class.java) { load(VoiceFxDto("alien", listOf(1.0))) }
        assertThrows(ProjectError.Corrupt::class.java) { load(VoiceFxDto("echo", listOf(300.0))) }                    // too few values
        assertThrows(ProjectError.Corrupt::class.java) { load(VoiceFxDto("echo", listOf(5000.0, 0.5, 0.5))) }        // delay out of range
        assertThrows(ProjectError.Corrupt::class.java) { load(VoiceFxDto("chipmunk", listOf(Double.NaN))) }
        // A valid one loads.
        assertEquals(VoiceFx(VoicePreset.CHIPMUNK, listOf(6.0)), TimelineMapper.toTimeline(project(ClipAudioDto(voice = VoiceFxDto("chipmunk", listOf(6.0)))))
            .trackOfClip("c")!!.clip("c")!!.audio.voice)
    }
}
