package com.qtekfun.ultimatevideoeditor.data

import com.qtekfun.ultimatevideoeditor.data.model.ClipDto
import com.qtekfun.ultimatevideoeditor.data.model.MarkerDto
import com.qtekfun.ultimatevideoeditor.data.model.ProjectDto
import com.qtekfun.ultimatevideoeditor.data.model.ProjectSettingsDto
import com.qtekfun.ultimatevideoeditor.data.model.TrackDto
import com.qtekfun.ultimatevideoeditor.domain.FrameIndex
import com.qtekfun.ultimatevideoeditor.domain.Marker
import com.qtekfun.ultimatevideoeditor.domain.MarkerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MarkerMapperTest {
    private val settings = ProjectSettingsDto(1920, 1080, 30, 1, "Rec709-SDR")

    private fun project(markers: List<MarkerDto> = emptyList()) = ProjectDto(
        id = "p",
        name = "P",
        settings = settings,
        tracks = listOf(TrackDto("v", "video", 0, emptyList<ClipDto>())),
        markers = markers,
    )

    @Test
    fun `markers are read into the timeline sorted with their kinds`() {
        val dto = project(listOf(MarkerDto("b", 90, "beat"), MarkerDto("a", 30)))
        val timeline = TimelineMapper.toTimeline(dto)
        assertEquals(
            listOf(Marker("a", FrameIndex(30), MarkerKind.MANUAL), Marker("b", FrameIndex(90), MarkerKind.BEAT)),
            timeline.markers.sortedBy { it.frame },
        )
    }

    @Test
    fun `markers survive a round trip through the json text`() {
        val dto = project(listOf(MarkerDto("a", 30), MarkerDto("b", 90, "beat")))
        val back = TimelineMapper.toDto(dto, TimelineMapper.toTimeline(dto), dto.mediaLibrary)
        assertEquals(dto.markers, back.markers)
        assertEquals(dto.markers, ProjectJson.decode(ProjectJson.encode(back)).markers)
    }

    @Test
    fun `projects written before markers existed load with none`() {
        val old = """{"version":1,"id":"p","name":"P","settings":{"width":1920,"height":1080,"fpsNum":30,"fpsDen":1,"colorSpace":"Rec709-SDR"}}"""
        val dto = ProjectJson.decode(old)
        assertEquals(emptyList<MarkerDto>(), dto.markers)
        assertEquals(emptyList<Marker>(), TimelineMapper.toTimeline(dto).markers)
    }

    @Test
    fun `an unknown kind or a broken marker list is reported as corrupt`() {
        assertThrows(ProjectError.Corrupt::class.java) { TimelineMapper.toTimeline(project(listOf(MarkerDto("a", 1, "weird")))) }
        assertThrows(ProjectError.Corrupt::class.java) { TimelineMapper.toTimeline(project(listOf(MarkerDto("a", 1), MarkerDto("a", 2)))) }
        assertThrows(ProjectError.Corrupt::class.java) { TimelineMapper.toTimeline(project(listOf(MarkerDto("a", -4)))) }
    }
}
