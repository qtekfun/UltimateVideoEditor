package com.qtekfun.ultimatevideoeditor.domain

import com.qtekfun.ultimatevideoeditor.data.ProjectJson
import com.qtekfun.ultimatevideoeditor.data.TimelineMapper
import com.qtekfun.ultimatevideoeditor.data.model.MarkerDto
import com.qtekfun.ultimatevideoeditor.data.model.ProjectDto
import com.qtekfun.ultimatevideoeditor.data.model.ProjectSettingsDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The flag list as a shot list: what it selects, how it groups, and the text and CSV a person takes to a notes app. */
class ShotListTest {
    private fun tc(frame: Long) = "t$frame"
    private fun group(color: MarkerColor?) = color?.name ?: "No colour"

    private val flags = listOf(
        Marker("c", FrameIndex(300), color = MarkerColor.BLUE, name = "Port", done = true),
        Marker("a", FrameIndex(30), color = MarkerColor.RED, name = "Lighthouse", note = "wide shot"),
        Marker("beat", FrameIndex(60), MarkerKind.BEAT, color = MarkerColor.RED),
        Marker("b", FrameIndex(90), color = MarkerColor.RED, note = "hands cooking"),
        Marker("d", FrameIndex(120)),
    )

    @Test
    fun `beat markers never appear and the rest is in time order`() {
        val all = ShotList.select(flags, ShotList.Filter(onlyPending = false))
        assertEquals(listOf("a", "b", "d", "c"), all.map { it.id })
    }

    @Test
    fun `only pending drops the flags already shot`() {
        assertEquals(listOf("a", "b", "d"), ShotList.select(flags, ShotList.Filter(onlyPending = true)).map { it.id })
    }

    @Test
    fun `a colour filter keeps the chosen colours, with none as a choice of its own`() {
        val reds = ShotList.select(flags, ShotList.Filter(onlyPending = false, colors = setOf(MarkerColor.RED)))
        assertEquals(listOf("a", "b"), reds.map { it.id })
        val noneAndBlue = ShotList.select(flags, ShotList.Filter(onlyPending = false, colors = setOf(null, MarkerColor.BLUE)))
        assertEquals(listOf("d", "c"), noneAndBlue.map { it.id })
    }

    @Test
    fun `groups follow the colour order with no colour last and skip empty ones`() {
        val groups = ShotList.grouped(ShotList.select(flags, ShotList.Filter(onlyPending = false)))
        assertEquals(listOf(MarkerColor.RED, MarkerColor.BLUE, null), groups.map { it.color })
        assertEquals(listOf("a", "b"), groups.first().flags.map { it.id })
    }

    @Test
    fun `text has a checkbox per flag, grouped, with the name and note joined`() {
        val text = ShotList.text("Trip", ShotList.select(flags, ShotList.Filter(onlyPending = false)), ::tc, ::group)
        assertEquals(
            """
            Trip

            RED (2)
            [ ] t30 · Lighthouse: wide shot
            [ ] t90 · hands cooking

            BLUE (1)
            [x] t300 · Port

            No colour (1)
            [ ] t120
            """.trimIndent(),
            text,
        )
    }

    @Test
    fun `an empty selection gives only the title`() {
        assertEquals("Trip", ShotList.text("Trip", emptyList(), ::tc, ::group))
    }

    @Test
    fun `line breaks in a note stay on one line so the checkbox list is not broken`() {
        val flag = Marker("n", FrameIndex(5), name = "A\nB", note = "first\r\n  second")
        assertEquals("Trip\n\nNo colour (1)\n[ ] t5 · A B: first second", ShotList.text("Trip", listOf(flag), ::tc, ::group))
    }

    @Test
    fun `csv quotes commas, quotes and line breaks and starts with the header`() {
        val flag = Marker("q", FrameIndex(7), color = MarkerColor.GREEN, name = "Say \"hi\", loud", note = "two\nlines", done = true)
        val csv = ShotList.csv(listOf("Done", "Colour", "Time", "Name", "Note"), listOf(flag, Marker("p", FrameIndex(8))), ::tc) { it?.name.orEmpty() }
        assertEquals(
            "Done,Colour,Time,Name,Note\r\nx,GREEN,t7,\"Say \"\"hi\"\", loud\",\"two\nlines\"\r\n,,t8,,\r\n",
            csv,
        )
    }

    @Test
    fun `ticking a flag is one command that leaves the label alone and unticking restores it`() {
        val timeline = Timeline(markers = listOf(Marker("a", FrameIndex(30), name = "Lighthouse", note = "wide", color = MarkerColor.RED)))
        val ticked = SetMarkerDone("a", true).apply(timeline).getOrFail()
        assertTrue(ticked.markers.single().done)
        assertEquals("Lighthouse", ticked.markers.single().name)
        assertEquals("wide", ticked.markers.single().note)
        assertEquals(timeline, SetMarkerDone("a", false).apply(ticked).getOrFail())
        assertTrue(SetMarkerDone("a", false).apply(timeline).getOrFail() === timeline)
        assertTrue(SetMarkerDone("zzz", true).apply(timeline) is EditResult.Failure)
    }

    @Test
    fun `editing the popup keeps the done state unless it says otherwise`() {
        val timeline = Timeline(markers = listOf(Marker("a", FrameIndex(30), done = true)))
        assertTrue(EditMarker("a", "New", null, null).apply(timeline).getOrFail().markers.single().done)
        assertFalse(EditMarker("a", "New", null, null, done = false).apply(timeline).getOrFail().markers.single().done)
    }

    @Test
    fun `done survives the project file and old files load as not done`() {
        val settings = ProjectSettingsDto(1920, 1080, 30, 1, "Rec709-SDR")
        val dto = ProjectDto(id = "p", name = "P", settings = settings, markers = listOf(MarkerDto("a", 30, done = true), MarkerDto("b", 60)))
        val back = TimelineMapper.toDto(dto, TimelineMapper.toTimeline(dto), dto.mediaLibrary)
        val reread = TimelineMapper.toTimeline(ProjectJson.decode(ProjectJson.encode(back)))
        assertEquals(listOf(true, false), reread.markers.map { it.done })
        val old = """{"version":1,"id":"p","name":"P","settings":{"width":1920,"height":1080,"fpsNum":30,"fpsDen":1,"colorSpace":"Rec709-SDR"},"markers":[{"id":"a","frame":3}]}"""
        assertFalse(TimelineMapper.toTimeline(ProjectJson.decode(old)).markers.single().done)
    }
}
