package com.ultimatevideo.uveditor.data

import com.ultimatevideo.uveditor.data.model.ClipDto
import com.ultimatevideo.uveditor.data.model.MediaAssetDto
import com.ultimatevideo.uveditor.data.model.ProjectDto
import com.ultimatevideo.uveditor.data.model.ProjectSettingsDto
import com.ultimatevideo.uveditor.data.model.TrackDto
import com.ultimatevideo.uveditor.domain.StillKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StillMapperTest {

    private val settings = ProjectSettingsDto(1920, 1080, 30, 1, "Rec709-SDR")

    private val image = MediaAssetDto(
        id = "img1", uri = "content://media/picker/0/1", durationFrames = 150,
        nativeFpsNum = 30, nativeFpsDen = 1, colorSpace = "Rec709-SDR",
        hasVideo = false, hasAudio = false, isImage = true,
    )

    private fun project(vararg clips: ClipDto, library: List<MediaAssetDto> = listOf(image)) = ProjectDto(
        id = "p",
        name = "P",
        settings = settings,
        mediaLibrary = library,
        tracks = listOf(TrackDto("v", "video", 0, clips.toList())),
    )

    private fun roundTrip(dto: ProjectDto): ProjectDto =
        TimelineMapper.toDto(dto, TimelineMapper.toTimeline(dto), dto.mediaLibrary)

    @Test
    fun `photo and sticker clips round trip`() {
        val dto = project(
            ClipDto("P", "img1", 0, 0, 150, still = "photo"),
            ClipDto("S", "sticker:heart", 150, 0, 90, still = "sticker"),
        )
        val clips = TimelineMapper.toTimeline(dto).track("v")!!.clips
        assertEquals(StillKind.PHOTO, clips[0].still)
        assertEquals(StillKind.STICKER, clips[1].still)
        assertFalse(clips[0].hasMedia)
        assertEquals(dto.tracks, roundTrip(dto).tracks)
    }

    @Test
    fun `the image flag survives and defaults to false for older libraries`() {
        val dto = project(ClipDto("P", "img1", 0, 0, 150, still = "photo"))
        assertTrue(roundTrip(dto).mediaLibrary.single().isImage)
        val legacy = MediaAssetDto("a", "content://x", 100, 30, 1, "Rec709-SDR")
        assertFalse(legacy.isImage)
    }

    @Test
    fun `projects written before stills load as plain clips`() {
        val clip = TimelineMapper.toTimeline(project(ClipDto("A", "a1", 0, 0, 100))).track("v")!!.clip("A")!!
        assertNull(clip.still)
        assertTrue(clip.hasMedia)
        assertNull(roundTrip(project(ClipDto("A", "a1", 0, 0, 100))).tracks.single().clips.single().still)
    }

    @Test
    fun `json without the still field decodes and the field is written only when set`() {
        val dto = project(ClipDto("A", "a1", 0, 0, 100), ClipDto("P", "img1", 100, 0, 50, still = "photo"))
        val text = ProjectJson.encode(dto)
        assertTrue("\"still\":\"photo\"" in text.replace(" ", "").replace("\n", ""))
        assertEquals(dto.tracks, ProjectJson.decode(text).tracks)
        val old = ProjectJson.encode(project(ClipDto("A", "a1", 0, 0, 100)))
        assertFalse("sticker" in old || "\"photo\"" in old)
    }

    @Test
    fun `an unknown still kind is corrupt`() {
        val dto = project(ClipDto("P", "img1", 0, 0, 150, still = "hologram"))
        assertThrows(ProjectError.Corrupt::class.java) { TimelineMapper.toTimeline(dto) }
    }

    @Test
    fun `a still on an audio track is corrupt`() {
        val dto = ProjectDto(
            id = "p", name = "P", settings = settings, mediaLibrary = listOf(image),
            tracks = listOf(TrackDto("a", "audio", 0, listOf(ClipDto("P", "img1", 0, 0, 150, still = "photo")))),
        )
        assertThrows(ProjectError.Corrupt::class.java) { TimelineMapper.toTimeline(dto) }
    }
}
