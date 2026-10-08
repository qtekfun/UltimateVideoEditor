package com.qtekfun.ultimatevideoeditor.data

import com.qtekfun.ultimatevideoeditor.data.model.ClipDto
import com.qtekfun.ultimatevideoeditor.data.model.KeyframeDto
import com.qtekfun.ultimatevideoeditor.data.model.ProjectDto
import com.qtekfun.ultimatevideoeditor.data.model.ProjectSettingsDto
import com.qtekfun.ultimatevideoeditor.data.model.TrackDto
import com.qtekfun.ultimatevideoeditor.data.model.TransformDto
import com.qtekfun.ultimatevideoeditor.domain.Interpolation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyframeMapperTest {

    private val settings = ProjectSettingsDto(1080, 1920, 30, 1, "Rec709-SDR")

    private fun project(keyframes: List<KeyframeDto>) = ProjectDto(
        id = "p",
        name = "P",
        settings = settings,
        tracks = listOf(
            TrackDto("v", "video", 0, listOf(ClipDto("A", "a1", 0, 0, 100, keyframes = keyframes))),
        ),
    )

    private val keys = listOf(
        KeyframeDto(0, TransformDto(scale = listOf(1.0, 1.0), position = listOf(0.0, 0.0)), "linear"),
        KeyframeDto(50, TransformDto(scale = listOf(2.0, 2.0), rotation = 90.0, position = listOf(10.0, -20.0), opacity = 0.5), "ease"),
        KeyframeDto(99, TransformDto(), "hold"),
    )

    @Test
    fun `keyframes round trip with pose and interpolation`() {
        val dto = project(keys)
        val timeline = TimelineMapper.toTimeline(dto)
        val clip = timeline.track("v")!!.clip("A")!!

        assertEquals(listOf(0L, 50L, 99L), clip.keyframes.map { it.frame })
        assertEquals(listOf(Interpolation.LINEAR, Interpolation.EASE, Interpolation.HOLD), clip.keyframes.map { it.interpolation })
        assertEquals(2.0, clip.keyframes[1].transform.scaleX, 0.0)
        assertEquals(0.5, clip.keyframes[1].transform.opacity, 0.0)
        assertEquals(dto.tracks, TimelineMapper.toDto(dto, timeline, dto.mediaLibrary).tracks)
    }

    @Test
    fun `projects written before keyframes existed load without any`() {
        val timeline = TimelineMapper.toTimeline(project(emptyList()))

        assertTrue(timeline.track("v")!!.clip("A")!!.keyframes.isEmpty())
    }

    @Test
    fun `keyframes survive the JSON text form`() {
        val text = ProjectJson.encode(project(keys))
        val decoded = ProjectJson.decode(text)

        assertEquals(keys, decoded.tracks.single().clips.single().keyframes)
    }

    @Test
    fun `an unknown interpolation is reported as a corrupt project`() {
        val dto = project(listOf(KeyframeDto(0, TransformDto(), "bounce")))

        assertThrows(ProjectError.Corrupt::class.java) { TimelineMapper.toTimeline(dto) }
    }

    @Test
    fun `a keyframe outside the clip is reported as a corrupt project`() {
        val dto = project(listOf(KeyframeDto(100, TransformDto(), "linear")))

        assertThrows(ProjectError.Corrupt::class.java) { TimelineMapper.toTimeline(dto) }
    }

    @Test
    fun `unordered keyframes are reported as a corrupt project`() {
        val dto = project(listOf(KeyframeDto(10, TransformDto()), KeyframeDto(5, TransformDto())))

        assertThrows(ProjectError.Corrupt::class.java) { TimelineMapper.toTimeline(dto) }
    }
}
