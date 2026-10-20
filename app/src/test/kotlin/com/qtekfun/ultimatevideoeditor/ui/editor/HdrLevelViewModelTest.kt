package com.qtekfun.ultimatevideoeditor.ui.editor

import com.qtekfun.ultimatevideoeditor.data.MediaImportException
import com.qtekfun.ultimatevideoeditor.data.MediaImporter
import com.qtekfun.ultimatevideoeditor.data.ProbedMedia
import com.qtekfun.ultimatevideoeditor.data.ProjectStore
import com.qtekfun.ultimatevideoeditor.data.model.ClipDto
import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto
import com.qtekfun.ultimatevideoeditor.data.model.ProjectDto
import com.qtekfun.ultimatevideoeditor.data.model.ProjectSettingsDto
import com.qtekfun.ultimatevideoeditor.data.model.TrackDto
import com.qtekfun.ultimatevideoeditor.domain.HdrBrightness
import com.qtekfun.ultimatevideoeditor.domain.HdrLevels
import com.qtekfun.ultimatevideoeditor.domain.SourceColorSpace
import com.qtekfun.ultimatevideoeditor.engine.timeline.HitKind
import com.qtekfun.ultimatevideoeditor.engine.timeline.TimelineHit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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

/** The HDR level as the editor runs it: which files are measured, when, and what the project keeps. */
@OptIn(ExperimentalCoroutinesApi::class)
class HdrLevelViewModelTest {
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
        override suspend fun load(id: String): ProjectDto = project

