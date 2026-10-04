package com.ultimatevideo.uveditor.domain

import com.ultimatevideo.uveditor.data.ProjectJson
import com.ultimatevideo.uveditor.data.TimelineMapper
import com.ultimatevideo.uveditor.data.model.MarkerDto
import com.ultimatevideo.uveditor.data.model.ProjectDto
import com.ultimatevideo.uveditor.data.model.ProjectSettingsDto
import com.ultimatevideo.uveditor.data.model.TrackDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Name, move and previous / next of markers: the pure operations behind the popup and the ruler drag. */
class MarkerEditTest {
    private val base = Timeline(
        markers = listOf(
            Marker("m1", FrameIndex(10)),
            Marker("m2", FrameIndex(50), MarkerKind.BEAT),
            Marker("m3", FrameIndex(90), note = "old", color = MarkerColor.GREEN),
        ),
    )

    @Test
    fun `update sets name, note and colour together and trims`() {
        val result = MarkerOps.update(base, "m1", "  Intro ", " a note ", MarkerColor.RED).getOrFail()
        val marker = result.markers.first { it.id == "m1" }
        assertEquals("Intro", marker.name)
        assertEquals("a note", marker.note)
        assertEquals(MarkerColor.RED, marker.color)
        assertEquals(base.markers.drop(1), result.markers.drop(1))
    }

    @Test
    fun `a blank name clears it and an unchanged update returns the same timeline`() {
        val named = MarkerOps.update(base, "m1", "Intro", null, null).getOrFail()
        assertNull(MarkerOps.update(named, "m1", "   ", null, null).getOrFail().markers.first().name)
        assertTrue(MarkerOps.update(base, "m1", null, null, null).getOrFail() === base)
        assertTrue(MarkerOps.update(named, "m1", "Intro", null, null).getOrFail() === named)
    }

    @Test
    fun `annotate keeps the name`() {
        val named = MarkerOps.update(base, "m1", "Intro", null, null).getOrFail()
        assertEquals("Intro", MarkerOps.annotate(named, "m1", "note", MarkerColor.BLUE).getOrFail().markers.first().name)
    }

    @Test
    fun `names have a length limit and unknown markers are errors`() {
        assertTrue(MarkerOps.update(base, "m1", "n".repeat(MarkerOps.MAX_NAME_LENGTH + 1), null, null).errorOrFail() is EditError.InvalidMarker)
        assertEquals(EditError.MarkerNotFound("zz"), MarkerOps.update(base, "zz", "x", null, null).errorOrFail())
        assertEquals(EditError.MarkerNotFound("zz"), MarkerOps.move(base, "zz", FrameIndex(3)).errorOrFail())
    }

    @Test
    fun `editing is one undo step and undo is exact`() {
        val history = EditHistory(base).execute(EditMarker("m2", "Drop", "big", MarkerColor.PURPLE)).getOrFail()
        assertEquals("Drop", history.timeline.markers[1].name)
        assertEquals(MarkerKind.BEAT, history.timeline.markers[1].kind)
        assertEquals(base, history.undo().timeline)
        assertEquals(history.timeline, history.undo().redo().timeline)
    }

    @Test
    fun `move keeps the order sorted`() {
        val moved = MarkerOps.move(base, "m1", FrameIndex(70)).getOrFail()
        assertEquals(listOf("m2", "m1", "m3"), moved.markers.map { it.id })
        assertEquals(listOf(50L, 70L, 90L), moved.markers.map { it.frame.value })
        assertEquals(base.markers.first { it.id == "m1" }.copy(frame = FrameIndex(70)), moved.markers[1])
    }

    @Test
    fun `move onto another marker or before zero is refused and onto itself changes nothing`() {
        assertTrue(MarkerOps.move(base, "m1", FrameIndex(50)).errorOrFail() is EditError.InvalidMarker)
        assertTrue(MarkerOps.move(base, "m1", FrameIndex(-1)).errorOrFail() is EditError.InvalidMarker)
        assertTrue(MarkerOps.move(base, "m1", FrameIndex(10)).getOrFail() === base)
    }

    @Test
    fun `moving is one undo step and undo is exact`() {
        val history = EditHistory(base).execute(MoveMarker("m3", FrameIndex(20))).getOrFail()
        assertEquals(listOf("m1", "m3", "m2"), history.timeline.markers.map { it.id })
        assertEquals(base, history.undo().timeline)
    }

    @Test
    fun `previous and next are strict`() {
        assertNull(MarkerOps.previous(base.markers, FrameIndex(10)))
        assertEquals("m1", MarkerOps.previous(base.markers, FrameIndex(11))?.id)
        assertEquals("m2", MarkerOps.next(base.markers, FrameIndex(10))?.id)
        assertEquals("m3", MarkerOps.next(base.markers, FrameIndex(50))?.id)
        assertNull(MarkerOps.next(base.markers, FrameIndex(90)))
        assertNull(MarkerOps.next(emptyList(), FrameIndex(0)))
    }

    @Test
    fun `deleting a marker is one undo step`() {
        val history = EditHistory(base).execute(RemoveMarker("m2")).getOrFail()
        assertEquals(listOf("m1", "m3"), history.timeline.markers.map { it.id })
        assertEquals(base, history.undo().timeline)
    }

    @Test
    fun `a name survives the project file and old files load without one`() {
        val dto = ProjectDto(
            id = "p",
            name = "P",
            settings = ProjectSettingsDto(1920, 1080, 30, 1, "Rec709-SDR"),
            tracks = listOf(TrackDto("v", "video", 0, emptyList())),
            markers = listOf(MarkerDto("a", 30, name = "Intro"), MarkerDto("b", 90, "beat")),
        )
        val timeline = TimelineMapper.toTimeline(dto)
        assertEquals("Intro", timeline.markers.first().name)
        val saved = TimelineMapper.toDto(dto, timeline, dto.mediaLibrary)
        assertEquals(dto.markers, ProjectJson.decode(ProjectJson.encode(saved)).markers)
        assertNull(TimelineMapper.toTimeline(ProjectJson.decode(ProjectJson.encode(saved))).markers[1].name)
    }

    @Test
    fun `a stored name is trimmed, cut to the limit and blank means none`() {
        val long = "x".repeat(MarkerOps.MAX_NAME_LENGTH + 20)
        val dto = ProjectDto(
            id = "p",
            name = "P",
            settings = ProjectSettingsDto(1920, 1080, 30, 1, "Rec709-SDR"),
            tracks = listOf(TrackDto("v", "video", 0, emptyList())),
            markers = listOf(MarkerDto("a", 30, name = "   "), MarkerDto("b", 90, name = long)),
        )
        val markers = TimelineMapper.toTimeline(dto).markers
        assertNull(markers[0].name)
        assertEquals(MarkerOps.MAX_NAME_LENGTH, markers[1].name?.length)
    }
}
