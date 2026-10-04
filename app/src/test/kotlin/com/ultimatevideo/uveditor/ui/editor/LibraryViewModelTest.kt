package com.ultimatevideo.uveditor.ui.editor

import com.ultimatevideo.uveditor.data.MediaImportException
import com.ultimatevideo.uveditor.data.MediaImporter
import com.ultimatevideo.uveditor.data.ProbedMedia
import com.ultimatevideo.uveditor.data.ProjectError
import com.ultimatevideo.uveditor.data.ProjectStore
import com.ultimatevideo.uveditor.data.interchange.BundleChoice
import com.ultimatevideo.uveditor.data.interchange.BundlePreview
import com.ultimatevideo.uveditor.data.interchange.BundleWriteResult
import com.ultimatevideo.uveditor.data.interchange.InterchangeExporter
import com.ultimatevideo.uveditor.data.interchange.ResourceInfo
import com.ultimatevideo.uveditor.data.interchange.ResourceKind
import com.ultimatevideo.uveditor.data.model.ClipDto
import com.ultimatevideo.uveditor.data.model.MediaAssetDto
import com.ultimatevideo.uveditor.data.model.ProjectDto
import com.ultimatevideo.uveditor.data.model.ProjectSettingsDto
import com.ultimatevideo.uveditor.data.model.TrackDto
import com.ultimatevideo.uveditor.domain.MarkerColor
import com.ultimatevideo.uveditor.engine.timeline.HitKind
import com.ultimatevideo.uveditor.engine.timeline.TimelineHit
import com.ultimatevideo.uveditor.ui.library.LibraryFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeStore(var project: ProjectDto?) : ProjectStore {
        override suspend fun load(id: String): ProjectDto = project ?: throw ProjectError.NotFound(id)

        override suspend fun save(project: ProjectDto) {
            this.project = project
        }
    }

    private class NoImporter : MediaImporter {
        override suspend fun import(uri: String): ProbedMedia = throw MediaImportException("not used")
    }

    private class FakeInterchange : InterchangeExporter {
        val bundles = mutableListOf<Triple<String, String, Boolean>>()
        val choices = mutableListOf<BundleChoice>()
        val documents = LinkedHashMap<String, ByteArray>()
        var failWith: IOException? = null
        var failPreview: IOException? = null
        var preview = BundlePreview(
            mediaCount = 2,
            mediaBytes = 3_000_000,
            luts = listOf(ResourceInfo(ResourceKind.LUT, "5", "Warm", 2_000, true)),
            fonts = listOf(ResourceInfo(ResourceKind.FONT, "ab", "Big Font", 40_000, true)),
        )

        override suspend fun exportBundle(projectId: String, uri: String, includeMedia: Boolean): BundleWriteResult {
            failWith?.let { throw it }
            bundles += Triple(projectId, uri, includeMedia)
            return BundleWriteResult(if (includeMedia) 2 else 0, if (includeMedia) listOf("gone.mp4") else emptyList())
        }

        override suspend fun exportBundle(projectId: String, uri: String, choice: BundleChoice): BundleWriteResult {
            choices += choice
            return exportBundle(projectId, uri, choice.includeMedia).copy(
                lutsIncluded = if (choice.includeLuts) 1 else 0,
                fontsIncluded = if (choice.includeFonts) 1 else 0,
            )
        }

        override suspend fun bundlePreview(projectId: String): BundlePreview {
            failPreview?.let { throw it }
            return preview
        }

        override suspend fun writeDocument(uri: String, bytes: ByteArray) {
            failWith?.let { throw it }
            documents[uri] = bytes
        }
    }

    private val a1 = MediaAssetDto("a1", "content://m/a1", 900, 30, 1, "Rec709-SDR", displayName = "main.mp4")
    private val a2 = MediaAssetDto("a2", "content://m/a2", 900, 30, 1, "Rec709-SDR", displayName = "broll.mp4")
    private val a3 = MediaAssetDto("a3", "content://m/a3", 900, 30, 1, "Rec709-SDR", displayName = "spare.mp4")

    private fun project(clips: Boolean = true) = ProjectDto(
        id = "p1",
        name = "Test",
        settings = ProjectSettingsDto(1920, 1080, 30, 1, "Rec709-SDR"),
        mediaLibrary = listOf(a1, a2, a3),
        tracks = listOf(
            TrackDto(
                "v1", "video", 0,
                if (clips) listOf(ClipDto("c1", "a1", 0, 0, 300), ClipDto("c2", "a1", 300, 0, 150), ClipDto("c3", "a2", 450, 0, 100)) else emptyList(),
            ),
        ),
    )

    private class Harness(val vm: EditorViewModel, val store: FakeStore, val interchange: FakeInterchange, val effects: MutableList<EditorEffect>) {
        val state get() = vm.state.value

        fun select(clipId: String) {
            val ordered = state.visibleTimeline.tracks.flatMap { it.clips }.map { it.id }
            val key = vm.snapshotOf(state).clips[ordered.indexOf(clipId)].clipKey
            vm.onIntent(EditorIntent.TapTimeline(TimelineHit(HitKind.CLIP, 0, key, 0)))
        }

        fun at(frame: Long) = vm.onIntent(EditorIntent.SetPlayhead(frame))

        fun messages(): List<String> = effects.filterIsInstance<EditorEffect.ShowMessage>().map { it.text }
    }

    private fun TestScope.harness(clips: Boolean = true): Harness {
        var counter = 0
        val store = FakeStore(project(clips))
        val interchange = FakeInterchange()
        val vm = EditorViewModel("p1", store, NoImporter(), idGenerator = { "n${counter++}" }, interchange = interchange)
        val effects = mutableListOf<EditorEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { effects += it } }
        advanceUntilIdle()
        return Harness(vm, store, interchange, effects)
    }

    @Test
    fun `the library opens on the file of the selected clip`() = runTest(dispatcher) {
        val h = harness()
        h.select("c3")
        h.vm.onIntent(LibraryIntent.RevealSelectedInLibrary)
        assertTrue(h.state.library.open)
        assertEquals("a2", h.state.library.highlightAssetId)
        h.vm.onIntent(LibraryIntent.Close)
        assertEquals(false, h.state.library.open)
        assertNull(h.state.library.highlightAssetId)
    }

    @Test
    fun `with no clip selected it just opens, and a title says there is no file`() = runTest(dispatcher) {
        val h = harness()
        h.vm.onIntent(LibraryIntent.RevealSelectedInLibrary)
        assertTrue(h.state.library.open)
        assertNull(h.state.library.highlightAssetId)
    }

    @Test
    fun `search, filter and tag are kept in the state`() = runTest(dispatcher) {
        val h = harness()
        h.vm.onIntent(LibraryIntent.Open())
        h.vm.onIntent(LibraryIntent.QueryChanged("broll"))
        h.vm.onIntent(LibraryIntent.FilterSelected(LibraryFilter.UNUSED))
        h.vm.onIntent(LibraryIntent.TagSelected("night"))
        assertEquals("broll", h.state.library.query.text)
        assertEquals(LibraryFilter.UNUSED, h.state.library.query.filter)
        assertEquals("night", h.state.library.query.tag)
    }

    @Test
    fun `tags and a note are saved with the project`() = runTest(dispatcher) {
        val h = harness()
        h.vm.onIntent(LibraryIntent.EditAsset("a3"))
        assertEquals("spare.mp4", h.state.library.editing?.name)
        h.vm.onIntent(LibraryIntent.TagsChanged("b-roll,  Night , b-roll"))
        h.vm.onIntent(LibraryIntent.NoteChanged("  shaky but useful "))
        h.vm.onIntent(LibraryIntent.ConfirmAssetEdit)
        assertNull(h.state.library.editing)
        val asset = h.state.assets.first { it.id == "a3" }
        assertEquals(listOf("b-roll", "Night"), asset.tags)
        assertEquals("shaky but useful", asset.note)

        advanceUntilIdle()
        assertEquals(listOf("b-roll", "Night"), h.store.project!!.mediaLibrary.first { it.id == "a3" }.tags)
        // It is a library edit, not a timeline edit: Undo has nothing to do with it.
        assertEquals(false, h.state.canUndo)
    }

    @Test
    fun `removing unused files asks first and leaves the files used on the timeline`() = runTest(dispatcher) {
        val h = harness()
        h.vm.onIntent(LibraryIntent.AskDeleteUnused)
        assertEquals(1, h.state.library.confirmDeleteUnused)
        h.vm.onIntent(LibraryIntent.DismissDeleteUnused)
        assertEquals(3, h.state.assets.size)

        h.vm.onIntent(LibraryIntent.AskDeleteUnused)
        h.vm.onIntent(LibraryIntent.ConfirmDeleteUnused)
        assertEquals(listOf("a1", "a2"), h.state.assets.map { it.id })
        assertNull(h.state.library.confirmDeleteUnused)
        assertTrue(h.messages().last().startsWith("Removed 1 unused file"))
        advanceUntilIdle()
        assertEquals(listOf("a1", "a2"), h.store.project!!.mediaLibrary.map { it.id })
    }

    @Test
    fun `a file that undo could bring back is not removed`() = runTest(dispatcher) {
        val h = harness()
        h.select("c3")
        h.vm.onIntent(EditorIntent.RippleDeleteSelected)
        // a2 (broll.mp4) is now unused on the timeline, but undoing the delete would need it again.
        h.vm.onIntent(LibraryIntent.AskDeleteUnused)
        assertEquals(1, h.state.library.confirmDeleteUnused) // only the spare one
        h.vm.onIntent(LibraryIntent.ConfirmDeleteUnused)
        assertEquals(listOf("a1", "a2"), h.state.assets.map { it.id })
        h.vm.onIntent(EditorIntent.Undo)
        assertTrue(h.state.timeline.tracks.flatMap { it.clips }.any { it.id == "c3" })
    }

    @Test
    fun `nothing to remove says so`() = runTest(dispatcher) {
        val h = harness()
        h.vm.onIntent(LibraryIntent.AskDeleteUnused)
        h.vm.onIntent(LibraryIntent.ConfirmDeleteUnused)
        h.vm.onIntent(LibraryIntent.AskDeleteUnused)
        assertNull(h.state.library.confirmDeleteUnused)
        assertEquals("Every file in the library is used", h.messages().last())
    }

    @Test
    fun `find in timeline steps through the uses and wraps around`() = runTest(dispatcher) {
        val h = harness()
        h.at(0)
        h.vm.onIntent(LibraryIntent.Open())
        h.vm.onIntent(LibraryIntent.FindInTimeline("a1"))
        assertEquals("c2", h.state.selectedClipId)
        assertEquals(300L, h.state.playhead.value)
        assertEquals(false, h.state.library.open)
        assertTrue(h.messages().last().startsWith("Use 2 of 2"))

        h.vm.onIntent(LibraryIntent.FindInTimeline("a1"))
        assertEquals("c1", h.state.selectedClipId)
        assertEquals(0L, h.state.playhead.value)
    }

    @Test
    fun `find in timeline for an unused file says so and changes nothing`() = runTest(dispatcher) {
        val h = harness()
        h.vm.onIntent(LibraryIntent.FindInTimeline("a3"))
        assertEquals("That file is not used on the timeline", h.messages().last())
        assertNull(h.state.selectedClipId)
    }

    @Test
    fun `an EDL export asks for a file and writes the track`() = runTest(dispatcher) {
        val h = harness()
        h.vm.onIntent(LibraryIntent.RequestExport(InterchangeKind.EDL))
        val picker = h.effects.filterIsInstance<EditorEffect.LaunchInterchangePicker>().single()
        assertEquals(InterchangeKind.EDL, picker.kind)
        assertEquals("Test.edl", picker.suggestedFileName)
        assertEquals(INTERCHANGE_MIME, picker.mime)

        h.vm.onIntent(LibraryIntent.ExportTo(InterchangeKind.EDL, "doc://out"))
        assertEquals("Writing EDL…", h.state.library.busy)
        advanceUntilIdle()
        assertNull(h.state.library.busy)
        val text = h.interchange.documents.getValue("doc://out").toString(Charsets.UTF_8)
        assertTrue(text, text.startsWith("TITLE: Test - V1"))
        assertTrue(h.messages().last().startsWith("EDL written (1 track)"))
    }

    @Test
    fun `an FCPXML export writes the document`() = runTest(dispatcher) {
        val h = harness()
        h.vm.onIntent(LibraryIntent.RequestExport(InterchangeKind.FCPXML))
        assertEquals(INTERCHANGE_MIME, h.effects.filterIsInstance<EditorEffect.LaunchInterchangePicker>().single().mime)
        h.vm.onIntent(LibraryIntent.ExportTo(InterchangeKind.FCPXML, "doc://x"))
        advanceUntilIdle()
        val xml = h.interchange.documents.getValue("doc://x").toString(Charsets.UTF_8)
        assertTrue(xml, xml.contains("<fcpxml version=\"1.9\">"))
        assertTrue(h.messages().last().startsWith("FCPXML written"))
    }

    @Test
    fun `a bundle export saves the project first and reports skipped media`() = runTest(dispatcher) {
        val h = harness()
        h.vm.onIntent(LibraryIntent.EditAsset("a3"))
        h.vm.onIntent(LibraryIntent.TagsChanged("x"))
        h.vm.onIntent(LibraryIntent.ConfirmAssetEdit)
        h.vm.onIntent(LibraryIntent.ExportTo(InterchangeKind.BUNDLE_WITH_MEDIA, "doc://bundle"))
        advanceUntilIdle()
        assertEquals(listOf(Triple("p1", "doc://bundle", true)), h.interchange.bundles)
        // The tag typed a moment ago is on disk before the bundle was made.
        assertEquals(listOf("x"), h.store.project!!.mediaLibrary.first { it.id == "a3" }.tags)
        assertTrue(h.messages().last().contains("Bundle written with 2 media files"))
        assertTrue(h.messages().last().contains("gone.mp4"))
    }

    @Test
    fun `asking for a bundle opens the dialog, saves the project and shows what could go in`() = runTest(dispatcher) {
        val h = harness()
        h.vm.onIntent(LibraryIntent.EditAsset("a3"))
        h.vm.onIntent(LibraryIntent.TagsChanged("y"))
        h.vm.onIntent(LibraryIntent.ConfirmAssetEdit)
        h.vm.onIntent(LibraryIntent.RequestExport(InterchangeKind.BUNDLE))
        // The dialog is there at once, still measuring, and no picker has opened yet.
        assertEquals(null, h.state.library.bundleDraft!!.preview)
        assertTrue(h.effects.filterIsInstance<EditorEffect.LaunchInterchangePicker>().isEmpty())
        advanceUntilIdle()
        val draft = h.state.library.bundleDraft!!
        assertEquals(h.interchange.preview, draft.preview)
        assertEquals(BundleChoice(includeMedia = false, includeLuts = true, includeFonts = false), draft.choice)
        assertEquals(2_000L, draft.estimatedBytes)
        // The bundle is made from the file on disk, so what was typed a moment ago is saved before measuring.
        assertEquals(listOf("y"), h.store.project!!.mediaLibrary.first { it.id == "a3" }.tags)
    }

    @Test
    fun `the media switch starts on for the with-media entry`() = runTest(dispatcher) {
        val h = harness()
        h.vm.onIntent(LibraryIntent.RequestExport(InterchangeKind.BUNDLE_WITH_MEDIA))
        advanceUntilIdle()
        assertTrue(h.state.library.bundleDraft!!.choice.includeMedia)
    }

    @Test
    fun `the ticked choice reaches the export and the message names the LUTs and fonts`() = runTest(dispatcher) {
        val h = harness()
        h.vm.onIntent(LibraryIntent.RequestExport(InterchangeKind.BUNDLE))
        advanceUntilIdle()
        val choice = BundleChoice(includeMedia = true, includeLuts = true, includeFonts = true)
        h.vm.onIntent(LibraryIntent.BundleChoiceChanged(choice))
        assertEquals(3_000_000L + 2_000L + 40_000L, h.state.library.bundleDraft!!.estimatedBytes)
        h.vm.onIntent(LibraryIntent.ConfirmBundleExport)
        advanceUntilIdle()
        assertNull(h.state.library.bundleDraft)
        val picker = h.effects.filterIsInstance<EditorEffect.LaunchInterchangePicker>().single()
        assertEquals(InterchangeKind.BUNDLE_WITH_MEDIA, picker.kind)
        assertEquals("Test.uvbundle", picker.suggestedFileName)
        h.vm.onIntent(LibraryIntent.ExportTo(picker.kind, "doc://b"))
        advanceUntilIdle()
        assertEquals(listOf(choice), h.interchange.choices)
        val message = h.messages().last()
        assertTrue(message, message.startsWith("Bundle written with 2 media files and 1 LUT and 1 font"))
    }

    @Test
    fun `cancelling the dialog and a project that cannot be measured`() = runTest(dispatcher) {
        val h = harness()
        h.vm.onIntent(LibraryIntent.RequestExport(InterchangeKind.BUNDLE))
        advanceUntilIdle()
        h.vm.onIntent(LibraryIntent.DismissBundleExport)
        assertNull(h.state.library.bundleDraft)
        assertTrue(h.effects.filterIsInstance<EditorEffect.LaunchInterchangePicker>().isEmpty())

        h.interchange.failPreview = IOException("unreadable project")
        h.vm.onIntent(LibraryIntent.RequestExport(InterchangeKind.BUNDLE))
        advanceUntilIdle()
        val draft = h.state.library.bundleDraft!!
        assertEquals("unreadable project", draft.failed)
        assertEquals(false, draft.canExport)
        // Confirming a dialog that cannot export does nothing.
        h.vm.onIntent(LibraryIntent.ConfirmBundleExport)
        advanceUntilIdle()
        assertTrue(h.effects.filterIsInstance<EditorEffect.LaunchInterchangePicker>().isEmpty())
    }

    @Test
    fun `closing the dialog while the project is measured leaves it closed`() = runTest(dispatcher) {
        val h = harness()
        h.vm.onIntent(LibraryIntent.RequestExport(InterchangeKind.BUNDLE))
        h.vm.onIntent(LibraryIntent.DismissBundleExport)
        advanceUntilIdle()
        assertNull(h.state.library.bundleDraft)
    }

    @Test
    fun `a failed export is reported and the busy mark goes away`() = runTest(dispatcher) {
        val h = harness()
        h.interchange.failWith = IOException("disk full")
        h.vm.onIntent(LibraryIntent.ExportTo(InterchangeKind.EDL, "doc://x"))
        advanceUntilIdle()
        assertEquals("Export failed: disk full", h.messages().last())
        assertNull(h.state.library.busy)
    }

    @Test
    fun `an EDL of a project without clips is refused before the picker opens`() = runTest(dispatcher) {
        val h = harness(clips = false)
        h.vm.onIntent(LibraryIntent.RequestExport(InterchangeKind.EDL))
        assertTrue(h.effects.filterIsInstance<EditorEffect.LaunchInterchangePicker>().isEmpty())
        assertEquals("There are no video or audio clips to put in an EDL", h.messages().last())
    }

    @Test
    fun `an EDL of several tracks is a zip with its own type`() = runTest(dispatcher) {
        val h = harness()
        val project = project().copy(
            tracks = project().tracks + TrackDto("a1", "audio", 1, listOf(ClipDto("m1", "a1", 0, 0, 100))),
        )
        h.store.project = project
        // Reload through a fresh view model so the second track is part of the timeline.
        var counter = 0
        val vm = EditorViewModel("p1", FakeStore(project), NoImporter(), idGenerator = { "q${counter++}" }, interchange = h.interchange)
        val effects = mutableListOf<EditorEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { effects += it } }
        advanceUntilIdle()
        vm.onIntent(LibraryIntent.RequestExport(InterchangeKind.EDL))
        val picker = effects.filterIsInstance<EditorEffect.LaunchInterchangePicker>().single()
        assertEquals("Test.zip", picker.suggestedFileName)
        assertEquals(ZIP_MIME, picker.mime)
    }
}
