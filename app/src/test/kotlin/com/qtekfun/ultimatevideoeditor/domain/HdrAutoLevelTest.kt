package com.qtekfun.ultimatevideoeditor.domain

import com.qtekfun.ultimatevideoeditor.data.TimelineMapper
import com.qtekfun.ultimatevideoeditor.data.model.ClipDto
import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto
import com.qtekfun.ultimatevideoeditor.data.model.ProjectDto
import com.qtekfun.ultimatevideoeditor.data.model.ProjectSettingsDto
import com.qtekfun.ultimatevideoeditor.data.model.TrackDto
import com.qtekfun.ultimatevideoeditor.engine.still.StillRef
import com.qtekfun.ultimatevideoeditor.ui.editor.previewRequestsOnCanvas
import com.qtekfun.ultimatevideoeditor.ui.export.buildExportPlan
import com.qtekfun.ultimatevideoeditor.ui.export.withHdrPictures
import com.qtekfun.ultimatevideoeditor.proxy.ResolvedSource
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The automatic HDR level as the project file, the edit history, the preview and the exporter see it. */
class HdrAutoLevelTest {
    private val fps = FrameRate(30, 1)

    private fun asset(id: String, colorSpace: String) = MediaAssetDto(id, "content://$id", 600, 30, 1, colorSpace, hasVideo = true, hasAudio = true)
    private val assets = listOf(asset("sdr", "Rec709-SDR"), asset("hlg", "Rec2020-HLG"), asset("pq", "Rec2020-PQ"))

    private fun levels(brightness: HdrBrightness = HdrBrightness.COMFORTABLE, hlg: Double? = 2.0, pq: Double? = 2.0, hdrProject: Boolean = true) =
        HdrLevels(
            brightness,
            buildMap {
                hlg?.let { put(HdrLevels.keyOf("content://hlg", 600, SourceColorSpace.HLG), it) }
                pq?.let { put(HdrLevels.keyOf("content://pq", 600, SourceColorSpace.PQ), it) }
            },
            hdrProject,
        )

    private fun mixed() = timeline(
        track("v3", clip("pq", 0, 100, asset = "pq")),
        track("v2", clip("hlg", 0, 100, asset = "hlg")),
        track("v1", clip("sdr", 0, 100, asset = "sdr")),
    )

    // region the project file

    private val withDefaults = Json { encodeDefaults = true }

    private val settingsJson = """{"width":1920,"height":1080,"fpsNum":30,"fpsDen":1,"colorSpace":"Rec2020-HLG"}"""

    @Test
    fun `a project saved before the setting existed reads as comfortable`() {
        val settings = Json.decodeFromString(ProjectSettingsDto.serializer(), settingsJson)
        assertEquals(HdrBrightness.COMFORTABLE, HdrBrightness.fromId(settings.hdrBrightness))
    }

    @Test
    fun `the setting is stored and an unknown value reads as comfortable`() {
        val original = Json.decodeFromString(ProjectSettingsDto.serializer(), settingsJson.replace("}", ""","hdrBrightness":"original"}"""))
        assertEquals(HdrBrightness.ORIGINAL, HdrBrightness.fromId(original.hdrBrightness))
        val future = Json.decodeFromString(ProjectSettingsDto.serializer(), settingsJson.replace("}", ""","hdrBrightness":"punchy"}"""))
        assertEquals(HdrBrightness.COMFORTABLE, HdrBrightness.fromId(future.hdrBrightness))
        val written = withDefaults.encodeToString(ProjectSettingsDto.serializer(), original)
        assertTrue(written.contains("\"hdrBrightness\":\"original\""))
    }

    @Test
    fun `a clip's setting round trips through the project file and old clips follow the project`() {
        val settings = ProjectSettingsDto(1920, 1080, 30, 1, "Rec2020-HLG")
        fun dto(setting: Boolean?) = ProjectDto(
            id = "p", name = "P", settings = settings, mediaLibrary = assets,
            tracks = listOf(TrackDto("v1", "video", 0, listOf(ClipDto("c", "hlg", 0, 0, 100, hdrAutoLevel = setting)))),
        )
        for (setting in listOf(true, false, null)) {
            val project = dto(setting)
            val timeline = TimelineMapper.toTimeline(project)
            assertEquals(setting, timeline.track("v1")!!.clip("c")!!.hdrAutoLevel)
            assertEquals(project.tracks, TimelineMapper.toDto(project, timeline, assets).tracks)
        }
        val old = Json.decodeFromString(ClipDto.serializer(), """{"id":"c","assetId":"hlg","timelineStartFrame":0,"sourceInFrame":0,"sourceOutFrame":100}""")
        assertNull(old.hdrAutoLevel)
    }

    // endregion

    // region editing

