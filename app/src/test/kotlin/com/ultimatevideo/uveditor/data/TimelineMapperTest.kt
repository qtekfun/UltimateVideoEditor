package com.ultimatevideo.uveditor.data

import com.ultimatevideo.uveditor.data.model.ClipDto
import com.ultimatevideo.uveditor.data.model.ProjectDto
import com.ultimatevideo.uveditor.data.model.ProjectSettingsDto
import com.ultimatevideo.uveditor.data.model.TrackDto
import com.ultimatevideo.uveditor.data.model.TransformDto
import com.ultimatevideo.uveditor.domain.ClipTransform
import com.ultimatevideo.uveditor.domain.FrameIndex
import com.ultimatevideo.uveditor.domain.TimelineOps
import com.ultimatevideo.uveditor.domain.TrimEdge
import com.ultimatevideo.uveditor.domain.getOrFail
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TimelineMapperTest {

    private val settings = ProjectSettingsDto(1920, 1080, 30, 1, "Rec709-SDR")

    private fun clipDto(id: String, start: Long, out: Long = 100, gain: Double = 0.0) = ClipDto(
        id = id,
        assetId = "a1",
        timelineStartFrame = start,
        sourceInFrame = 0,
        sourceOutFrame = out,
        transform = TransformDto(rotation = 12.0),
        gainDb = gain,
        colorOverride = "Rec2020-HLG",
    )

    private fun project(vararg tracks: TrackDto) = ProjectDto(id = "p", name = "P", settings = settings, tracks = tracks.toList())

    @Test
    fun `tracks are ordered by order field and clips by start`() {
        val dto = project(
            TrackDto("audio", "audio", order = 1),
            TrackDto("video", "video", order = 0, clips = listOf(clipDto("late", 200), clipDto("early", 0))),
        )

        val timeline = TimelineMapper.toTimeline(dto)

        assertEquals(listOf("video", "audio"), timeline.tracks.map { it.id })
        assertEquals(listOf("early", "late"), timeline.tracks[0].clips.map { it.id })
    }

    @Test
    fun `round trip keeps clip extras`() {
        val dto = project(TrackDto("v", "video", 0, listOf(clipDto("c1", 0, gain = -6.0))))

        val back = TimelineMapper.toDto(dto, TimelineMapper.toTimeline(dto), dto.mediaLibrary)

        assertEquals(dto.tracks, back.tracks)
    }

    @Test
    fun `edited placement is written while the colour override survives`() {
        val dto = project(TrackDto("v", "video", 0, listOf(clipDto("c1", 0, gain = -6.0))))
        val moved = TimelineOps.move(TimelineMapper.toTimeline(dto), "c1", FrameIndex(50)).getOrFail()
        val trimmed = TimelineOps.trim(moved, "c1", TrimEdge.START, FrameIndex(60)).getOrFail()

        val clip = TimelineMapper.toDto(dto, trimmed, emptyList()).tracks.single().clips.single()

        assertEquals(60, clip.timelineStartFrame)
        assertEquals(10, clip.sourceInFrame)
        assertEquals(100, clip.sourceOutFrame)
        assertEquals(-6.0, clip.gainDb, 0.0)
        assertEquals(12.0, clip.transform.rotation, 0.0)
        assertEquals("Rec2020-HLG", clip.colorOverride)
    }

    @Test
    fun `a split keeps gain and transform on both halves and the colour override on the derived one`() {
        val dto = project(TrackDto("v", "video", 0, listOf(clipDto("c1", 0, gain = -6.0))))
        val split = TimelineOps.split(TimelineMapper.toTimeline(dto), "v", FrameIndex(40), "c1~x9").getOrFail()

        val clips = TimelineMapper.toDto(dto, split, emptyList()).tracks.single().clips

        val derived = clips.first { it.id == "c1~x9" }
        assertEquals(-6.0, derived.gainDb, 0.0)
        assertEquals(12.0, derived.transform.rotation, 0.0)
        assertEquals("Rec2020-HLG", derived.colorOverride)
    }

    @Test
    fun `transform and gain are read into the domain`() {
        val dto = project(
            TrackDto(
                "v", "video", 0,
                listOf(
                    clipDto("c1", 0, gain = -3.5).copy(
                        transform = TransformDto(scale = listOf(1.5, 2.0), rotation = 30.0, position = listOf(100.0, -50.0), opacity = 0.25),
                    ),
                ),
            ),
        )

        val clip = TimelineMapper.toTimeline(dto).tracks.single().clips.single()

        assertEquals(ClipTransform(100.0, -50.0, 1.5, 2.0, 30.0, 0.25), clip.transform)
        assertEquals(-3.5, clip.gainDb, 0.0)
    }

    @Test
    fun `domain transform is written back to the dto`() {
        val dto = project(TrackDto("v", "video", 0, listOf(clipDto("c1", 0))))
        val edited = TimelineOps.setTransform(TimelineMapper.toTimeline(dto), "c1", ClipTransform(10.0, 20.0, 0.5, 0.5, 90.0, 0.8)).getOrFail()
        val gained = TimelineOps.setGain(edited, "c1", -12.0).getOrFail()

        val clip = TimelineMapper.toDto(dto, gained, emptyList()).tracks.single().clips.single()

        assertEquals(listOf(0.5, 0.5), clip.transform.scale)
        assertEquals(listOf(10.0, 20.0), clip.transform.position)
        assertEquals(90.0, clip.transform.rotation, 0.0)
        assertEquals(0.8, clip.transform.opacity, 0.0)
        assertEquals(-12.0, clip.gainDb, 0.0)
    }

    @Test
    fun `old transforms without opacity load as opaque`() {
        val json = """{"scale":[1.0,1.0],"rotation":0.0,"position":[0.0,0.0]}"""

        assertEquals(1.0, Json.decodeFromString<TransformDto>(json).opacity, 0.0)
    }

    @Test
    fun `malformed or invalid transform is reported as corrupt`() {
        val short = project(TrackDto("v", "video", 0, listOf(clipDto("c1", 0).copy(transform = TransformDto(scale = listOf(1.0))))))
        val flat = project(TrackDto("v", "video", 0, listOf(clipDto("c1", 0).copy(transform = TransformDto(scale = listOf(0.0, 1.0))))))

        assertThrows(ProjectError.Corrupt::class.java) { TimelineMapper.toTimeline(short) }
        assertThrows(ProjectError.Corrupt::class.java) { TimelineMapper.toTimeline(flat) }
    }

    @Test
    fun `unknown track type is reported as corrupt`() {
        val dto = project(TrackDto("t", "hologram", 0))

        assertThrows(ProjectError.Corrupt::class.java) { TimelineMapper.toTimeline(dto) }
    }

    @Test
    fun `overlapping clips are reported as corrupt`() {
        val dto = project(TrackDto("v", "video", 0, listOf(clipDto("a", 0), clipDto("b", 50))))

        assertThrows(ProjectError.Corrupt::class.java) { TimelineMapper.toTimeline(dto) }
    }
}
