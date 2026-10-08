package com.qtekfun.ultimatevideoeditor.data

import com.qtekfun.ultimatevideoeditor.data.model.ClipDto
import com.qtekfun.ultimatevideoeditor.data.model.ProjectDto
import com.qtekfun.ultimatevideoeditor.data.model.ProjectSettingsDto
import com.qtekfun.ultimatevideoeditor.data.model.TitleDto
import com.qtekfun.ultimatevideoeditor.data.model.TrackDto
import com.qtekfun.ultimatevideoeditor.data.model.TransitionDto
import com.qtekfun.ultimatevideoeditor.domain.TitleAlignment
import com.qtekfun.ultimatevideoeditor.domain.TitleContent
import com.qtekfun.ultimatevideoeditor.domain.Transition
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleTransitionMapperTest {

    private val settings = ProjectSettingsDto(1920, 1080, 30, 1, "Rec709-SDR")

    private fun media(id: String, start: Long, srcIn: Long = 0) = ClipDto(
        id = id,
        assetId = "a1",
        timelineStartFrame = start,
        sourceInFrame = srcIn,
        sourceOutFrame = srcIn + 100,
    )

    private fun titleClip(id: String, start: Long, title: TitleDto) = ClipDto(
        id = id,
        assetId = null,
        timelineStartFrame = start,
        sourceInFrame = 0,
        sourceOutFrame = 40,
        title = title,
    )

    private fun project(
        tracks: List<TrackDto>,
        transitions: List<TransitionDto> = emptyList(),
    ) = ProjectDto(id = "p", name = "P", settings = settings, tracks = tracks, transitions = transitions)

    private val videoTrack = TrackDto("v", "video", 0, listOf(media("A", 0), media("B", 100, srcIn = 20)))

    @Test
    fun `titles round trip with colour alignment and style`() {
        val title = TitleDto("Hello", sizeFraction = 0.12, color = "#80FF0000", alignment = "right", bold = true)
        val dto = project(listOf(videoTrack, TrackDto("t", "title", 1, listOf(titleClip("T1", 10, title)))))

        val timeline = TimelineMapper.toTimeline(dto)
        val content = timeline.track("t")!!.clip("T1")!!.title!!

        assertEquals(TitleContent("Hello", 0.12, 0x80FF0000.toInt(), TitleAlignment.RIGHT, true), content)
        assertEquals(dto.tracks, TimelineMapper.toDto(dto, timeline, dto.mediaLibrary).tracks)
    }

    @Test
    fun `the title outline flag round trips and defaults to off`() {
        val outlined = TitleDto("Caption", outline = true)
        val plain = TitleDto("Plain")
        val dto = project(listOf(videoTrack, TrackDto("t", "title", 1, listOf(titleClip("T1", 0, outlined), titleClip("T2", 50, plain)))))

        val timeline = TimelineMapper.toTimeline(dto)

        assertTrue(timeline.track("t")!!.clip("T1")!!.title!!.outline)
        assertFalse(timeline.track("t")!!.clip("T2")!!.title!!.outline)
        assertEquals(dto.tracks, TimelineMapper.toDto(dto, timeline, dto.mediaLibrary).tracks)
        // Projects written before the flag existed still load.
        assertFalse(Json.decodeFromString<TitleDto>("""{"text":"Old"}""").outline)
    }

    @Test
    fun `transitions round trip`() {
        val dto = project(listOf(videoTrack), listOf(TransitionDto("x", "crossfade", "A", "B", 12)))

        val timeline = TimelineMapper.toTimeline(dto)

        assertEquals(Transition("x", "A", "B", 12), timeline.transitions.single())
        assertEquals(dto.transitions, TimelineMapper.toDto(dto, timeline, dto.mediaLibrary).transitions)
    }

    @Test
    fun `json survives a full encode and decode`() {
        val title = TitleDto("Caption")
        val dto = project(
            listOf(videoTrack, TrackDto("t", "title", 1, listOf(titleClip("T1", 0, title)))),
            listOf(TransitionDto("x", "crossfade", "A", "B", 8)),
        )

        val json = Json.encodeToString(dto)
        val back = Json.decodeFromString<ProjectDto>(json)

        assertEquals(dto, back)
        assertEquals(TimelineMapper.toTimeline(dto), TimelineMapper.toTimeline(back))
    }

    @Test
    fun `old projects without titles or transitions still load`() {
        val dto = project(listOf(videoTrack))

        val timeline = TimelineMapper.toTimeline(dto)

        assertNull(timeline.track("v")!!.clip("A")!!.title)
        assertEquals(emptyList<Transition>(), timeline.transitions)
    }

    @Test
    fun `a transition whose clips are not adjacent is reported as corrupt`() {
        val dto = project(listOf(videoTrack), listOf(TransitionDto("x", "crossfade", "B", "A", 8)))

        assertThrows(ProjectError.Corrupt::class.java) { TimelineMapper.toTimeline(dto) }
    }

    @Test
    fun `an unknown transition type is reported as corrupt`() {
        val dto = project(listOf(videoTrack), listOf(TransitionDto("x", "page-curl", "A", "B", 8)))

        assertThrows(ProjectError.Corrupt::class.java) { TimelineMapper.toTimeline(dto) }
    }

    @Test
    fun `a malformed title colour or alignment is reported as corrupt`() {
        fun load(title: TitleDto) = TimelineMapper.toTimeline(
            project(listOf(TrackDto("t", "title", 0, listOf(titleClip("T1", 0, title))))),
        )

        assertThrows(ProjectError.Corrupt::class.java) { load(TitleDto("x", color = "red")) }
        assertThrows(ProjectError.Corrupt::class.java) { load(TitleDto("x", color = "#FFF")) }
        assertThrows(ProjectError.Corrupt::class.java) { load(TitleDto("x", alignment = "justify")) }
    }

    @Test
    fun `a title clip on a video track is reported as corrupt`() {
        val dto = project(listOf(TrackDto("v", "video", 0, listOf(titleClip("T1", 0, TitleDto("x"))))))

        assertThrows(ProjectError.Corrupt::class.java) { TimelineMapper.toTimeline(dto) }
    }
}
