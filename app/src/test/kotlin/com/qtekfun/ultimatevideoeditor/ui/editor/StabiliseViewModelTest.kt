package com.qtekfun.ultimatevideoeditor.ui.editor

import com.qtekfun.ultimatevideoeditor.data.MediaImportException
import com.qtekfun.ultimatevideoeditor.data.MediaImporter
import com.qtekfun.ultimatevideoeditor.data.ProbedMedia
import com.qtekfun.ultimatevideoeditor.data.ProjectError
import com.qtekfun.ultimatevideoeditor.data.ProjectStore
import com.qtekfun.ultimatevideoeditor.data.model.ClipDto
import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto
import com.qtekfun.ultimatevideoeditor.data.model.ProjectDto
import com.qtekfun.ultimatevideoeditor.data.model.ProjectSettingsDto
import com.qtekfun.ultimatevideoeditor.data.model.StabiliseDto
import com.qtekfun.ultimatevideoeditor.data.model.TitleDto
import com.qtekfun.ultimatevideoeditor.data.model.TrackDto
import com.qtekfun.ultimatevideoeditor.domain.Clip
import com.qtekfun.ultimatevideoeditor.domain.FrameRate
import com.qtekfun.ultimatevideoeditor.domain.StabCrop
import com.qtekfun.ultimatevideoeditor.domain.Stabilise
import com.qtekfun.ultimatevideoeditor.engine.stabilise.StabOutcome
import com.qtekfun.ultimatevideoeditor.engine.stabilise.StabStatus
import com.qtekfun.ultimatevideoeditor.engine.stabilise.Stabiliser
import com.qtekfun.ultimatevideoeditor.engine.timeline.HitKind
import com.qtekfun.ultimatevideoeditor.engine.timeline.TimelineHit
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StabiliseViewModelTest {
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

    private class FakeStabiliser : Stabiliser {
        var status: StabStatus = StabStatus.NotAnalysed
        val registered = mutableListOf<String>()
        var releaseAlls = 0
        var cancels = 0
        var analyses = 0
        /** When set, an analysis waits for it; otherwise it ends at once with [outcome]. */
        var gate: CompletableDeferred<StabOutcome>? = null
        var outcome: StabOutcome = StabOutcome.Done
        var statusAfterDone: StabStatus = StabStatus.Ready

        override fun statusOf(asset: MediaAssetDto, clip: Clip, fps: FrameRate): StabStatus = if (clip.stabilise == null) StabStatus.Off else status

        override suspend fun analyse(asset: MediaAssetDto, clip: Clip, fps: FrameRate, onProgress: (Float) -> Unit): StabOutcome {
            analyses++
            onProgress(0.25f)
            val result = gate?.await() ?: outcome
            if (result == StabOutcome.Done) {
                onProgress(1f)
                status = statusAfterDone
            }
            return result
        }

        override fun cancel() {
            cancels++
        }

        override fun register(asset: MediaAssetDto, clip: Clip, fps: FrameRate): Boolean {
            registered += clip.id
            return true
        }

        override fun releaseAll() {
            releaseAlls++
        }
    }

    private val asset = MediaAssetDto("a1", "content://m/a1", durationFrames = 900, nativeFpsNum = 30, nativeFpsDen = 1, colorSpace = "Rec709-SDR")

    private fun project(stabilised: Boolean = false) = ProjectDto(
        id = "p1",
        name = "Test",
        settings = ProjectSettingsDto(1920, 1080, 30, 1, "Rec709-SDR"),
        mediaLibrary = listOf(asset),
        tracks = listOf(
            TrackDto(
                "v1", "video", 0,
                listOf(
                    ClipDto("c1", "a1", 0, 0, 300, stabilise = if (stabilised) StabiliseDto(0.5, "tight") else null),
                    ClipDto("c2", "a1", 300, 0, 150),
                ),
            ),
            TrackDto("t1", "title", 1, listOf(ClipDto("t", null, 0, 0, 60, title = TitleDto(text = "Hi")))),
            TrackDto("a1", "audio", 2, listOf(ClipDto("m", "a1", 0, 0, 100))),
        ),
    )

    private class Harness(val vm: EditorViewModel, val stab: FakeStabiliser, val effects: MutableList<EditorEffect>) {
        val state get() = vm.state.value

        fun select(clipId: String) {
            val ordered = state.visibleTimeline.tracks.flatMap { it.clips }.map { it.id }
            val key = vm.snapshotOf(state).clips[ordered.indexOf(clipId)].clipKey
            vm.onIntent(EditorIntent.TapTimeline(TimelineHit(HitKind.CLIP, 0, key, 0)))
        }

        fun clip(id: String) = checkNotNull(state.timeline.trackOfClip(id)?.clip(id))

        fun messages(): List<String> = effects.filterIsInstance<EditorEffect.ShowMessage>().map { it.text }
    }

    private fun TestScope.harness(stab: FakeStabiliser = FakeStabiliser(), project: ProjectDto = project()): Harness {
        var counter = 0
        val vm = EditorViewModel(
            "p1", FakeStore(project), NoImporter(), idGenerator = { "n${counter++}" },
            stabiliser = stab, stabDispatcher = dispatcher,
        )
        val effects = mutableListOf<EditorEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { effects += it } }
        advanceUntilIdle()
        return Harness(vm, stab, effects)
    }

    @Test
    fun `turning the stabiliser on is one undo step and makes the clip's table available`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")
        h.vm.onIntent(EditorIntent.SetStabilise(Stabilise(0.6, StabCrop.FULL)))
        advanceUntilIdle()

        assertEquals(Stabilise(0.6, StabCrop.FULL), h.clip("c1").stabilise)
        assertEquals(listOf("c1"), h.stab.registered.distinct())
        assertEquals("c1", h.state.stab.clipId)
        assertEquals(StabStatus.NotAnalysed, h.state.stab.status)

        h.vm.onIntent(EditorIntent.Undo)
        advanceUntilIdle()
        assertNull(h.clip("c1").stabilise)
        assertEquals(StabStatus.Off, h.state.stab.status)
    }

    @Test
    fun `changing the strength or the crop is another undo step`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")
        h.vm.onIntent(EditorIntent.SetStabilise(Stabilise()))
        h.vm.onIntent(EditorIntent.SetStabilise(Stabilise(0.9, StabCrop.TIGHT)))
        advanceUntilIdle()
        assertEquals(Stabilise(0.9, StabCrop.TIGHT), h.clip("c1").stabilise)
        h.vm.onIntent(EditorIntent.Undo)
        assertEquals(Stabilise(), h.clip("c1").stabilise)
        h.vm.onIntent(EditorIntent.SetStabilise(null))
        assertNull(h.clip("c1").stabilise)
    }

    @Test
    fun `a title or an audio clip cannot be stabilised and the user is told`() = runTest(dispatcher) {
        val h = harness()
        h.select("t")
        h.vm.onIntent(EditorIntent.SetStabilise(Stabilise()))
        advanceUntilIdle()
        assertNull(h.clip("t").stabilise)
        assertTrue(h.messages().isNotEmpty())
    }

    @Test
    fun `the inspector's refresh reads the status of the selected stabilised clip`() = runTest(dispatcher) {
        val stab = FakeStabiliser().apply { status = StabStatus.Stale }
        val h = harness(stab, project(stabilised = true))
        h.select("c1")
        h.vm.onIntent(EditorIntent.RefreshStabilise)
        advanceUntilIdle()
        assertEquals("c1", h.state.stab.clipId)
        assertEquals(StabStatus.Stale, h.state.stab.status)

        stab.status = StabStatus.Ready
        h.vm.onIntent(EditorIntent.RefreshStabilise)
        advanceUntilIdle()
        assertEquals(StabStatus.Ready, h.state.stab.status)
    }

    @Test
    fun `a clip that is not stabilised shows no status`() = runTest(dispatcher) {
        val h = harness()
        h.select("c2")
        h.vm.onIntent(EditorIntent.RefreshStabilise)
        advanceUntilIdle()
        assertEquals(StabStatus.Off, h.state.stab.status)
        assertNull(h.state.stab.progress)
    }

    @Test
    fun `opening a project releases old tables and registers its stabilised clips`() = runTest(dispatcher) {
        val stab = FakeStabiliser().apply { status = StabStatus.Ready }
        val h = harness(stab, project(stabilised = true))
        assertTrue(h.stab.releaseAlls >= 1)
        assertTrue("c1" in h.stab.registered)
        assertFalse("c2" in h.stab.registered)
    }

    @Test
    fun `an analysis shows its progress, then the new status, and registers the table`() = runTest(dispatcher) {
        val stab = FakeStabiliser().apply { gate = CompletableDeferred() }
        val h = harness(stab, project(stabilised = true))
        h.select("c1")
        h.vm.onIntent(EditorIntent.AnalyseStabilise)
        advanceUntilIdle()
        assertEquals(0.25f, h.state.stab.progress!!, 0f)
        assertEquals(StabStatus.NotAnalysed, h.state.stab.status)
        val registeredBefore = stab.registered.size

        stab.gate!!.complete(StabOutcome.Done)
        advanceUntilIdle()
        assertNull(h.state.stab.progress)
        assertEquals(StabStatus.Ready, h.state.stab.status)
        assertTrue(stab.registered.size > registeredBefore)  // refreshed after the analysis
        assertTrue(h.messages().isEmpty())
    }

    @Test
    fun `only one analysis runs at a time`() = runTest(dispatcher) {
        val stab = FakeStabiliser().apply { gate = CompletableDeferred() }
        val h = harness(stab, project(stabilised = true))
        h.select("c1")
        h.vm.onIntent(EditorIntent.AnalyseStabilise)
        advanceUntilIdle()
        h.vm.onIntent(EditorIntent.AnalyseStabilise)
        advanceUntilIdle()
        assertEquals(1, stab.analyses)
        stab.gate!!.complete(StabOutcome.Done)
        advanceUntilIdle()
        // After it finished another one may start.
        stab.gate = null
        h.vm.onIntent(EditorIntent.AnalyseStabilise)
        advanceUntilIdle()
        assertEquals(2, stab.analyses)
    }

    @Test
    fun `a failed analysis tells the user and leaves the status alone`() = runTest(dispatcher) {
        val stab = FakeStabiliser().apply { outcome = StabOutcome.Failed("The video could not be decoded") }
        val h = harness(stab, project(stabilised = true))
        h.select("c1")
        h.vm.onIntent(EditorIntent.AnalyseStabilise)
        advanceUntilIdle()
        assertEquals(listOf("The video could not be decoded"), h.messages())
        assertNull(h.state.stab.progress)
        assertEquals(StabStatus.NotAnalysed, h.state.stab.status)
    }

    @Test
    fun `cancelling an analysis stops it quietly`() = runTest(dispatcher) {
        val stab = FakeStabiliser().apply { gate = CompletableDeferred() }
        val h = harness(stab, project(stabilised = true))
        h.select("c1")
        h.vm.onIntent(EditorIntent.AnalyseStabilise)
        advanceUntilIdle()
        h.vm.onIntent(EditorIntent.CancelStabilise)
        assertEquals(1, stab.cancels)
        stab.gate!!.complete(StabOutcome.Cancelled)
        advanceUntilIdle()
        assertNull(h.state.stab.progress)
        assertTrue(h.messages().isEmpty())
        assertNotNull(h.state.stab.clipId)
    }

    @Test
    fun `analysing without the stabiliser on, or with nothing selected, explains what to do`() = runTest(dispatcher) {
        val h = harness()
        h.vm.onIntent(EditorIntent.AnalyseStabilise)
        advanceUntilIdle()
        h.select("c2")
        h.vm.onIntent(EditorIntent.AnalyseStabilise)
        advanceUntilIdle()
        assertEquals(2, h.messages().size)
        assertEquals(0, h.stab.analyses)
    }

    @Test
    fun `a stabilised clip reaches the preview scene with its key`() = runTest(dispatcher) {
        val h = harness(project = project(stabilised = true))
        val layers = previewRequestsAt(h.state.timeline, h.state.assets, h.state.fps, com.qtekfun.ultimatevideoeditor.domain.FrameIndex(10)) { 1 }
        val stabilised = layers.single { it.sourceFrame == 10L && it.fx.stabKey != null }
        assertEquals(com.qtekfun.ultimatevideoeditor.domain.StabKey.of("a1", Stabilise(0.5, StabCrop.TIGHT)), stabilised.fx.stabKey)
    }
}
