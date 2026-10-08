package com.qtekfun.ultimatevideoeditor.data

import com.qtekfun.ultimatevideoeditor.data.model.ClipAudioDto
import com.qtekfun.ultimatevideoeditor.data.model.ClipDto
import com.qtekfun.ultimatevideoeditor.data.model.DenoiseDto
import com.qtekfun.ultimatevideoeditor.data.model.DuckingDto
import com.qtekfun.ultimatevideoeditor.data.model.ProjectDto
import com.qtekfun.ultimatevideoeditor.data.model.ProjectSettingsDto
import com.qtekfun.ultimatevideoeditor.data.model.TrackAudioDto
import com.qtekfun.ultimatevideoeditor.data.model.TrackDto
import com.qtekfun.ultimatevideoeditor.domain.AudioRole
import com.qtekfun.ultimatevideoeditor.domain.BusCompressor
import com.qtekfun.ultimatevideoeditor.domain.ClipAudio
import com.qtekfun.ultimatevideoeditor.domain.ClipEq
import com.qtekfun.ultimatevideoeditor.domain.Denoise
import com.qtekfun.ultimatevideoeditor.domain.Ducking
import com.qtekfun.ultimatevideoeditor.domain.EqBand
import com.qtekfun.ultimatevideoeditor.domain.TrackAudio
import com.qtekfun.ultimatevideoeditor.domain.TimelineOps
import com.qtekfun.ultimatevideoeditor.domain.getOrFail
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioMapperTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val settings = ProjectSettingsDto(1920, 1080, 30, 1, "Rec709-SDR")

    private fun clipDto(id: String, start: Long, audio: ClipAudioDto? = null) = ClipDto(
        id = id, assetId = "a1", timelineStartFrame = start, sourceInFrame = 0, sourceOutFrame = 100, audio = audio,
    )

    private fun project(tracks: List<TrackDto>, ducking: DuckingDto? = null) =
        ProjectDto(id = "p", name = "P", settings = settings, tracks = tracks, ducking = ducking)

    private val tools = ClipAudio(
        pan = 0.35,
        fadeInFrames = 8,
        fadeOutFrames = 20,
        eq = ClipEq(highPassHz = 100.0, lowPassHz = 14000.0).withBand(1, EqBand(500.0, -3.0, 0.9)),
        denoise = Denoise(0.7, List(Denoise.BINS) { (it % 7) * 0.001f }),
        normalizeDb = 4.5,
        targetLufs = -14.0,
    )

    @Test
    fun `old projects without audio fields load with neutral settings`() {
        val oldJson = """{"id":"p","name":"P","settings":{"width":1920,"height":1080,"fpsNum":30,"fpsDen":1,"colorSpace":"Rec709-SDR"},
            "tracks":[{"id":"v","type":"video","order":0,"clips":[{"id":"c","assetId":"a1","timelineStartFrame":0,"sourceInFrame":0,"sourceOutFrame":50}]}]}"""
        val dto = json.decodeFromString<ProjectDto>(oldJson)
        val timeline = TimelineMapper.toTimeline(dto)

        assertEquals(ClipAudio.NONE, timeline.tracks[0].clips[0].audio)
        assertEquals(TrackAudio.NONE, timeline.tracks[0].audio)
        assertNull(timeline.ducking)
    }

    @Test
    fun `neutral audio writes no audio fields at all`() {
        val timeline = TimelineMapper.toTimeline(project(listOf(TrackDto("v", "video", 0, listOf(clipDto("c", 0))))))
        val written = json.encodeToString(TimelineMapper.toDto(project(emptyList()), timeline, emptyList()))
        assertFalse(written.contains("\"audio\""))
        assertFalse(written.contains("ducking"))
    }

    @Test
    fun `every audio setting survives a round trip through JSON`() {
        val start = TimelineMapper.toTimeline(project(listOf(
            TrackDto("v", "video", 0, listOf(clipDto("c", 0))),
            TrackDto("m", "audio", 1, listOf(clipDto("n", 0))),
        )))
        val edited = TimelineOps.setClipAudio(start, "c", tools).getOrFail().let {
            TimelineOps.setTrackAudio(it, "m", TrackAudio(-8.0, mute = false, solo = true, role = AudioRole.MUSIC, compressor = BusCompressor(-22.0, 4.0, 6.0, 90.0, 1.5)))
        }.getOrFail().let { TimelineOps.setDucking(it, Ducking(12.0, -40.0, 30.0, 350.0)) }.getOrFail()

        val dto = TimelineMapper.toDto(project(emptyList()), edited, emptyList())
        val reloaded = TimelineMapper.toTimeline(json.decodeFromString<ProjectDto>(json.encodeToString(dto)))

        assertEquals(tools, reloaded.trackOfClip("c")!!.clip("c")!!.audio)
        assertEquals(edited.track("m")!!.audio, reloaded.track("m")!!.audio)
        assertEquals(Ducking(12.0, -40.0, 30.0, 350.0), reloaded.ducking)
        assertEquals(edited, reloaded)
    }

    @Test
    fun `a split copies the audio settings to both halves in the file`() {
        val start = TimelineMapper.toTimeline(project(listOf(TrackDto("v", "video", 0, listOf(clipDto("c", 0))))))
        val edited = TimelineOps.setClipAudio(start, "c", tools.copy(fadeInFrames = 0, fadeOutFrames = 0)).getOrFail()
        val split = TimelineOps.split(edited, "v", com.qtekfun.ultimatevideoeditor.domain.FrameIndex(40), "c2").getOrFail()
        val dto = TimelineMapper.toDto(project(listOf(TrackDto("v", "video", 0, listOf(clipDto("c", 0))))), split, emptyList())
        val clips = dto.tracks[0].clips

        assertEquals(2, clips.size)
        assertTrue(clips.all { val a = it.audio; a != null && a.pan == 0.35 && a.denoise != null })
    }

    @Test
    fun `invalid audio values in a file are reported as corrupt`() {
        fun load(audio: ClipAudioDto) = TimelineMapper.toTimeline(project(listOf(TrackDto("v", "video", 0, listOf(clipDto("c", 0, audio))))))
        assertThrows(ProjectError.Corrupt::class.java) { load(ClipAudioDto(pan = 3.0)) }
        assertThrows(ProjectError.Corrupt::class.java) { load(ClipAudioDto(normalizeDb = 99.0)) }
        assertThrows(ProjectError.Corrupt::class.java) { load(ClipAudioDto(denoise = DenoiseDto(0.5, List(10) { 0f }))) }
        assertThrows(ProjectError.Corrupt::class.java) { load(ClipAudioDto(fadeInFrames = -4)) }
        assertThrows(ProjectError.Corrupt::class.java) {
            TimelineMapper.toTimeline(project(listOf(TrackDto("v", "video", 0, emptyList(), TrackAudioDto(role = "boss")))))
        }
        assertThrows(ProjectError.Corrupt::class.java) {
            TimelineMapper.toTimeline(project(listOf(TrackDto("v", "video", 0, emptyList(), TrackAudioDto(volumeDb = 80.0)))))
        }
        assertThrows(ProjectError.Corrupt::class.java) {
            TimelineMapper.toTimeline(project(emptyList(), DuckingDto(amountDb = 70.0)))
        }
    }

    @Test
    fun `an EQ with the wrong number of bands falls back to the defaults instead of failing`() {
        val dto = ClipAudioDto(eq = com.qtekfun.ultimatevideoeditor.data.model.ClipEqDto(highPassHz = 90.0, bands = emptyList()))
        val timeline = TimelineMapper.toTimeline(project(listOf(TrackDto("v", "video", 0, listOf(clipDto("c", 0, dto))))))
        val eq = timeline.tracks[0].clips[0].audio.eq
        assertEquals(90.0, eq.highPassHz, 0.0)
        assertEquals(ClipEq.DEFAULT_BANDS, eq.bands)
    }

    @Test
    fun `the fade shape survives a round trip and an unknown or missing one reads as equal power`() {
        val start = TimelineMapper.toTimeline(project(listOf(TrackDto("m", "audio", 0, listOf(clipDto("n", 0))))))
        for (shape in com.qtekfun.ultimatevideoeditor.domain.FadeShape.entries) {
            val edited = TimelineOps.setClipAudio(start, "n", ClipAudio(fadeInFrames = 10, fadeShape = shape)).getOrFail()
            val written = json.encodeToString(TimelineMapper.toDto(project(emptyList()), edited, emptyList()))
            val reloaded = TimelineMapper.toTimeline(json.decodeFromString<ProjectDto>(written))
            assertEquals(shape, reloaded.trackOfClip("n")!!.clip("n")!!.audio.fadeShape)
            // Equal power is the default and is not written, so projects without the field are unchanged.
            assertEquals(shape != com.qtekfun.ultimatevideoeditor.domain.FadeShape.EQUAL_POWER, written.contains("fadeShape"))
        }
        val unknown = project(listOf(TrackDto("m", "audio", 0, listOf(clipDto("n", 0, ClipAudioDto(fadeInFrames = 5, fadeShape = "from-a-newer-build"))))))
        assertEquals(com.qtekfun.ultimatevideoeditor.domain.FadeShape.EQUAL_POWER, TimelineMapper.toTimeline(unknown).trackOfClip("n")!!.clip("n")!!.audio.fadeShape)
    }
}
