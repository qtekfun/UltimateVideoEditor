package com.ultimatevideo.uveditor.domain

import com.ultimatevideo.uveditor.data.ProjectJson
import com.ultimatevideo.uveditor.data.TimelineMapper
import com.ultimatevideo.uveditor.data.interchange.sampleProject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkerAnnotateTest {
    private val base = Timeline(
        markers = listOf(Marker("m1", FrameIndex(10)), Marker("m2", FrameIndex(50), MarkerKind.BEAT)),
    )

    @Test
    fun `a note and colour are set and trimmed`() {
        val result = MarkerOps.annotate(base, "m1", "  check this  ", MarkerColor.RED).getOrFail()
        val marker = result.markers.first { it.id == "m1" }
        assertEquals("check this", marker.note)
        assertEquals(MarkerColor.RED, marker.color)
        assertEquals(FrameIndex(10), marker.frame)
        assertEquals(base.markers[1], result.markers[1])
    }

    @Test
    fun `a blank note clears it and no change returns the same timeline`() {
        val noted = MarkerOps.annotate(base, "m1", "x", MarkerColor.BLUE).getOrFail()
        val cleared = MarkerOps.annotate(noted, "m1", "   ", null).getOrFail()
        assertNull(cleared.markers.first().note)
        assertNull(cleared.markers.first().color)
        assertTrue(MarkerOps.annotate(base, "m1", null, null).getOrFail() === base)
    }

    @Test
    fun `unknown marker and too long notes are errors`() {
        assertEquals(EditError.MarkerNotFound("zz"), MarkerOps.annotate(base, "zz", "x", null).errorOrFail())
        assertTrue(MarkerOps.annotate(base, "m1", "n".repeat(MarkerOps.MAX_NOTE_LENGTH + 1), null).errorOrFail() is EditError.InvalidMarker)
    }

    @Test
    fun `annotating is one undo step`() {
        val history = EditHistory(base).execute(AnnotateMarker("m1", "hello", MarkerColor.GREEN)).getOrFail()
        assertEquals("hello", history.timeline.markers.first().note)
        assertEquals(base, history.undo().timeline)
    }

    @Test
    fun `notes and colours survive the project file`() {
        val dto = sampleProject()
        val timeline = TimelineMapper.toTimeline(dto)
        val mk = timeline.markers.first { it.id == "mk1" }
        assertEquals("Cut here & <check>", mk.note)
        assertEquals(MarkerColor.RED, mk.color)
        val saved = TimelineMapper.toDto(dto, timeline, dto.mediaLibrary)
        val reloaded = ProjectJson.decode(ProjectJson.encode(saved))
        assertEquals(dto.markers, reloaded.markers)
    }

    @Test
    fun `a project without notes loads as before`() {
        val text = ProjectJson.encode(sampleProject()).replace("\"note\": \"Cut here & <check>\",", "").replace(Regex("\"color\": \"red\""), "\"color\": null")
        val timeline = TimelineMapper.toTimeline(ProjectJson.decode(text))
        assertNull(timeline.markers.first { it.id == "mk1" }.color)
    }
}
