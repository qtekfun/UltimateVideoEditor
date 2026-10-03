package com.ultimatevideo.uveditor.data

import com.ultimatevideo.uveditor.data.model.ClipDto
import com.ultimatevideo.uveditor.data.model.ProjectDto
import com.ultimatevideo.uveditor.data.model.ProjectSettingsDto
import com.ultimatevideo.uveditor.data.model.TitleDto
import com.ultimatevideo.uveditor.data.model.TitleWordDto
import com.ultimatevideo.uveditor.data.model.TrackDto
import com.ultimatevideo.uveditor.domain.TitleAnimation
import com.ultimatevideo.uveditor.domain.TitleLook
import com.ultimatevideo.uveditor.domain.TitleWord
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class CaptionMapperTest {

    private val settings = ProjectSettingsDto(1080, 1920, 30, 1, "Rec709-SDR")

    private fun project(title: TitleDto) = ProjectDto(
        id = "p",
        name = "P",
        settings = settings,
        tracks = listOf(
            TrackDto(
                "t",
                "title",
                0,
                listOf(ClipDto(id = "caption-1", assetId = null, timelineStartFrame = 30, sourceInFrame = 0, sourceOutFrame = 45, title = title)),
            ),
        ),
    )

    @Test
    fun `word timing animation and highlight colour round trip`() {
        val title = TitleDto(
            "Say it loud",
            bold = true,
            outline = true,
            words = listOf(TitleWordDto("Say", 0, 10), TitleWordDto("it", 10, 20), TitleWordDto("loud", 20, 35)),
            animation = "karaoke",
            highlight = "#FF00E5FF",
        )
        val dto = project(title)

        val timeline = TimelineMapper.toTimeline(dto)
        val content = timeline.track("t")!!.clip("caption-1")!!.title!!

        assertEquals(TitleAnimation.KARAOKE, content.animation)
        assertEquals(0xFF00E5FF.toInt(), content.highlightArgb)
        assertEquals(listOf(TitleWord("Say", 0, 10), TitleWord("it", 10, 20), TitleWord("loud", 20, 35)), content.words)
        // The per-frame look is a renderer detail and is never stored.
        assertEquals(TitleLook.FULL, content.look)
        assertEquals(dto.tracks, TimelineMapper.toDto(dto, timeline, dto.mediaLibrary).tracks)
    }

    @Test
    fun `every animation name maps both ways`() {
        for (animation in TitleAnimation.entries) {
            val dto = project(TitleDto("Hi there", words = listOf(TitleWordDto("Hi", 0, 5)), animation = animation.name.lowercase()))
            val timeline = TimelineMapper.toTimeline(dto)

            assertEquals(animation, timeline.track("t")!!.clip("caption-1")!!.title!!.animation)
            assertEquals(dto.tracks, TimelineMapper.toDto(dto, timeline, dto.mediaLibrary).tracks)
        }
    }

    @Test
    fun `titles saved before captions were animated still load as static`() {
        val old = Json.decodeFromString<TitleDto>("""{"text":"Old","bold":true,"outline":true}""")

        assertTrue(old.words.isEmpty())
        assertEquals("none", old.animation)
        val content = TimelineMapper.toTimeline(project(old)).track("t")!!.clip("caption-1")!!.title!!
        assertEquals(TitleAnimation.NONE, content.animation)
        assertTrue(content.words.isEmpty())
    }

    @Test
    fun `an unknown animation or a malformed highlight colour is reported as a corrupt project`() {
        assertThrows(ProjectError.Corrupt::class.java) { TimelineMapper.toTimeline(project(TitleDto("Hi", animation = "wobble"))) }
        assertThrows(ProjectError.Corrupt::class.java) { TimelineMapper.toTimeline(project(TitleDto("Hi", highlight = "yellow"))) }
    }

    @Test
    fun `json survives a full encode and decode with words`() {
        val dto = project(TitleDto("Hi there", words = listOf(TitleWordDto("Hi", 0, 5), TitleWordDto("there", 6, 14)), animation = "pop_in"))

        val back = Json.decodeFromString<ProjectDto>(Json.encodeToString(dto))

        assertEquals(dto, back)
        assertEquals(TimelineMapper.toTimeline(dto), TimelineMapper.toTimeline(back))
    }
}
