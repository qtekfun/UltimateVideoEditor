package com.qtekfun.ultimatevideoeditor.ui.editor

import com.qtekfun.ultimatevideoeditor.data.MediaCaches
import com.qtekfun.ultimatevideoeditor.data.MediaImportException
import com.qtekfun.ultimatevideoeditor.data.MediaImporter
import com.qtekfun.ultimatevideoeditor.data.MissingMedia
import com.qtekfun.ultimatevideoeditor.data.ProbedMedia
import com.qtekfun.ultimatevideoeditor.data.ProjectStore
import com.qtekfun.ultimatevideoeditor.data.model.ClipDto
import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto
import com.qtekfun.ultimatevideoeditor.data.model.ProjectDto
import com.qtekfun.ultimatevideoeditor.data.model.ProjectSettingsDto
import com.qtekfun.ultimatevideoeditor.data.model.TrackDto
import com.qtekfun.ultimatevideoeditor.data.relink.FolderListing
import com.qtekfun.ultimatevideoeditor.data.relink.FolderScanException
import com.qtekfun.ultimatevideoeditor.data.relink.FolderScanner
import com.qtekfun.ultimatevideoeditor.data.relink.ScanLimits
import com.qtekfun.ultimatevideoeditor.data.relink.ScanProgress
import com.qtekfun.ultimatevideoeditor.domain.relink.FolderFile
import com.qtekfun.ultimatevideoeditor.domain.relink.RelinkKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** "Relink by scanning a folder" (SPECS 5.40) through the editor view model, with a fake folder instead of the Storage Access Framework. */
@OptIn(ExperimentalCoroutinesApi::class)
class FolderRelinkViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeStore(var project: ProjectDto) : ProjectStore {
        val saved = mutableListOf<ProjectDto>()

        override suspend fun load(id: String): ProjectDto = project

