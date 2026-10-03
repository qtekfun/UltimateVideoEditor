package com.ultimatevideo.uveditor.data

import com.ultimatevideo.uveditor.data.model.ClipDto
import com.ultimatevideo.uveditor.data.model.ProjectDto
import com.ultimatevideo.uveditor.data.model.ProjectSettingsDto
import com.ultimatevideo.uveditor.data.model.TrackDto
import com.ultimatevideo.uveditor.data.model.TransformDto
import com.ultimatevideo.uveditor.domain.Clip
import com.ultimatevideo.uveditor.domain.FrameIndex
import com.ultimatevideo.uveditor.domain.Timeline
import com.ultimatevideo.uveditor.domain.Track
import com.ultimatevideo.uveditor.domain.TrackType
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
    fun `edited placement is written while extras survive`() {
        val dto = project(TrackDto("v", "video", 0, listOf(clipDto("c1", 0, gain = -6.0))))
        val moved = Timeline(listOf(Track("v", TrackType.VIDEO, listOf(Clip("c1", "a1", FrameIndex(50), FrameIndex(10), FrameIndex(60))))))

        val clip = TimelineMapper.toDto(dto, moved, emptyList()).tracks.single().clips.single()

        assertEquals(50, clip.timelineStartFrame)
        assertEquals(10, clip.sourceInFrame)
        assertEquals(60, clip.sourceOutFrame)
        assertEquals(-6.0, clip.gainDb, 0.0)
        assertEquals(12.0, clip.transform.rotation, 0.0)
        assertEquals("Rec2020-HLG", clip.colorOverride)
    }

    @Test
    fun `derived clip inherits extras from its parent id`() {
        val dto = project(TrackDto("v", "video", 0, listOf(clipDto("c1", 0, gain = -6.0))))
        val split = Timeline(
            listOf(
                Track(
                    "v",
                    TrackType.VIDEO,
                    listOf(
                        Clip("c1", "a1", FrameIndex(0), FrameIndex(0), FrameIndex(40)),
                        Clip("c1~x9", "a1", FrameIndex(40), FrameIndex(40), FrameIndex(100)),
                    ),
                ),
            ),
        )

        val clips = TimelineMapper.toDto(dto, split, emptyList()).tracks.single().clips

        assertEquals(-6.0, clips.first { it.id == "c1~x9" }.gainDb, 0.0)
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