        override suspend fun save(project: ProjectDto) {
            this.project = project
        }
    }

    private object ReadableImporter : MediaImporter {
        override suspend fun import(uri: String): ProbedMedia = ProbedMedia(5_000_000, 30, 1, "Rec2020-HLG", hasVideo = true, hasAudio = true)
    }

    private object BrokenImporter : MediaImporter {
        override suspend fun import(uri: String): ProbedMedia = throw MediaImportException("gone")
    }

    private class FakeAnalyzer(val level: Double? = 2.0, val gate: CompletableDeferred<Unit>? = null) : HdrLevelAnalyzer {
        val calls = ArrayList<Pair<String, SourceColorSpace>>()

        override suspend fun diffuseLevel(asset: MediaAssetDto, space: SourceColorSpace): Double? {
            calls += asset.id to space
            gate?.await()
            return level
        }
    }

    private class MemoryCache : HdrLevelCache {
        val values = HashMap<String, Double>()

        override fun get(key: String) = values[key]

        override fun put(key: String, level: Double) {
            values[key] = level
        }
    }

    private val hlg = MediaAssetDto("hlg", "content://m/hlg", 300, 30, 1, "Rec2020-HLG")
    private val sdr = MediaAssetDto("sdr", "content://m/sdr", 300, 30, 1, "Rec709-SDR")
    private val hlgKey = HdrLevelCache.keyOf(hlg, SourceColorSpace.HLG)

    private fun project(brightness: String = "comfortable") = ProjectDto(
        id = "p1",
        name = "Test",
        settings = ProjectSettingsDto(1920, 1080, 30, 1, "Rec2020-HLG", hdrBrightness = brightness),
        mediaLibrary = listOf(hlg, sdr),
        tracks = listOf(
            TrackDto("v1", "video", 0, listOf(ClipDto("h", "hlg", 0, 0, 100), ClipDto("s", "sdr", 100, 0, 100))),
            TrackDto("a1", "audio", 1),
        ),
    )

    private class Harness(val vm: EditorViewModel, val store: FakeStore) {
        val state get() = vm.state.value
        val hdr: HdrLevels get() = state.hdr

        /** Selects a clip the way a tap does; keys are assigned lazily by the snapshot, so read it once first. */
        fun select(clipId: String) {
            val ordered = state.visibleTimeline.tracks.flatMap { it.clips }.map { it.id }
            val key = vm.snapshotOf(state).clips[ordered.indexOf(clipId)].clipKey
            vm.onIntent(EditorIntent.TapTimeline(TimelineHit(HitKind.CLIP, 0, key, 5)))
        }
    }

    private fun TestScope.harness(
        store: FakeStore = FakeStore(project()),
        analyzer: HdrLevelAnalyzer,
        cache: HdrLevelCache = MemoryCache(),
        importer: MediaImporter = ReadableImporter,
    ): Harness {
        val vm = EditorViewModel("p1", store, importer, hdrAnalyzer = analyzer, hdrLevelCache = cache, hdrDispatcher = dispatcher, saveDebounceMillis = 0)
        advanceUntilIdle()
        return Harness(vm, store)
    }

    @Test
    fun `an HLG file is measured once on opening and an SDR file never is`() = runTest(dispatcher) {
        val analyzer = FakeAnalyzer()
        val h = harness(analyzer = analyzer)
        assertEquals(listOf("hlg" to SourceColorSpace.HLG), analyzer.calls)
        assertEquals(2.0, h.hdr.levels.getValue(hlgKey), 0.0)
        assertEquals(1, h.hdr.levels.size)
        assertEquals(0.5, h.hdr.gainOf(null, hlg.uri, hlg.durationFrames, SourceColorSpace.HLG, null), 1e-12)
        assertTrue(h.hdr.hdrProject)
    }

    @Test
    fun `until the measurement finishes the clip is shown as recorded and then the level arrives`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val analyzer = FakeAnalyzer(gate = gate)
        val h = harness(analyzer = analyzer)
        assertTrue(h.hdr.levels.isEmpty())
        assertEquals(1.0, h.hdr.gainOf(null, hlg.uri, hlg.durationFrames, SourceColorSpace.HLG, null), 0.0)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, h.hdr.levels.size) // the state changed, which is what redraws the preview
    }

    @Test
    fun `a level already in the cache is not measured again, and a new one is stored`() = runTest(dispatcher) {
        val cache = MemoryCache().apply { put(hlgKey, 1.6) }
        val cached = FakeAnalyzer()
        val h = harness(analyzer = cached, cache = cache)
        assertTrue(cached.calls.isEmpty())
        assertEquals(1.6, h.hdr.levels.getValue(hlgKey), 0.0)

        val empty = MemoryCache()
        harness(analyzer = FakeAnalyzer(level = 1.3), cache = empty)
        assertEquals(1.3, empty.values.getValue(hlgKey), 0.0)
    }

    @Test
    fun `a file that cannot be measured is shown as recorded and not asked for again`() = runTest(dispatcher) {
        val analyzer = FakeAnalyzer(level = null)
        val h = harness(analyzer = analyzer)
        assertTrue(h.hdr.levels.isEmpty())
        h.vm.onIntent(EditorIntent.SetPlayhead(10)) // an edit or any other sync must not retry
        h.vm.onIntent(EditorIntent.SetHdrBrightness(HdrBrightness.ORIGINAL))
        h.vm.onIntent(EditorIntent.SetHdrBrightness(HdrBrightness.COMFORTABLE))
        advanceUntilIdle()
        assertEquals(1, analyzer.calls.size)
    }

    @Test
    fun `an unreadable file is not opened for measuring`() = runTest(dispatcher) {
        val analyzer = FakeAnalyzer()
        harness(analyzer = analyzer, importer = BrokenImporter)
        assertTrue(analyzer.calls.isEmpty())
    }

    @Test
    fun `the original setting measures nothing until the project or a clip asks for it`() = runTest(dispatcher) {
        val analyzer = FakeAnalyzer()
        val h = harness(store = FakeStore(project("original")), analyzer = analyzer)
        assertEquals(HdrBrightness.ORIGINAL, h.hdr.brightness)
        assertTrue(analyzer.calls.isEmpty())
        h.vm.onIntent(EditorIntent.SetHdrBrightness(HdrBrightness.COMFORTABLE))
        advanceUntilIdle()
        assertEquals(1, analyzer.calls.size)
        assertEquals(0.5, h.hdr.gainOf(null, hlg.uri, hlg.durationFrames, SourceColorSpace.HLG, null), 1e-12)
    }

    @Test
    fun `the setting is saved with the project`() = runTest(dispatcher) {
        val h = harness(analyzer = FakeAnalyzer())
        h.vm.onIntent(EditorIntent.SetHdrBrightness(HdrBrightness.ORIGINAL))
        advanceUntilIdle()
        assertEquals("original", h.store.project.settings.hdrBrightness)
        assertEquals(HdrBrightness.ORIGINAL, h.hdr.brightness)
        h.vm.onIntent(EditorIntent.SetHdrBrightness(HdrBrightness.COMFORTABLE))
        advanceUntilIdle()
        assertEquals("comfortable", h.store.project.settings.hdrBrightness)
    }

    @Test
    fun `a clip can switch its level off and the choice is one undo step`() = runTest(dispatcher) {
        val h = harness(analyzer = FakeAnalyzer())
        h.select("h")
        h.vm.onIntent(EditorIntent.SetClipHdrLevel(false))
        val clip = h.state.timeline.track("v1")!!.clip("h")!!
        assertEquals(false, clip.hdrAutoLevel)
        assertEquals(1.0, h.hdr.gainOf(clip.hdrAutoLevel, hlg.uri, hlg.durationFrames, SourceColorSpace.HLG, null), 0.0)
        h.vm.onIntent(EditorIntent.Undo)
        assertNull(h.state.timeline.track("v1")!!.clip("h")!!.hdrAutoLevel)
        h.vm.onIntent(EditorIntent.Redo)
        assertEquals(false, h.state.timeline.track("v1")!!.clip("h")!!.hdrAutoLevel)
    }

    @Test
    fun `reading a file as another colour space measures it as that space`() = runTest(dispatcher) {
        val analyzer = FakeAnalyzer()
        val h = harness(analyzer = analyzer)
        h.select("s")
        h.vm.onIntent(EditorIntent.SetClipColor(SourceColorSpace.PQ))
        advanceUntilIdle()
        assertTrue(("sdr" to SourceColorSpace.PQ) in analyzer.calls)
        assertEquals(2, h.hdr.levels.size)
    }

    @Test
    fun `switching a clip to SDR stops it needing a measurement`() = runTest(dispatcher) {
        val analyzer = FakeAnalyzer()
        val store = FakeStore(project().copy(tracks = project().tracks.map { t -> t.copy(clips = t.clips.map { if (it.id == "h") it.copy(colorOverride = "Rec709-SDR") else it }) }))
        harness(store = store, analyzer = analyzer)
        assertTrue(analyzer.calls.isEmpty())
    }
}