        override suspend fun save(project: ProjectDto) {
            saved += project
            this.project = project
        }
    }

    /** Opens only the files in [media]; every other address is gone. */
    private class FakeImporter(val media: MutableMap<String, ProbedMedia>) : MediaImporter {
        val opened = mutableListOf<String>()

        override suspend fun import(uri: String): ProbedMedia {
            opened += uri
            return media[uri] ?: throw MediaImportException("cannot open $uri")
        }
    }

    private class RecordingCaches : MediaCaches {
        val invalidated = mutableListOf<String>()

        override fun invalidate(assetId: String) {
            invalidated += assetId
        }
    }

    private class FakeScanner(var files: List<FolderFile> = emptyList()) : FolderScanner {
        var retained = true
        var fail: FolderScanException? = null
        var gate: CompletableDeferred<Unit>? = null
        val scanned = mutableListOf<String>()

        override fun retainAccess(treeUri: String): Boolean = retained

        override suspend fun scan(treeUri: String, limits: ScanLimits, onProgress: (ScanProgress) -> Unit): FolderListing {
            scanned += treeUri
            fail?.let { throw it }
            onProgress(ScanProgress(files.size, 2))
            gate?.await()
            return FolderListing(files, folders = 2, truncated = false)
        }
    }

    private val settings = ProjectSettingsDto(1920, 1080, 30, 1, "Rec709-SDR")

    private fun asset(id: String, name: String, oldFolder: String = "Movies/trip") = MediaAssetDto(
        id,
        "content://com.android.externalstorage.documents/document/primary%3A" + (oldFolder + "/" + name).replace("/", "%2F"),
        durationFrames = 300,
        nativeFpsNum = 30,
        nativeFpsDen = 1,
        colorSpace = "Rec709-SDR",
        displayName = name,
        videoWidth = 1920,
        videoHeight = 1080,
    )

    private fun clipDto(id: String, asset: String, start: Long) = ClipDto(id, asset, timelineStartFrame = start, sourceInFrame = 0, sourceOutFrame = 100)

    private val project = ProjectDto(
        id = "p1",
        name = "Test",
        settings = settings,
        mediaLibrary = listOf(asset("a1", "intro.mp4"), asset("a2", "Beach.MP4"), asset("a3", "lost.mp4")),
        tracks = listOf(
            TrackDto("v1", "video", 0, listOf(clipDto("c1", "a1", 0), clipDto("c2", "a2", 100), clipDto("c3", "a3", 200))),
            TrackDto("a1", "audio", 1),
        ),
    )

    private fun video(seconds: Long = 10, width: Int = 1920, height: Int = 1080) =
        ProbedMedia(seconds * 1_000_000, 30, 1, "Rec709-SDR", hasVideo = true, hasAudio = true, videoWidth = width, videoHeight = height)

    private fun inFolder(path: String, size: Long? = null, kind: RelinkKind = RelinkKind.VIDEO) =
        FolderFile("content://tree/$path", path.split('/'), kind, size)

    private class Harness(
        val vm: EditorViewModel,
        val store: FakeStore,
        val importer: FakeImporter,
        val caches: RecordingCaches,
        val scanner: FakeScanner,
        val effects: MutableList<EditorEffect>,
    ) {
        val state get() = vm.state.value
        val messages get() = effects.filterIsInstance<EditorEffect.ShowMessage>().map { it.text }
        val report get() = (state.folderRelink as FolderRelinkUi.Done).outcome
    }

    private fun TestScope.harness(scanner: FakeScanner, known: Map<String, ProbedMedia> = emptyMap()): Harness {
        val store = FakeStore(project)
        val importer = FakeImporter(known.toMutableMap())
        val caches = RecordingCaches()
        val vm = EditorViewModel("p1", store, importer, idGenerator = { "n1" }, mediaCaches = caches, folderScanner = scanner)
        val effects = mutableListOf<EditorEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { effects += it } }
        advanceUntilIdle()
        return Harness(vm, store, importer, caches, scanner, effects)
    }

    @Test
    fun `asking for a folder scan opens the folder picker`() = runTest(dispatcher) {
        val h = harness(FakeScanner())

        h.vm.onIntent(EditorIntent.RequestFolderRelink)

        assertEquals(listOf<EditorEffect>(EditorEffect.LaunchFolderPicker), h.effects)
    }

    @Test
    fun `every file found is relinked in one step with one save`() = runTest(dispatcher) {
        val scanner = FakeScanner(listOf(inFolder("trip/intro.mp4"), inFolder("trip/beach.mp4"), inFolder("trip/readme.txt", kind = RelinkKind.IMAGE)))
        val h = harness(scanner, known = mapOf("content://tree/trip/intro.mp4" to video(), "content://tree/trip/beach.mp4" to video(20)))
        assertEquals(setOf("a1", "a2", "a3"), h.state.missingMedia.keys)
        val keyBefore = h.vm.assetKey("a1")

        h.vm.onIntent(EditorIntent.RelinkFromFolder("content://tree"))
        advanceUntilIdle()

        assertEquals("content://tree/trip/intro.mp4", h.state.assets.first { it.id == "a1" }.uri)
        assertEquals("content://tree/trip/beach.mp4", h.state.assets.first { it.id == "a2" }.uri)
        assertEquals(600, h.state.assets.first { it.id == "a2" }.durationFrames)
        assertEquals(setOf("a3"), h.state.missingMedia.keys)
        assertEquals(listOf("a1", "a2"), h.caches.invalidated)
        assertNotEquals(keyBefore, h.vm.assetKey("a1"))
        assertEquals("Relinked 2 of 3", h.messages.last())
        assertEquals("Relinked 2 of 3", scanSummaryText(h.report))
        assertEquals(listOf("a3"), h.report.notFound.map { it.assetId })
        assertTrue(h.state.relinkOpen)
        advanceTimeBy(600)
        assertEquals(1, h.store.saved.size)
        assertEquals("content://tree/trip/beach.mp4", h.store.saved.single().mediaLibrary.first { it.id == "a2" }.uri)
    }

    @Test
    fun `relinking from a folder is not an undo step`() = runTest(dispatcher) {
        val h = harness(FakeScanner(listOf(inFolder("intro.mp4"))), known = mapOf("content://tree/intro.mp4" to video()))

        h.vm.onIntent(EditorIntent.RelinkFromFolder("content://tree"))
        advanceUntilIdle()

        assertFalse(h.state.canUndo)
    }

    @Test
    fun `a file whose kind differs is refused and reported`() = runTest(dispatcher) {
        val picture = ProbedMedia(0, 30, 1, "Rec709-SDR", hasVideo = false, hasAudio = false, isImage = true)
        val h = harness(FakeScanner(listOf(inFolder("intro.mp4"))), known = mapOf("content://tree/intro.mp4" to picture))

        h.vm.onIntent(EditorIntent.RelinkFromFolder("content://tree"))
        advanceUntilIdle()

        assertTrue(h.state.missingMedia.containsKey("a1"))
        assertEquals("Relinked 0 of 3", h.messages.last())
        assertTrue(h.report.notFound.first { it.assetId == "a1" }.reason.contains("picture"))
    }

    @Test
    fun `a file that cannot be read is not accepted`() = runTest(dispatcher) {
        val h = harness(FakeScanner(listOf(inFolder("intro.mp4"))))

        h.vm.onIntent(EditorIntent.RelinkFromFolder("content://tree"))
        advanceUntilIdle()

        assertTrue(h.state.missingMedia.containsKey("a1"))
        assertTrue(h.report.notFound.first { it.assetId == "a1" }.reason.contains("cannot be read"))
        advanceTimeBy(600)
        assertTrue(h.store.saved.isEmpty())
    }

    @Test
    fun `same name with different sizes is listed as ambiguous when nothing tells them apart`() = runTest(dispatcher) {
        val scanner = FakeScanner(listOf(inFolder("one/intro.mp4", 100), inFolder("two/intro.mp4", 200)))
        val same = video()
        val h = harness(scanner, known = mapOf("content://tree/one/intro.mp4" to same, "content://tree/two/intro.mp4" to same))

        h.vm.onIntent(EditorIntent.RelinkFromFolder("content://tree"))
        advanceUntilIdle()

        assertTrue(h.state.missingMedia.containsKey("a1"))
        assertEquals(listOf("one/intro.mp4", "two/intro.mp4"), h.report.ambiguous.single().candidates.map { it.path.joinToString("/") })
    }

    @Test
    fun `duration and picture size settle a duplicated name`() = runTest(dispatcher) {
        val scanner = FakeScanner(listOf(inFolder("one/intro.mp4", 100), inFolder("two/intro.mp4", 200)))
        val h = harness(scanner, known = mapOf("content://tree/one/intro.mp4" to video(seconds = 3), "content://tree/two/intro.mp4" to video(seconds = 10)))

        h.vm.onIntent(EditorIntent.RelinkFromFolder("content://tree"))
        advanceUntilIdle()

        assertEquals("content://tree/two/intro.mp4", h.state.assets.first { it.id == "a1" }.uri)
        assertTrue(h.report.ambiguous.isEmpty())
    }

    @Test
    fun `choosing one of the ambiguous candidates relinks it and drops it from the report`() = runTest(dispatcher) {
        val scanner = FakeScanner(listOf(inFolder("one/intro.mp4", 100), inFolder("two/intro.mp4", 200)))
        val same = video()
        val h = harness(scanner, known = mapOf("content://tree/one/intro.mp4" to same, "content://tree/two/intro.mp4" to same))
        h.vm.onIntent(EditorIntent.RelinkFromFolder("content://tree"))
        advanceUntilIdle()

        h.vm.onIntent(EditorIntent.RelinkFromCandidate("a1", "content://tree/two/intro.mp4"))
        runCurrent()

        assertEquals("content://tree/two/intro.mp4", h.state.assets.first { it.id == "a1" }.uri)
        assertTrue(h.report.ambiguous.isEmpty())
        assertFalse(h.state.missingMedia.containsKey("a1"))
    }

    @Test
    fun `the folder a file moved to is used for the others with a repeated name`() = runTest(dispatcher) {
        val scanner = FakeScanner(
            listOf(inFolder("Backup/trip/intro.mp4", 5), inFolder("Backup/trip/beach.mp4", 10), inFolder("Other/beach.mp4", 20)),
        )
        val same = video()
        val known = scanner.files.associate { it.uri to same }
        val h = harness(scanner, known)

        h.vm.onIntent(EditorIntent.RelinkFromFolder("content://tree"))
        advanceUntilIdle()

        assertEquals("content://tree/Backup/trip/beach.mp4", h.state.assets.first { it.id == "a2" }.uri)
    }

    @Test
    fun `cancelling a scan changes nothing`() = runTest(dispatcher) {
        val scanner = FakeScanner(listOf(inFolder("intro.mp4"))).also { it.gate = CompletableDeferred() }
        val h = harness(scanner, known = mapOf("content://tree/intro.mp4" to video()))

        h.vm.onIntent(EditorIntent.RelinkFromFolder("content://tree"))
        runCurrent()
        assertTrue(h.state.folderRelink is FolderRelinkUi.Running)
        assertEquals(1, (h.state.folderRelink as FolderRelinkUi.Running).files)
        h.vm.onIntent(EditorIntent.CancelFolderRelink)
        advanceUntilIdle()

        assertEquals(FolderRelinkUi.Idle, h.state.folderRelink)
        assertEquals(setOf("a1", "a2", "a3"), h.state.missingMedia.keys)
        assertTrue(h.caches.invalidated.isEmpty())
        advanceTimeBy(600)
        assertTrue(h.store.saved.isEmpty())
    }

    @Test
    fun `a second scan cannot start while one runs`() = runTest(dispatcher) {
        val scanner = FakeScanner().also { it.gate = CompletableDeferred() }
        val h = harness(scanner)

        h.vm.onIntent(EditorIntent.RelinkFromFolder("content://tree/1"))
        runCurrent()
        h.vm.onIntent(EditorIntent.RelinkFromFolder("content://tree/2"))
        runCurrent()

        assertEquals(listOf("content://tree/1"), scanner.scanned)
    }

    @Test
    fun `a folder that cannot be read is reported and nothing changes`() = runTest(dispatcher) {
        val scanner = FakeScanner().also { it.fail = FolderScanException("Access to the folder was removed") }
        val h = harness(scanner)

        h.vm.onIntent(EditorIntent.RelinkFromFolder("content://tree"))
        advanceUntilIdle()

        assertEquals(FolderRelinkUi.Idle, h.state.folderRelink)
        assertEquals("Access to the folder was removed", h.messages.last())
        assertEquals(setOf("a1", "a2", "a3"), h.state.missingMedia.keys)
    }

    @Test
    fun `with nothing missing there is nothing to scan`() = runTest(dispatcher) {
        val scanner = FakeScanner()
        val h = harness(scanner, known = project.mediaLibrary.associate { it.uri to video() })

        h.vm.onIntent(EditorIntent.RelinkFromFolder("content://tree"))
        advanceUntilIdle()

        assertTrue(scanner.scanned.isEmpty())
        assertEquals("No media is missing", h.messages.last())
    }

    @Test
    fun `a file already used by another item is not taken twice`() = runTest(dispatcher) {
        // Two missing items with the same name can only both be satisfied by one file: the second must be refused.
        val twin = project.copy(mediaLibrary = project.mediaLibrary + asset("a4", "intro.mp4", oldFolder = "Other"))
        val store = FakeStore(twin)
        val importer = FakeImporter(mutableMapOf("content://tree/intro.mp4" to video()))
        val vm = EditorViewModel("p1", store, importer, idGenerator = { "n1" }, mediaCaches = RecordingCaches(), folderScanner = FakeScanner(listOf(inFolder("intro.mp4"))))
        advanceUntilIdle()

        vm.onIntent(EditorIntent.RelinkFromFolder("content://tree"))
        advanceUntilIdle()

        val done = (vm.state.value.folderRelink as FolderRelinkUi.Done).outcome
        assertEquals(1, done.relinked.size)
        assertTrue(done.notFound.any { it.reason.contains("already in the project") })
    }

    @Test
    fun `closing the results resets the dialog`() = runTest(dispatcher) {
        val h = harness(FakeScanner(listOf(inFolder("intro.mp4"))), known = mapOf("content://tree/intro.mp4" to video()))
        h.vm.onIntent(EditorIntent.RelinkFromFolder("content://tree"))
        advanceUntilIdle()

        h.vm.onIntent(EditorIntent.HideRelink)

        assertFalse(h.state.relinkOpen)
        assertEquals(FolderRelinkUi.Idle, h.state.folderRelink)
    }

    @Test
    fun `old addresses give their folder trail and opaque ones only the name`() {
        assertEquals(listOf("Movies", "trip", "a.mp4"), MissingMedia.pathOfUri("content://com.android.externalstorage.documents/document/primary%3AMovies%2Ftrip%2Fa.mp4"))
        assertEquals(listOf("1234"), MissingMedia.pathOfUri("content://com.android.providers.downloads.documents/document/msf%3A1234"))
        assertEquals(listOf("storage", "emulated", "0", "a.mp4"), MissingMedia.pathOfUri("content://x/document/raw%3A%2Fstorage%2Femulated%2F0%2Fa.mp4"))
    }

    @Test
    fun `progress text names what is happening`() {
        assertEquals("Scanning: 12 media files in 3 folders", scanProgressText(FolderRelinkUi.Running(files = 12, folders = 3)))
        assertEquals("Checking the files found: 1 of 4", scanProgressText(FolderRelinkUi.Running(files = 12, folders = 3, checked = 1, total = 4)))
    }
}