    @Test
    fun `the clip setting is set, cleared and undone in one step`() {
        val t = mixed()
        val off = TimelineOps.setHdrAutoLevel(t, "hlg", false).getOrFail()
        assertEquals(false, off.trackOfClip("hlg")!!.clip("hlg")!!.hdrAutoLevel)
        assertEquals(t, TimelineOps.setHdrAutoLevel(off, "hlg", null).getOrFail())

        val history = EditHistory(t).execute(EditCommand.SetHdrAutoLevel("hlg", false)).getOrFail()
        assertEquals(false, history.timeline.trackOfClip("hlg")!!.clip("hlg")!!.hdrAutoLevel)
        assertEquals(t, history.undo().timeline)
        assertEquals(false, history.undo().redo().timeline.trackOfClip("hlg")!!.clip("hlg")!!.hdrAutoLevel)
    }

    @Test
    fun `only a video clip with media has an HDR level`() {
        val t = timeline(
            track("t1", clip("title", 0, 50, asset = null).copy(title = TitleContent("Hi")), type = TrackType.TITLE),
            track("a1", clip("m", 0, 50), type = TrackType.AUDIO),
        )
        assertTrue(TimelineOps.setHdrAutoLevel(t, "title", true).errorOrFail() is EditError.InvalidClip)
        assertTrue(TimelineOps.setHdrAutoLevel(t, "m", false).errorOrFail() is EditError.InvalidClip)
        assertEquals(EditError.ClipNotFound("zz"), TimelineOps.setHdrAutoLevel(t, "zz", true).errorOrFail())
        TimelineOps.setHdrAutoLevel(t, "title", null).getOrFail() // following the project is always allowed
    }

    @Test
    fun `split and trim keep the setting on every part`() {
        val t = TimelineOps.setHdrAutoLevel(timeline(track("v1", clip("a", 0, 100, asset = "hlg"))), "a", false).getOrFail()
        val split = TimelineOps.split(t, "v1", f(40), "b").getOrFail()
        assertEquals(listOf(false, false), split.track("v1")!!.clips.map { it.hdrAutoLevel })
        assertEquals(false, TimelineOps.trim(split, "b", TrimEdge.START, f(50)).getOrFail().track("v1")!!.clip("b")!!.hdrAutoLevel)
    }

    @Test
    fun `the render plan carries the clip setting`() {
        val t = TimelineOps.setHdrAutoLevel(mixed(), "hlg", false).getOrFail()
        assertEquals(false, t.renderClips().first { it.assetId == "hlg" }.hdrAutoLevel)
        assertNull(t.renderClips().first { it.assetId == "pq" }.hdrAutoLevel)
    }

    // endregion

    // region preview and export agree

    private fun previewLevels(t: Timeline, hdr: HdrLevels): Map<String, Float> =
        previewRequestsOnCanvas(t, assets, fps, f(10), 1920, 1080, sourceOf = { ResolvedSource(it.uri) }, hdr = hdr) { id -> id.length }
            .associate { it.uri to it.level }

    private fun exportLevels(t: Timeline, hdr: HdrLevels): Map<String, Double> {
        val plan = buildExportPlan(t, assets, fps, hdr = hdr)!!
        val keyOf = plan.assetKeys.entries.associate { it.value to it.key }
        return plan.videoClips.associate { keyOf.getValue(it.assetKey) to it.level }
    }

    @Test
    fun `an HLG and a PQ clip are dimmed and an SDR clip never is`() {
        val preview = previewLevels(mixed(), levels())
        assertEquals(1f, preview.getValue("content://sdr"), 0f)
        assertEquals(0.5f, preview.getValue("content://hlg"), 1e-6f)
        assertEquals(0.5f, preview.getValue("content://pq"), 1e-6f)
        val export = exportLevels(mixed(), levels())
        assertEquals(1.0, export.getValue("sdr"), 0.0)
        assertEquals(0.5, export.getValue("hlg"), 1e-12)
        assertEquals(0.5, export.getValue("pq"), 1e-12)
    }

    @Test
    fun `the preview and the export give every clip the same gain`() {
        for (brightness in HdrBrightness.entries) {
            for (setting in listOf(true, false, null)) {
                val t = TimelineOps.setHdrAutoLevel(mixed(), "hlg", setting).getOrFail()
                val l = levels(brightness)
                val preview = previewLevels(t, l).mapKeys { it.key.removePrefix("content://") }
                val export = exportLevels(t, l)
                assertEquals("$brightness/$setting", export.mapValues { it.value.toFloat() }, preview)
            }
        }
    }

    @Test
    fun `a clip waits for its analysis shown as recorded, and the gain appears when the level does`() {
        val before = previewLevels(mixed(), levels(hlg = null, pq = null))
        assertTrue(before.values.all { it == 1f })
        assertEquals(0.5f, previewLevels(mixed(), levels(pq = null)).getValue("content://hlg"), 1e-6f)
    }

