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
import com.qtekfun.ultimatevideoeditor.ui.text.english

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

        /** Files of a particular folder; a folder not listed here shows [files]. */
        val byTree = mutableMapOf<String, List<FolderFile>>()

        override fun retainAccess(treeUri: String): Boolean = retained

        override suspend fun scan(treeUri: String, limits: ScanLimits, onProgress: (ScanProgress) -> Unit): FolderListing {
            scanned += treeUri
            fail?.let { throw it }
            val here = byTree[treeUri] ?: files
            onProgress(ScanProgress(here.size, 2))
            gate?.await()
            return FolderListing(here, folders = 2, truncated = false)
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
        val messages get() = effects.filterIsInstance<EditorEffect.ShowMessage>().map { it.text.english() }
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
        assertEquals("Relinked 2 of 3", scanSummaryText(h.report).english())
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
        assertEquals("Scanning: 12 media files in 3 folders", scanProgressText(FolderRelinkUi.Running(files = 12, folders = 3)).english())
        assertEquals("Checking the files found: 1 of 4", scanProgressText(FolderRelinkUi.Running(files = 12, folders = 3, checked = 1, total = 4)).english())
    }

    // region scanning another folder (SPECS 5.40)

    private fun audioAsset(id: String, name: String) = asset(id, name).copy(hasVideo = false, videoWidth = 0, videoHeight = 0)

    private fun audio(seconds: Long = 10) = ProbedMedia(seconds * 1_000_000, 30, 1, "Rec709-SDR", hasVideo = false, hasAudio = true)

    /** Two videos (v1, v2) and two audio files (s1, s2), all missing. */
    private fun TestScope.mixedHarness(scanner: FakeScanner, known: Map<String, ProbedMedia>): Harness {
        val mixed = project.copy(
            mediaLibrary = listOf(asset("v1", "intro.mp4"), asset("v2", "beach.mp4"), audioAsset("s1", "music.wav"), audioAsset("s2", "voice.wav")),
            tracks = listOf(
                TrackDto("v1", "video", 0, listOf(clipDto("c1", "v1", 0), clipDto("c2", "v2", 100))),
                TrackDto("a1", "audio", 1, listOf(clipDto("c3", "s1", 0), clipDto("c4", "s2", 100))),
            ),
        )
        val store = FakeStore(mixed)
        val importer = FakeImporter(known.toMutableMap())
        val caches = RecordingCaches()
        val vm = EditorViewModel("p1", store, importer, idGenerator = { "n1" }, mediaCaches = caches, folderScanner = scanner)
        val effects = mutableListOf<EditorEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { effects += it } }
        advanceUntilIdle()
        return Harness(vm, store, importer, caches, scanner, effects)
    }

    private fun twoFolders(): Pair<FakeScanner, Map<String, ProbedMedia>> {
        val scanner = FakeScanner()
        scanner.byTree["content://videos"] = listOf(inFolder("V/intro.mp4"), inFolder("V/beach.mp4"))
        scanner.byTree["content://audio"] = listOf(
            inFolder("A/music.wav", kind = RelinkKind.AUDIO),
            inFolder("A/voice.wav", kind = RelinkKind.AUDIO),
        )
        val known = mapOf(
            "content://tree/V/intro.mp4" to video(),
            "content://tree/V/beach.mp4" to video(),
            "content://tree/A/music.wav" to audio(),
            "content://tree/A/voice.wav" to audio(),
        )
        return scanner to known
    }

    @Test
    fun `a second scan of another folder relinks the rest and the counters add up`() = runTest(dispatcher) {
        val (scanner, known) = twoFolders()
        val h = mixedHarness(scanner, known)

        h.vm.onIntent(EditorIntent.RelinkFromFolder("content://videos"))
        advanceUntilIdle()
        assertEquals("Relinked 2 of 4", scanSummaryText(h.report).english())
        assertEquals(setOf("s1", "s2"), h.state.missingMedia.keys)
        assertEquals(listOf("s1", "s2"), h.report.notFound.map { it.assetId })

        h.vm.onIntent(EditorIntent.RelinkFromFolder("content://audio"))
        advanceUntilIdle()

        assertEquals("Relinked 4 of 4", scanSummaryText(h.report).english())
        assertEquals("Relinked 4 of 4", h.messages.last())
        assertEquals(setOf("v1", "v2", "s1", "s2"), h.report.relinked.map { it.old.id }.toSet())
        assertTrue(h.report.notFound.isEmpty())
        assertTrue(h.state.missingMedia.isEmpty())
        assertEquals("content://tree/A/music.wav", h.state.assets.first { it.id == "s1" }.uri)
        // The videos relinked by the first scan are untouched by the second.
        assertEquals("content://tree/V/intro.mp4", h.state.assets.first { it.id == "v1" }.uri)
        assertEquals(listOf("content://videos", "content://audio"), scanner.scanned)
    }

    @Test
    fun `each scan is one save and not an undo step`() = runTest(dispatcher) {
        val (scanner, known) = twoFolders()
        val h = mixedHarness(scanner, known)

        h.vm.onIntent(EditorIntent.RelinkFromFolder("content://videos"))
        advanceUntilIdle()
        advanceTimeBy(600)
        assertEquals(1, h.store.saved.size)
        h.vm.onIntent(EditorIntent.RelinkFromFolder("content://audio"))
        advanceUntilIdle()
        advanceTimeBy(600)

        assertEquals(2, h.store.saved.size)
        assertFalse(h.state.canUndo)
    }

    @Test
    fun `only the items still missing are looked for in the next scan`() = runTest(dispatcher) {
        val (scanner, known) = twoFolders()
        val h = mixedHarness(scanner, known)
        h.vm.onIntent(EditorIntent.RelinkFromFolder("content://videos"))
        advanceUntilIdle()
        val openedBefore = h.importer.opened.size

        // The audio folder also holds a file named like an already relinked video: it must not be opened or taken.
        scanner.byTree["content://audio"] = scanner.byTree.getValue("content://audio") + inFolder("A/intro.mp4")
        h.vm.onIntent(EditorIntent.RelinkFromFolder("content://audio"))
        advanceUntilIdle()

        assertEquals("content://tree/V/intro.mp4", h.state.assets.first { it.id == "v1" }.uri)
        assertEquals(listOf("content://tree/A/music.wav", "content://tree/A/voice.wav"), h.importer.opened.drop(openedBefore))
    }

    @Test
    fun `a file found by the second scan leaves the not found list and the rest stays`() = runTest(dispatcher) {
        val (scanner, known) = twoFolders()
        scanner.byTree["content://audio"] = listOf(inFolder("A/music.wav", kind = RelinkKind.AUDIO))
        val h = mixedHarness(scanner, known)
        h.vm.onIntent(EditorIntent.RelinkFromFolder("content://videos"))
        advanceUntilIdle()
        assertEquals(listOf("s1", "s2"), h.report.notFound.map { it.assetId })

        h.vm.onIntent(EditorIntent.RelinkFromFolder("content://audio"))
        advanceUntilIdle()

        assertEquals(listOf("s2"), h.report.notFound.map { it.assetId })
        assertEquals("Relinked 3 of 4", scanSummaryText(h.report).english())
        assertEquals(setOf("s2"), h.state.missingMedia.keys)
    }

    @Test
    fun `scanning a third folder finishes what the first two left`() = runTest(dispatcher) {
        val (scanner, known) = twoFolders()
        scanner.byTree["content://audio"] = listOf(inFolder("A/music.wav", kind = RelinkKind.AUDIO))
        scanner.byTree["content://more"] = listOf(inFolder("A/voice.wav", kind = RelinkKind.AUDIO))
        val h = mixedHarness(scanner, known)

        for (tree in listOf("content://videos", "content://audio", "content://more")) {
            h.vm.onIntent(EditorIntent.RelinkFromFolder(tree))
            advanceUntilIdle()
        }

        assertEquals("Relinked 4 of 4", scanSummaryText(h.report).english())
        assertTrue(h.state.missingMedia.isEmpty())
        assertTrue(h.report.notFound.isEmpty())
    }

    @Test
    fun `a scan with nothing missing is not started`() = runTest(dispatcher) {
        val (scanner, known) = twoFolders()
        val h = mixedHarness(scanner, known)
        h.vm.onIntent(EditorIntent.RelinkFromFolder("content://videos"))
        advanceUntilIdle()
        h.vm.onIntent(EditorIntent.RelinkFromFolder("content://audio"))
        advanceUntilIdle()
        val report = h.report

        h.vm.onIntent(EditorIntent.RelinkFromFolder("content://audio"))
        advanceUntilIdle()

        assertEquals(2, scanner.scanned.size)
        assertEquals("No media is missing", h.messages.last())
        assertEquals(report, h.report)
    }

    @Test
    fun `cancelling the second scan keeps the results of the first`() = runTest(dispatcher) {
        val (scanner, known) = twoFolders()
        val h = mixedHarness(scanner, known)
        h.vm.onIntent(EditorIntent.RelinkFromFolder("content://videos"))
        advanceUntilIdle()
        val first = h.report
        scanner.gate = CompletableDeferred()

        h.vm.onIntent(EditorIntent.RelinkFromFolder("content://audio"))
        runCurrent()
        assertTrue(h.state.folderRelink is FolderRelinkUi.Running)
        h.vm.onIntent(EditorIntent.CancelFolderRelink)
        advanceUntilIdle()

        assertEquals(first, h.report)
        assertEquals(setOf("s1", "s2"), h.state.missingMedia.keys)
        assertEquals("content://tree/V/intro.mp4", h.state.assets.first { it.id == "v1" }.uri)
        advanceTimeBy(600)
        assertEquals(1, h.store.saved.size)
    }

    @Test
    fun `a folder that cannot be read during the second scan keeps the first results`() = runTest(dispatcher) {
        val (scanner, known) = twoFolders()
        val h = mixedHarness(scanner, known)
        h.vm.onIntent(EditorIntent.RelinkFromFolder("content://videos"))
        advanceUntilIdle()
        val first = h.report
        scanner.fail = FolderScanException("Access to the folder was removed")

        h.vm.onIntent(EditorIntent.RelinkFromFolder("content://audio"))
        advanceUntilIdle()

        assertEquals(first, h.report)
    }

    @Test
    fun `ambiguous items of both scans are shown together and a later choice resolves one`() = runTest(dispatcher) {
        val scanner = FakeScanner()
        // Both videos are ambiguous in the first folder; the second folder adds a candidate for intro only.
        scanner.byTree["content://one"] = listOf(
            inFolder("x/intro.mp4", 1), inFolder("y/intro.mp4", 2), inFolder("x/beach.mp4", 3), inFolder("y/beach.mp4", 4),
        )
        scanner.byTree["content://two"] = listOf(inFolder("z/intro.mp4", 5), inFolder("w/intro.mp4", 6))
        val same = video()
        val known = (scanner.byTree.getValue("content://one") + scanner.byTree.getValue("content://two")).associate { it.uri to same }
        val h = mixedHarness(scanner, known)

        h.vm.onIntent(EditorIntent.RelinkFromFolder("content://one"))
        advanceUntilIdle()
        assertEquals(listOf("v1", "v2"), h.report.ambiguous.map { it.assetId })

        h.vm.onIntent(EditorIntent.RelinkFromFolder("content://two"))
        advanceUntilIdle()

        val byId = h.report.ambiguous.associateBy { it.assetId }
        assertEquals(setOf("v1", "v2"), byId.keys)
        assertEquals(4, byId.getValue("v1").candidates.size)
        assertEquals(2, byId.getValue("v2").candidates.size)
        // The audio items are listed once, and nothing is double-listed.
        assertEquals(listOf("s1", "s2"), h.report.notFound.map { it.assetId })

        h.vm.onIntent(EditorIntent.RelinkFromCandidate("v1", "content://tree/z/intro.mp4"))
        runCurrent()
        assertEquals(listOf("v2"), h.report.ambiguous.map { it.assetId })
    }

    @Test
    fun `an ambiguous item that the second scan settles leaves the ambiguous list`() = runTest(dispatcher) {
        val scanner = FakeScanner()
        scanner.byTree["content://one"] = listOf(inFolder("x/intro.mp4", 1), inFolder("y/intro.mp4", 2))
        // The second folder has a single file of that name: the name alone settles it there.
        scanner.byTree["content://two"] = listOf(inFolder("z/intro.mp4", 5))
        val same = video()
        val known = (scanner.byTree.getValue("content://one") + scanner.byTree.getValue("content://two")).associate { it.uri to same }
        val h = mixedHarness(scanner, known)
        h.vm.onIntent(EditorIntent.RelinkFromFolder("content://one"))
        advanceUntilIdle()
        assertEquals(listOf("v1"), h.report.ambiguous.map { it.assetId })

        h.vm.onIntent(EditorIntent.RelinkFromFolder("content://two"))
        advanceUntilIdle()

        assertEquals("content://tree/z/intro.mp4", h.state.assets.first { it.id == "v1" }.uri)
        assertTrue(h.report.ambiguous.isEmpty())
    }

    @Test
    fun `an item ambiguous in the first scan and absent from the second stays ambiguous`() = runTest(dispatcher) {
        val scanner = FakeScanner()
        scanner.byTree["content://one"] = listOf(inFolder("x/intro.mp4", 1), inFolder("y/intro.mp4", 2))
        scanner.byTree["content://two"] = emptyList()
        val same = video()
        val known = scanner.byTree.getValue("content://one").associate { it.uri to same }
        val h = mixedHarness(scanner, known)
        h.vm.onIntent(EditorIntent.RelinkFromFolder("content://one"))
        advanceUntilIdle()

        h.vm.onIntent(EditorIntent.RelinkFromFolder("content://two"))
        advanceUntilIdle()

        assertEquals(listOf("v1"), h.report.ambiguous.map { it.assetId })
        assertFalse(h.report.notFound.any { it.assetId == "v1" })
        assertEquals(2, h.report.ambiguous.single().candidates.size)
    }

    @Test
    fun `closing the results and reopening offers the folder scan again while items are missing`() = runTest(dispatcher) {
        val (scanner, known) = twoFolders()
        val h = mixedHarness(scanner, known)
        h.vm.onIntent(EditorIntent.RelinkFromFolder("content://videos"))
        advanceUntilIdle()

        h.vm.onIntent(EditorIntent.HideRelink)
        h.vm.onIntent(EditorIntent.ShowRelink)

        assertEquals(FolderRelinkUi.Idle, h.state.folderRelink)
        assertTrue(h.state.relinkOpen)
        assertEquals(setOf("s1", "s2"), h.state.missingAssets.map { it.assetId }.toSet())
        h.vm.onIntent(EditorIntent.RelinkFromFolder("content://audio"))
        advanceUntilIdle()
        assertTrue(h.state.missingMedia.isEmpty())
    }

    @Test
    fun `the scan action reads Scan a folder before a scan and Scan another folder after it`() = runTest(dispatcher) {
        val (scanner, known) = twoFolders()
        val h = mixedHarness(scanner, known)
        assertEquals("Scan a folder…", scanActionLabel(h.state.folderRelink).english())

        h.vm.onIntent(EditorIntent.RelinkFromFolder("content://videos"))
        advanceUntilIdle()
        assertEquals("Scan another folder…", scanActionLabel(h.state.folderRelink).english())

        // Back to the list keeps the session: same label, same results behind it, the remaining items listed.
        h.vm.onIntent(EditorIntent.DismissFolderRelink)
        val back = h.state.folderRelink as FolderRelinkUi.Done
        assertTrue(back.showList)
        assertEquals("Scan another folder…", scanActionLabel(back).english())
        assertEquals(setOf("s1", "s2"), h.state.missingAssets.map { it.assetId }.toSet())
        assertTrue(h.state.relinkOpen)
    }

    @Test
    fun `scanning from the list after Back accumulates into the same session`() = runTest(dispatcher) {
        val (scanner, known) = twoFolders()
        val h = mixedHarness(scanner, known)
        h.vm.onIntent(EditorIntent.RelinkFromFolder("content://videos"))
        advanceUntilIdle()
        h.vm.onIntent(EditorIntent.DismissFolderRelink)

        h.vm.onIntent(EditorIntent.RelinkFromFolder("content://audio"))
        advanceUntilIdle()

        assertEquals("Relinked 4 of 4", scanSummaryText(h.report).english())
        assertFalse((h.state.folderRelink as FolderRelinkUi.Done).showList)
    }

    @Test
    fun `Back with nothing missing leaves no list and no scan action`() = runTest(dispatcher) {
        val (scanner, known) = twoFolders()
        val h = mixedHarness(scanner, known)
        h.vm.onIntent(EditorIntent.RelinkFromFolder("content://videos"))
        advanceUntilIdle()
        h.vm.onIntent(EditorIntent.RelinkFromFolder("content://audio"))
        advanceUntilIdle()

        h.vm.onIntent(EditorIntent.DismissFolderRelink)

        assertTrue(h.state.missingAssets.isEmpty())
        assertFalse(h.state.relinkOpen)
    }

    @Test
    fun `cancelling a scan started from the list returns to the list`() = runTest(dispatcher) {
        val (scanner, known) = twoFolders()
        val h = mixedHarness(scanner, known)
        h.vm.onIntent(EditorIntent.RelinkFromFolder("content://videos"))
        advanceUntilIdle()
        h.vm.onIntent(EditorIntent.DismissFolderRelink)
        scanner.gate = CompletableDeferred()

        h.vm.onIntent(EditorIntent.RelinkFromFolder("content://audio"))
        runCurrent()
        h.vm.onIntent(EditorIntent.CancelFolderRelink)
        advanceUntilIdle()

        val done = h.state.folderRelink as FolderRelinkUi.Done
        assertTrue(done.showList)
        assertEquals("Relinked 2 of 4", scanSummaryText(done.outcome).english())
    }

    @Test
    fun `closing the dialog ends the session and the next scan starts at zero`() = runTest(dispatcher) {
        val (scanner, known) = twoFolders()
        val h = mixedHarness(scanner, known)
        h.vm.onIntent(EditorIntent.RelinkFromFolder("content://videos"))
        advanceUntilIdle()
        h.vm.onIntent(EditorIntent.DismissFolderRelink)

        h.vm.onIntent(EditorIntent.HideRelink)
        assertEquals(FolderRelinkUi.Idle, h.state.folderRelink)
        h.vm.onIntent(EditorIntent.ShowRelink)
        assertEquals("Scan a folder…", scanActionLabel(h.state.folderRelink).english())
        h.vm.onIntent(EditorIntent.RelinkFromFolder("content://audio"))
        advanceUntilIdle()

        // Two items were missing when this session began, so it counts 2 of 2, not 4 of 4.
        assertEquals("Relinked 2 of 2", scanSummaryText(h.report).english())
    }

    // endregion
}
