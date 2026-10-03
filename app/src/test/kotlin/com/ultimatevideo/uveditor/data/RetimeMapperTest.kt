package com.ultimatevideo.uveditor.data

import com.ultimatevideo.uveditor.data.model.ClipDto
import com.ultimatevideo.uveditor.data.model.ProjectDto
import com.ultimatevideo.uveditor.data.model.ProjectSettingsDto
import com.ultimatevideo.uveditor.data.model.SpeedKeyDto
import com.ultimatevideo.uveditor.data.model.TrackDto
import com.ultimatevideo.uveditor.domain.SpeedKey
import com.ultimatevideo.uveditor.domain.isFreeze
import com.ultimatevideo.uveditor.domain.isRetimed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RetimeMapperTest {

    private val settings = ProjectSettingsDto(1920, 1080, 30, 1, "Rec709-SDR")

    private fun project(clip: ClipDto) = ProjectDto(
        id = "p",
        name = "P",
        settings = settings,
        tracks = listOf(TrackDto("v", "video", 0, listOf(clip))),
    )

    private fun roundTrip(dto: ProjectDto): ProjectDto {
        val timeline = TimelineMapper.toTimeline(dto)
        return TimelineMapper.toDto(dto, timeline, dto.mediaLibrary)
    }

    @Test
    fun `speed reverse and ramp round trip`() {
        val dto = project(
            ClipDto(
                "A", "a1", 0, 10, 110,
                timelineFrames = 50,
                reverse = true,
                speedRamp = listOf(SpeedKeyDto(0, 400), SpeedKeyDto(25, 1500), SpeedKeyDto(49, 600)),
            ),
        )
        val clip = TimelineMapper.toTimeline(dto).track("v")!!.clip("A")!!
        assertEquals(50L, clip.durationFrames)
        assertTrue(clip.reverse)
        assertEquals(listOf(SpeedKey(0, 400), SpeedKey(25, 1500), SpeedKey(49, 600)), clip.speedRamp)
        assertEquals(dto.tracks, roundTrip(dto).tracks)
    }

    @Test
    fun `a freeze frame round trips`() {
        val dto = project(ClipDto("A", "a1", 0, 42, 43, timelineFrames = 30))
        val clip = TimelineMapper.toTimeline(dto).track("v")!!.clip("A")!!
        assertTrue(clip.isFreeze)
        assertEquals(dto.tracks, roundTrip(dto).tracks)
    }

    @Test
    fun `projects written before retiming load at normal speed`() {
        val clip = TimelineMapper.toTimeline(project(ClipDto("A", "a1", 0, 0, 100))).track("v")!!.clip("A")!!
        assertNull(clip.retimedFrames)
        assertFalse(clip.reverse)
        assertFalse(clip.isRetimed)
        assertEquals(100L, clip.durationFrames)
    }

    @Test
    fun `a retime that restates the clip's own length is corrupt`() {
        val dto = project(ClipDto("A", "a1", 0, 0, 100, timelineFrames = 100))
        assertThrows(ProjectError.Corrupt::class.java) { TimelineMapper.toTimeline(dto) }
    }

    @Test
    fun `a ramp outside the clip is corrupt`() {
        val dto = project(ClipDto("A", "a1", 0, 0, 100, speedRamp = listOf(SpeedKeyDto(100, 1000))))
        assertThrows(ProjectError.Corrupt::class.java) { TimelineMapper.toTimeline(dto) }
    }
}