    @Test
    fun `the original setting leaves every clip as recorded unless a clip asks for the level`() {
        assertTrue(previewLevels(mixed(), levels(HdrBrightness.ORIGINAL)).values.all { it == 1f })
        val forced = TimelineOps.setHdrAutoLevel(mixed(), "hlg", true).getOrFail()
        assertEquals(0.5f, previewLevels(forced, levels(HdrBrightness.ORIGINAL)).getValue("content://hlg"), 1e-6f)
    }

    @Test
    fun `a project without any HDR clip is planned exactly as before`() {
        // Parity: with no HDR footage the new inputs change nothing, whatever the setting and the levels.
        val sdrOnly = timeline(track("v1", clip("a", 0, 100, asset = "sdr"), clip("b", 100, 100, asset = "sdr")))
        val plain = buildExportPlan(sdrOnly, assets, fps)!!
        for (l in listOf(levels(), levels(HdrBrightness.ORIGINAL), HdrLevels.NONE)) {
            val planned = buildExportPlan(sdrOnly, assets, fps, hdr = l)!!
            assertEquals(plain.videoClips, planned.videoClips)
        }
        assertTrue(previewLevels(sdrOnly, levels()).values.all { it == 1f })
    }

    @Test
    fun `an SDR project dims its HLG clips too, before the tone map`() {
        // The level does not depend on the project's colour space: it is applied to the source, whatever the target.
        val sdrProject = levels(hdrProject = false)
        assertEquals(0.5f, previewLevels(mixed(), sdrProject).getValue("content://hlg"), 1e-6f)
    }

    @Test
    fun `a colour override reads the file as another space and takes that level`() {
        val t = TimelineOps.setColorOverride(timeline(track("v1", clip("c", 0, 100, asset = "sdr"))), "c", SourceColorSpace.HLG).getOrFail()
        val l = HdrLevels(levels = mapOf(HdrLevels.keyOf("content://sdr", 600, SourceColorSpace.HLG) to 2.0))
        assertEquals(0.5f, previewLevels(t, l).getValue("content://sdr"), 1e-6f)
        assertEquals(0.5, exportLevels(t, l).getValue("sdr"), 1e-12)
    }

    // endregion

    // region photos

    @Test
    fun `photos in an HDR project are rasterised as HDR and in an SDR project as before`() {
        val photo = asset("photo", "Rec709-SDR").copy(hasVideo = false, isImage = true)
        val t = timeline(track("v1", clip("p", 0, 60, asset = "photo").copy(still = StillKind.PHOTO)))
        fun stills(hdr: HdrLevels) = buildExportPlan(t, listOf(photo), fps, hdr = hdr)!!.stills.values.toList()
        assertEquals(listOf(StillRef(StillKind.PHOTO, "content://photo", hdr = true)), stills(HdrLevels(hdrProject = true)))
        assertEquals(listOf(StillRef(StillKind.PHOTO, "content://photo")), stills(HdrLevels(hdrProject = false)))
        assertEquals(listOf(StillRef(StillKind.PHOTO, "content://photo")), stills(HdrLevels.NONE))
    }

    @Test
    fun `a sticker is never an HDR picture`() {
        val t = timeline(track("v1", clip("s", 0, 60, asset = "heart").copy(still = StillKind.STICKER)))
        val ref = buildExportPlan(t, assets, fps, hdr = HdrLevels(hdrProject = true))!!.stills.values.single()
        assertFalse(ref.hdr)
    }

    @Test
    fun `only the photos that came out HLG are marked as HLG sources`() {
        val photo = asset("photo", "Rec709-SDR").copy(hasVideo = false, isImage = true)
        val t = timeline(
            track("v2", clip("p2", 0, 60, asset = "photo").copy(still = StillKind.PHOTO)),
            track("v1", clip("p1", 0, 60, asset = "photo2").copy(still = StillKind.PHOTO)),
        )
        val library = listOf(photo, photo.copy(id = "photo2", uri = "content://photo2"))
        val plan = buildExportPlan(t, library, fps, hdr = HdrLevels(hdrProject = true))!!
        val marked = plan.videoClips.withHdrPictures(plan.stills, setOf(StillKind.PHOTO to "content://photo"))
        val modes = marked.associate { plan.stills.getValue(it.titleKey).id to it.colorMode }
        assertEquals(SourceColorSpace.HLG.nativeModeValue, modes.getValue("content://photo"))
        assertEquals(SourceColorSpace.SDR.nativeModeValue, modes.getValue("content://photo2"))
        assertEquals(plan.videoClips, plan.videoClips.withHdrPictures(plan.stills, emptySet()))
    }

    // endregion
}
