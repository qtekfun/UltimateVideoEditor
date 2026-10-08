package com.qtekfun.ultimatevideoeditor.data

import com.qtekfun.ultimatevideoeditor.data.interchange.Fcpxml
import com.qtekfun.ultimatevideoeditor.data.model.ClipDto
import com.qtekfun.ultimatevideoeditor.data.model.MarkerDto
import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto
import com.qtekfun.ultimatevideoeditor.data.model.ProjectDto
import com.qtekfun.ultimatevideoeditor.data.model.ProjectSettingsDto
import com.qtekfun.ultimatevideoeditor.data.model.TrackDto
import com.qtekfun.ultimatevideoeditor.domain.AddMarker
import com.qtekfun.ultimatevideoeditor.domain.EditCommand
import com.qtekfun.ultimatevideoeditor.domain.EditHistory
import com.qtekfun.ultimatevideoeditor.domain.EditResult
import com.qtekfun.ultimatevideoeditor.domain.FrameIndex
import com.qtekfun.ultimatevideoeditor.domain.Marker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Anchored markers in project.json: new optional fields, old files open as free markers, interchange reads resolved frames. */
class MarkerAnchorMapperTest {
    private val asset = MediaAssetDto("a1", "content://m/a1", durationFrames = 900, nativeFpsNum = 30, nativeFpsDen = 1, colorSpace = "Rec709-SDR")

    private fun project(markers: List<MarkerDto>) = ProjectDto(
        id = "p",
        name = "P",
        settings = ProjectSettingsDto(1920, 1080, 30, 1, "Rec709-SDR"),
        mediaLibrary = listOf(asset),
        tracks = listOf(TrackDto("v", "video", 0, listOf(ClipDto("c1", "a1", 0, 0, 100), ClipDto("c2", "a1", 100, 0, 100)))),
        markers = markers,
    )

    @Test
    fun `an anchored marker survives a round trip through the json text`() {
        val dto = project(listOf(MarkerDto("a", 130, anchorClipId = "c2", offset = 30), MarkerDto("b", 10)))
        val timeline = TimelineMapper.toTimeline(dto)
        assertEquals(Marker("a", FrameIndex(130), anchorClipId = "c2", offsetFrames = 30), timeline.markers.last())
        val back = ProjectJson.decode(ProjectJson.encode(TimelineMapper.toDto(dto, timeline, dto.mediaLibrary)))
        assertEquals(dto.markers.sortedBy { it.frame }, back.markers.sortedBy { it.frame })
    }

    @Test
    fun `an old file without anchors opens as free markers and is not attached on load`() {
        val old = """{"version":1,"id":"p","name":"P","settings":{"width":1920,"height":1080,"fpsNum":30,"fpsDen":1,"colorSpace":"Rec709-SDR"},""" +
            """"mediaLibrary":[{"id":"a1","uri":"content://m/a1","durationFrames":900,"nativeFpsNum":30,"nativeFpsDen":1,"colorSpace":"Rec709-SDR"}],""" +
            """"tracks":[{"id":"v","type":"video","order":0,"clips":[{"id":"c1","assetId":"a1","timelineStartFrame":0,"sourceInFrame":0,"sourceOutFrame":100}]}],""" +
            """"markers":[{"id":"m","frame":40,"kind":"manual"}]}"""
        val marker = TimelineMapper.toTimeline(ProjectJson.decode(old)).markers.single()
        assertNull(marker.anchorClipId)
        assertEquals(0, marker.offsetFrames)
        assertEquals(FrameIndex(40), marker.frame)
    }

    @Test
    fun `an anchor to a missing clip is dropped on load and a stale frame is put back on its clip`() {
        val timeline = TimelineMapper.toTimeline(
            project(
                listOf(
                    MarkerDto("gone", 130, anchorClipId = "nope", offset = 30),
                    MarkerDto("stale", 5, anchorClipId = "c2", offset = 31),
                    MarkerDto("far", 500, anchorClipId = "c1", offset = 900),
                ),
            ),
        )
        val byId = timeline.markers.associateBy { it.id }
        assertNull(byId.getValue("gone").anchorClipId)
        assertEquals(FrameIndex(130), byId.getValue("gone").frame)
        assertEquals(FrameIndex(131), byId.getValue("stale").frame)
        assertEquals("c2", byId.getValue("stale").anchorClipId)
        assertEquals(99, byId.getValue("far").offsetFrames)
        assertEquals(FrameIndex(99), byId.getValue("far").frame)
        assertTrue(timeline.invariantViolations().isEmpty())
    }

    @Test
    fun `the interchange export reads the resolved frame of an anchored marker`() {
        val dto = project(listOf(MarkerDto("a", 130, anchorClipId = "c2", offset = 30)))
        val free = project(listOf(MarkerDto("a", 130)))
        val original = markerStart(Fcpxml.export(dto).xml)
        // A clip inserted in front moves c2 and, because the marker sticks to c2, the marker.
        val edited = edit(dto)
        assertEquals(FrameIndex(160), TimelineMapper.toTimeline(edited).markers.single().frame)
        assertEquals(160L, edited.markers.single().frame)
        val shifted = markerStart(Fcpxml.export(edited).xml)
        // The marker sits 30 frames into c2 in both exports: the time written for it is the same as before the insert...
        val editedFree = edit(free)
        assertEquals(130L, editedFree.markers.single().frame)
        // ...whereas a free marker ends up elsewhere in the picture.
        assertNotEquals(shifted, markerStart(Fcpxml.export(editedFree).xml))
        assertEquals(original, shifted)
    }

    private fun edit(dto: ProjectDto): ProjectDto {
        val timeline = TimelineMapper.toTimeline(dto)
        val inserted = com.qtekfun.ultimatevideoeditor.domain.Clip(
            "n", "a1", FrameIndex(0), FrameIndex(0), FrameIndex(30),
        )
        val result = EditHistory(timeline).execute(EditCommand.InsertBase(inserted, FrameIndex(0)))
        val after = (result as EditResult.Success).value.timeline
        assertTrue(after.invariantViolations().isEmpty())
        assertFalse(after.markers.isEmpty())
        return TimelineMapper.toDto(dto, after, dto.mediaLibrary)
    }

    private fun markerStart(xml: String): String = Regex("<marker start=\"([^\"]+)\"").find(xml)!!.groupValues[1]

    @Test
    fun `adding an unanchored marker keeps working through the command`() {
        val timeline = TimelineMapper.toTimeline(project(emptyList()))
        val after = (AddMarker(Marker("x", FrameIndex(10))).apply(timeline) as EditResult.Success).value
        assertNull(after.markers.single().anchorClipId)
    }
}
