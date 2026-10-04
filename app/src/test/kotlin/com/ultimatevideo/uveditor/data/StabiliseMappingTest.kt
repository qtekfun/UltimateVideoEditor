package com.ultimatevideo.uveditor.data

import com.ultimatevideo.uveditor.data.model.ClipDto
import com.ultimatevideo.uveditor.data.model.ProjectDto
import com.ultimatevideo.uveditor.data.model.StabiliseDto
import com.ultimatevideo.uveditor.data.model.TrackDto
import com.ultimatevideo.uveditor.domain.StabCrop
import com.ultimatevideo.uveditor.domain.Stabilise
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StabiliseMappingTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun project(stabilise: StabiliseDto?) = ProjectDto(
        id = "p1",
        name = "Test",
        settings = com.ultimatevideo.uveditor.data.model.ProjectSettingsDto(1920, 1080, 30, 1, "Rec709-SDR"),
        mediaLibrary = listOf(
            com.ultimatevideo.uveditor.data.model.MediaAssetDto("a1", "content://m/a1", 300, 30, 1, "Rec709-SDR"),
        ),
        tracks = listOf(TrackDto("v1", "video", 0, listOf(ClipDto("c1", "a1", 0, 0, 90, stabilise = stabilise)))),
    )

    @Test
    fun `the settings survive a trip through the timeline and back`() {
        val timeline = TimelineMapper.toTimeline(project(StabiliseDto(0.65, "tight")))
        val clip = timeline.tracks.single().clips.single()
        assertEquals(Stabilise(0.65, StabCrop.TIGHT), clip.stabilise)

        val back = TimelineMapper.toDto(project(null), timeline, project(null).mediaLibrary)
        assertEquals(StabiliseDto(0.65, "tight"), back.tracks.single().clips.single().stabilise)
    }

    @Test
    fun `a clip without the field is not stabilised and writes none`() {
        val timeline = TimelineMapper.toTimeline(project(null))
        assertNull(timeline.tracks.single().clips.single().stabilise)
        val encoded = json.encodeToString(TimelineMapper.toDto(project(null), timeline, project(null).mediaLibrary))
        assertFalse("stabilise" in encoded)
    }

    @Test
    fun `a project written before the field existed still loads`() {
        val old = """{"id":"p","name":"x","settings":{"width":1920,"height":1080,"fpsNum":30,"fpsDen":1,"colorSpace":"Rec709-SDR"},
            |"mediaLibrary":[{"id":"a1","uri":"content://m/a1","durationFrames":300,"nativeFpsNum":30,"nativeFpsDen":1,"colorSpace":"Rec709-SDR"}],
            |"tracks":[{"id":"v1","type":"video","order":0,"clips":[{"id":"c1","assetId":"a1","timelineStartFrame":0,"sourceInFrame":0,"sourceOutFrame":90}]}]}
        """.trimMargin()
        val timeline = TimelineMapper.toTimeline(json.decodeFromString<ProjectDto>(old))
        assertNull(timeline.tracks.single().clips.single().stabilise)
    }

    @Test
    fun `the json carries the strength and the crop name`() {
        val encoded = json.encodeToString(StabiliseDto(0.4, "full"))
        assertTrue(""""strength":0.4""" in encoded)
        assertTrue(""""crop":"full"""" in encoded)
        assertEquals(StabiliseDto(0.4, "full"), json.decodeFromString<StabiliseDto>(encoded))
        // Missing keys fall back to the defaults.
        assertEquals(StabiliseDto(), json.decodeFromString<StabiliseDto>("{}"))
    }

    @Test
    fun `an unknown crop or an impossible strength is a corrupt project`() {
        assertThrows(ProjectError.Corrupt::class.java) { TimelineMapper.toTimeline(project(StabiliseDto(0.5, "huge"))) }
        assertThrows(ProjectError.Corrupt::class.java) { TimelineMapper.toTimeline(project(StabiliseDto(3.0, "tight"))) }
    }
}
