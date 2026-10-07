package com.ultimatevideo.uveditor.data

import com.ultimatevideo.uveditor.data.model.ClipDto
import com.ultimatevideo.uveditor.data.model.ProjectDto
import com.ultimatevideo.uveditor.data.model.ProjectSettingsDto
import com.ultimatevideo.uveditor.data.model.TrackDto
import com.ultimatevideo.uveditor.domain.ClipLinks
import com.ultimatevideo.uveditor.domain.DetachAudio
import com.ultimatevideo.uveditor.domain.EditCommand
import com.ultimatevideo.uveditor.domain.EditHistory
import com.ultimatevideo.uveditor.domain.FrameIndex
import com.ultimatevideo.uveditor.domain.getOrFail
import com.ultimatevideo.uveditor.domain.renderClips
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Detached and linked audio in the project file (SPECS 4.1, 5.38): new fields with defaults, so older projects open unchanged. */
class DetachedAudioMapperTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val settings = ProjectSettingsDto(1920, 1080, 30, 1, "Rec709-SDR")

    @Test
    fun `a project saved before detached audio existed opens with every clip attached and unlinked`() {
        val old = """{"id":"p","name":"P","settings":{"width":1920,"height":1080,"fpsNum":30,"fpsDen":1,"colorSpace":"Rec709-SDR"},
            "tracks":[{"id":"v","type":"video","order":0,"clips":[{"id":"c","assetId":"a1","timelineStartFrame":0,"sourceInFrame":0,"sourceOutFrame":50}]},
            {"id":"a","type":"audio","order":1,"clips":[{"id":"m","assetId":"a1","timelineStartFrame":0,"sourceInFrame":0,"sourceOutFrame":50}]}]}"""
        val timeline = TimelineMapper.toTimeline(json.decodeFromString<ProjectDto>(old))
        val all = timeline.tracks.flatMap { it.clips }
        assertTrue(all.none { it.audioDetached })
        assertTrue(all.all { it.linkId == null })
        assertTrue(timeline.invariantViolations().isEmpty())
        assertFalse(timeline.renderClips().any { it.soundDetached })
    }

    @Test
    fun `an ordinary clip writes neither field`() {
        val dto = ProjectDto(
            id = "p", name = "P", settings = settings,
            tracks = listOf(TrackDto("v", "video", 0, listOf(ClipDto("c", "a1", 0, 0, 50)))),
        )
        val timeline = TimelineMapper.toTimeline(dto)
        val written = json.encodeToString(TimelineMapper.toDto(dto, timeline, emptyList()))
        assertFalse(written.contains("audioDetached"))
        assertFalse(written.contains("linkId"))
    }

    @Test
    fun `detach state and the link survive a round trip through JSON`() {
        val dto = ProjectDto(
            id = "p", name = "P", settings = settings,
            tracks = listOf(TrackDto("v", "video", 0, listOf(ClipDto("c", "a1", 0, 0, 50))), TrackDto("a", "audio", 1, emptyList())),
        )
        val start = TimelineMapper.toTimeline(dto)
        val detached = EditHistory(start).execute(DetachAudio("c", "aud", "a")).getOrFail().timeline
        val written = json.encodeToString(TimelineMapper.toDto(dto, detached, emptyList()))
        val back = TimelineMapper.toTimeline(json.decodeFromString<ProjectDto>(written))
        assertEquals(detached, back)
        val video = back.trackOfClip("c")!!.clip("c")!!
        assertTrue(video.audioDetached)
        assertEquals("aud", ClipLinks.partnerOf(back, video)?.second?.id)
        assertNull(back.trackOfClip("aud")!!.clip("aud")!!.takeIf { it.audioDetached })
    }

    @Test
    fun `a link whose partner is missing is dropped on load instead of making the project unreadable`() {
        val dto = ProjectDto(
            id = "p", name = "P", settings = settings,
            tracks = listOf(TrackDto("v", "video", 0, listOf(ClipDto("c", "a1", 0, 0, 50, linkId = "L", audioDetached = true))), TrackDto("a", "audio", 1, emptyList())),
        )
        val timeline = TimelineMapper.toTimeline(dto)
        val clip = timeline.trackOfClip("c")!!.clip("c")!!
        assertNull(clip.linkId)
        assertTrue(clip.audioDetached)
    }

    @Test
    fun `a derived clip writes its own link, not the one of the clip it was split from`() {
        val dto = ProjectDto(
            id = "p", name = "P", settings = settings,
            tracks = listOf(
                TrackDto("v", "video", 0, listOf(ClipDto("c", "a1", 0, 0, 50, linkId = "L", audioDetached = true))),
                TrackDto("a", "audio", 1, listOf(ClipDto("m", "a1", 0, 0, 50, linkId = "L"))),
            ),
        )
        val split = EditHistory(TimelineMapper.toTimeline(dto)).execute(EditCommand.Split("v", FrameIndex(20), "c~x")).getOrFail().timeline
        val out = TimelineMapper.toDto(dto, split, emptyList())
        val links = out.tracks.flatMap { it.clips }.associate { it.id to it.linkId }
        assertEquals(links["c"], links["m"])
        assertEquals(links["c~x"], links["m~x"])
        assertTrue(links["c"] != links["c~x"])
    }
}
