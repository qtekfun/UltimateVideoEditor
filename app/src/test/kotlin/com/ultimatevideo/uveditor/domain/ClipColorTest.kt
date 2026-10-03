package com.ultimatevideo.uveditor.domain

import com.ultimatevideo.uveditor.data.ColorSpaceNames
import com.ultimatevideo.uveditor.data.TimelineMapper
import com.ultimatevideo.uveditor.data.model.ClipDto
import com.ultimatevideo.uveditor.data.model.MediaAssetDto
import com.ultimatevideo.uveditor.data.model.ProjectDto
import com.ultimatevideo.uveditor.data.model.ProjectSettingsDto
import com.ultimatevideo.uveditor.data.model.TrackDto
import com.ultimatevideo.uveditor.ui.editor.conversionNote
import com.ultimatevideo.uveditor.ui.editor.previewRequestsAt
import com.ultimatevideo.uveditor.ui.export.buildExportPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipColorTest {
    private val fps = FrameRate(30, 1)

    private fun asset(id: String, colorSpace: String) =
        MediaAssetDto(id, "content://$id", 600, 30, 1, colorSpace, hasVideo = true, hasAudio = true)

    private val assets = listOf(asset("a1", "Rec709-SDR"), asset("a2", "Rec2020-HLG"), asset("a3", "Rec2020-PQ"))

    /** An SDR, an HLG and a PQ clip side by side on three lanes (display order: top first). */
    private fun mixed() = timeline(
        track("v3", clip("pq", 0, 100, asset = "a3")),
        track("v2", clip("hlg", 0, 100, asset = "a2")),
        track("v1", clip("sdr", 0, 100, asset = "a1")),
    )

    // region domain

    @Test
    fun `an override is set, cleared and undone`() {
        val t = mixed()
        val set = TimelineOps.setColorOverride(t, "sdr", SourceColorSpace.HLG).getOrFail()
        assertEquals(SourceColorSpace.HLG, set.trackOfClip("sdr")!!.clip("sdr")!!.colorOverride)
        val cleared = TimelineOps.setColorOverride(set, "sdr", null).getOrFail()
        assertEquals(t, cleared)

        val history = EditHistory(t).execute(EditCommand.SetColorOverride("sdr", SourceColorSpace.PQ)).getOrFail()
        assertEquals(t, history.undo().timeline)
    }

    @Test
    fun `only video clips with media have a source colour`() {
        val titles = timeline(
            track("t1", clip("title", 0, 50, asset = null).copy(title = TitleContent("Hi")), type = TrackType.TITLE),
            track("a1", clip("m", 0, 50), type = TrackType.AUDIO),
        )
        assertTrue(TimelineOps.setColorOverride(titles, "title", SourceColorSpace.HLG).errorOrFail() is EditError.InvalidClip)
        assertTrue(TimelineOps.setColorOverride(titles, "m", SourceColorSpace.HLG).errorOrFail() is EditError.InvalidClip)
        assertEquals(EditError.ClipNotFound("zz"), TimelineOps.setColorOverride(titles, "zz", SourceColorSpace.HLG).errorOrFail())
        // Clearing is always allowed.
        TimelineOps.setColorOverride(titles, "title", null).getOrFail()
    }

    @Test
    fun `split and trim keep the override on every part`() {
        val t = TimelineOps.setColorOverride(timeline(track("v1", clip("a", 0, 100, asset = "a1"))), "a", SourceColorSpace.HLG).getOrFail()
        val split = TimelineOps.split(t, "v1", f(40), "b").getOrFail()
        assertEquals(listOf(SourceColorSpace.HLG, SourceColorSpace.HLG), split.track("v1")!!.clips.map { it.colorOverride })
        val trimmed = TimelineOps.trim(split, "b", TrimEdge.START, f(50)).getOrFail()
        assertEquals(SourceColorSpace.HLG, trimmed.track("v1")!!.clip("b")!!.colorOverride)
    }

    // endregion

    // region JSON

    @Test
    fun `the override round trips through the project file and old projects have none`() {
        val settings = ProjectSettingsDto(1920, 1080, 30, 1, "Rec709-SDR")
        fun dto(override: String?) = ProjectDto(
            id = "p", name = "P", settings = settings, mediaLibrary = assets,
            tracks = listOf(TrackDto("v1", "video", 0, listOf(ClipDto("c", "a2", 0, 0, 100, colorOverride = override)))),
        )
        for (space in SourceColorSpace.entries) {
            val project = dto(space.id)
            val timeline = TimelineMapper.toTimeline(project)
            assertEquals(space, timeline.track("v1")!!.clip("c")!!.colorOverride)
            assertEquals(project.tracks, TimelineMapper.toDto(project, timeline, assets).tracks)
        }
        assertNull(TimelineMapper.toTimeline(dto(null)).track("v1")!!.clip("c")!!.colorOverride)
        assertNull(TimelineMapper.toTimeline(dto("something-unknown")).track("v1")!!.clip("c")!!.colorOverride)
        assertNull(TimelineMapper.toTimeline(dto("")).track("v1")!!.clip("c")!!.colorOverride)
    }

    // endregion

    // region preview and export parity

    private fun previewOverrides(t: Timeline) =
        previewRequestsAt(t, assets, fps, f(10)) { id -> id.last().digitToInt() }.associate { it.uri to it.sourceOverride }

    private fun exportModes(t: Timeline) =
        buildExportPlan(t, assets, fps)!!.videoClips.associate { it.assetKey to it.colorMode }

    @Test
    fun `without overrides the preview uses the file and the export mode follows the asset`() {
        assertEquals(mapOf("content://a1" to -1, "content://a2" to -1, "content://a3" to -1), previewOverrides(mixed()))
        assertEquals(
            listOf(0, 1, 4).sorted(),
            exportModes(mixed()).values.sorted(),
        )
    }

    @Test
    fun `an override reaches the preview layer and the export spec for every source and override`() {
        for (source in SourceColorSpace.entries) {
            for (override in SourceColorSpace.entries) {
                val id = listOf("a1", "a2", "a3")[source.ordinal]
                val t = TimelineOps.setColorOverride(timeline(track("v1", clip("c", 0, 100, asset = id))), "c", override).getOrFail()
                assertEquals(override.transferIndex, previewOverrides(t).values.single())
                assertEquals(override.nativeModeValue, exportModes(t).values.single())
            }
        }
    }

    // endregion

    // region detection and wording

    @Test
    fun `detection reads the transfer, and falls back to HDR10 static metadata when it is absent`() {
        assertEquals("Rec2020-HLG", ColorSpaceNames.detect(android.media.MediaFormat.COLOR_TRANSFER_HLG, false))
        assertEquals("Rec2020-PQ", ColorSpaceNames.detect(android.media.MediaFormat.COLOR_TRANSFER_ST2084, false))
        assertEquals("Rec2020-PQ", ColorSpaceNames.detect(null, true))
        assertEquals("Rec709-SDR", ColorSpaceNames.detect(null, false))
        // An explicit SDR transfer wins over stray static metadata.
        assertEquals("Rec709-SDR", ColorSpaceNames.detect(android.media.MediaFormat.COLOR_TRANSFER_SDR_VIDEO, true))
    }

    @Test
    fun `every source and project combination has a conversion note`() {
        val notes = buildSet {
            for (project in ProjectColorSpace.entries) for (source in SourceColorSpace.entries) add(conversionNote(source, project))
        }
        assertEquals(6, notes.size)
        assertTrue(conversionNote(SourceColorSpace.HLG, ProjectColorSpace.REC709_SDR).contains("tone-mapped"))
        assertTrue(conversionNote(SourceColorSpace.SDR, ProjectColorSpace.REC2020_HLG).contains("203"))
    }

    // endregion
}
