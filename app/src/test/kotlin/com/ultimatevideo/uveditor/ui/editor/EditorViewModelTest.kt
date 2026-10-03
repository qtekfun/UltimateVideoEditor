package com.ultimatevideo.uveditor.ui.editor

import com.ultimatevideo.uveditor.data.MediaImportException
import com.ultimatevideo.uveditor.data.MediaImporter
import com.ultimatevideo.uveditor.data.ProbedMedia
import com.ultimatevideo.uveditor.data.ProjectError
import com.ultimatevideo.uveditor.data.ProjectStore
import com.ultimatevideo.uveditor.data.model.ClipDto
import com.ultimatevideo.uveditor.data.model.MediaAssetDto
import com.ultimatevideo.uveditor.data.model.ProjectDto
import com.ultimatevideo.uveditor.data.model.ProjectSettingsDto
import com.ultimatevideo.uveditor.data.model.TrackDto
import com.ultimatevideo.uveditor.domain.Clip
import com.ultimatevideo.uveditor.domain.FrameIndex
import com.ultimatevideo.uveditor.domain.TrackType
import com.ultimatevideo.uveditor.engine.timeline.HitKind
import com.ultimatevideo.uveditor.engine.timeline.TimelineHit
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EditorViewModelTest {

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
        val saved = mutableListOf<ProjectDto>()
        var failSave = false

        override suspend fun load(id: String): ProjectDto = project ?: throw ProjectError.NotFound(id)

        override suspend fun save(project: ProjectDto) {
            if (failSave) throw ProjectError.Io("disk full", java.io.IOException("full"))
            saved += project
            this.project = project
        }
    }

    private class FakeImporter(val media: Map<String, ProbedMedia>) : MediaImporter {
        override suspend fun import(uri: String): ProbedMedia =
            media[uri] ?: throw MediaImportException("Unsupported file")
    }

    private val settings = ProjectSettingsDto(1920, 1080, 30, 1, "Rec709-SDR")
    private val asset = MediaAssetDto("a1", "content://m/a1", durationFrames = 300, nativeFpsNum = 30, nativeFpsDen = 1, colorSpace = "Rec709-SDR")

    private fun clipDto(id: String, start: Long, gain: Double = 0.0) =
        ClipDto(id, "a1", timelineStartFrame = start, sourceInFrame = 0, sourceOutFrame = 100, gainDb = gain)

    private fun project(withClips: Boolean = true) = ProjectDto(
        id = "p1",
        name = "Test",
        settings = settings,
        mediaLibrary = listOf(asset),
        tracks = listOf(
            TrackDto("v1", "video", 0, if (withClips) listOf(clipDto("c1", 0, gain = -6.0), clipDto("c2", 100)) else emptyList()),
            TrackDto("a1", "audio", 1),
        ),
    )

    private val video5s = ProbedMedia(5_000_000, 30, 1, "Rec709-SDR", hasVideo = true, hasAudio = true)
    private val audio2s = ProbedMedia(2_000_000, 30, 1, "Rec709-SDR", hasVideo = false, hasAudio = true)

    private class Harness(val vm: EditorViewModel, val store: FakeStore, val effects: MutableList<EditorEffect>) {
        val state get() = vm.state.value
        fun clips(trackId: String): List<Clip> = state.timeline.track(trackId)?.clips.orEmpty()

        fun hitOn(clipId: String, frame: Long, kind: HitKind = HitKind.CLIP, track: Int = 0) =
            TimelineHit(kind, track, keyOf(clipId), frame)

        // Keys are assigned lazily by snapshotOf, so touch every clip once before looking one up.
        private fun keyOf(clipId: String): Long {
            val ordered = state.visibleTimeline.tracks.flatMap { it.clips }.map { it.id }
            val snapshot = vm.snapshotOf(state)
            return snapshot.clips[ordered.indexOf(clipId)].clipKey
        }

        fun select(clipId: String) = vm.onIntent(EditorIntent.TapTimeline(hitOn(clipId, 0)))
    }

    private fun TestScope.harness(
        project: ProjectDto? = project(),
        importer: MediaImporter = FakeImporter(emptyMap()),
    ): Harness {
        var counter = 0
        val store = FakeStore(project)
        val vm = EditorViewModel("p1", store, importer, idGenerator = { "n${counter++}" })
        val effects = mutableListOf<EditorEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { effects += it } }
        advanceUntilIdle()
        return Harness(vm, store, effects)
    }

    @Test
    fun `loads project into state`() = runTest(dispatcher) {
        val h = harness()

        assertFalse(h.state.isLoading)
        assertEquals("Test", h.state.projectName)
        assertEquals(listOf("c1", "c2"), h.clips("v1").map { it.id })
        assertFalse(h.state.canUndo)
    }

    @Test
    fun `missing tracks are created on load`() = runTest(dispatcher) {
        val empty = project(withClips = false).copy(tracks = emptyList())
        val h = harness(empty)

        assertEquals(listOf(TrackType.VIDEO, TrackType.AUDIO), h.state.timeline.tracks.map { it.type })
    }

    @Test
    fun `load failure is reported in state`() = runTest(dispatcher) {
        val h = harness(project = null)

        assertFalse(h.state.isLoading)
        assertNotNull(h.state.loadError)
    }

    @Test
    fun `tapping a clip selects it and tapping empty space clears the selection`() = runTest(dispatcher) {
        val h = harness()

        h.select("c2")
        assertEquals("c2", h.state.selectedClipId)

        h.vm.onIntent(EditorIntent.TapTimeline(TimelineHit(HitKind.EMPTY_TRACK, 0, -1, 500)))
        assertNull(h.state.selectedClipId)
    }

    @Test
    fun `tapping the ruler moves the playhead`() = runTest(dispatcher) {
        val h = harness()

        h.vm.onIntent(EditorIntent.TapTimeline(TimelineHit(HitKind.RULER, -1, -1, 250)))

        assertEquals(FrameIndex(250), h.state.playhead)
    }

    @Test
    fun `split cuts the selected clip at the playhead and the right half inherits its parent id`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")
        h.vm.onIntent(EditorIntent.SetPlayhead(40))

        h.vm.onIntent(EditorIntent.SplitAtPlayhead)

        val clips = h.clips("v1")
        assertEquals(listOf("c1", "c1~n0", "c2"), clips.map { it.id })
        assertEquals(40, clips[0].durationFrames)
        assertEquals(FrameIndex(40), clips[1].timelineStart)
        assertTrue(h.state.canUndo)
    }

    @Test
    fun `split on a clip edge explains why nothing happened`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")
        h.vm.onIntent(EditorIntent.SetPlayhead(100))

        h.vm.onIntent(EditorIntent.SplitAtPlayhead)
        runCurrent()

        assertEquals(2, h.clips("v1").size)
        assertTrue(h.effects.single() is EditorEffect.ShowMessage)
    }

    @Test
    fun `edit commands without a selection ask for one`() = runTest(dispatcher) {
        val h = harness()

        h.vm.onIntent(EditorIntent.RippleDeleteSelected)
        runCurrent()

        assertEquals(2, h.clips("v1").size)
        assertTrue(h.effects.single() is EditorEffect.ShowMessage)
    }

    @Test
    fun `ripple delete shifts later clips and undo restores them`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")

        h.vm.onIntent(EditorIntent.RippleDeleteSelected)

        assertEquals(listOf("c2"), h.clips("v1").map { it.id })
        assertEquals(FrameIndex(0), h.clips("v1").single().timelineStart)
        assertNull(h.state.selectedClipId)

        h.vm.onIntent(EditorIntent.Undo)
        assertEquals(listOf("c1", "c2"), h.clips("v1").map { it.id })
        assertTrue(h.state.canRedo)

        h.vm.onIntent(EditorIntent.Redo)
        assertEquals(listOf("c2"), h.clips("v1").map { it.id })
    }

    @Test
    fun `ripple append closes the gap before the selected clip`() = runTest(dispatcher) {
        val h = harness(project().copy(tracks = project().tracks.map {
            if (it.id == "v1") it.copy(clips = listOf(clipDto("c1", 0), clipDto("c2", 150))) else it
        }))
        h.select("c2")

        h.vm.onIntent(EditorIntent.RippleAppendSelected)

        assertEquals(FrameIndex(100), h.clips("v1").last().timelineStart)
    }

    @Test
    fun `dragging a clip previews without touching history until it is released`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")
        val grab = h.hitOn("c1", frame = 10)

        h.vm.onIntent(EditorIntent.DragStart(grab))
        h.vm.onIntent(EditorIntent.DragMove(frame = 250, trackIndex = 0))

        assertEquals(FrameIndex(240), h.state.dragPreview?.track("v1")?.clip("c1")?.timelineStart)
        assertEquals(FrameIndex(0), h.clips("v1").first().timelineStart)
        assertFalse(h.state.canUndo)

        h.vm.onIntent(EditorIntent.DragEnd(commit = true))

        assertNull(h.state.dragPreview)
        assertEquals(FrameIndex(240), h.state.timeline.track("v1")?.clip("c1")?.timelineStart)
        assertTrue(h.state.canUndo)
    }

    @Test
    fun `dragging snaps to a neighbouring clip edge`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")
        h.vm.onIntent(EditorIntent.DragStart(h.hitOn("c1", frame = 0)))

        h.vm.onIntent(EditorIntent.DragMove(frame = 203, trackIndex = 0))
        h.vm.onIntent(EditorIntent.DragEnd(commit = true))

        assertEquals(FrameIndex(200), h.state.timeline.track("v1")?.clip("c1")?.timelineStart)
    }

    @Test
    fun `dragging onto another clip is rejected and cancelling discards the preview`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")
        h.vm.onIntent(EditorIntent.DragStart(h.hitOn("c1", frame = 0)))

        h.vm.onIntent(EditorIntent.DragMove(frame = 150, trackIndex = 0))
        assertNull(h.state.dragPreview)

        h.vm.onIntent(EditorIntent.DragMove(frame = 300, trackIndex = 0))
        assertNotNull(h.state.dragPreview)
        h.vm.onIntent(EditorIntent.DragEnd(commit = false))

        assertNull(h.state.dragPreview)
        assertEquals(FrameIndex(0), h.clips("v1").first().timelineStart)
        assertFalse(h.state.canUndo)
    }

    @Test
    fun `only the selected clip can be dragged`() = runTest(dispatcher) {
        val h = harness()
        val onC2 = h.hitOn("c2", frame = 120)

        assertFalse(h.vm.canDrag(onC2))
        h.select("c2")
        assertTrue(h.vm.canDrag(onC2))
        assertFalse(h.vm.canDrag(h.hitOn("c1", frame = 10)))
        assertFalse(h.vm.canDrag(TimelineHit(HitKind.RULER, -1, -1, 5)))
    }

    @Test
    fun `trimming the right edge is bounded by the source length`() = runTest(dispatcher) {
        val h = harness()
        h.select("c2")
        h.vm.onIntent(EditorIntent.DragStart(h.hitOn("c2", frame = 199, kind = HitKind.CLIP_RIGHT_EDGE)))

        h.vm.onIntent(EditorIntent.DragMove(frame = 450, trackIndex = 0))
        assertNull(h.state.dragPreview)

        h.vm.onIntent(EditorIntent.DragMove(frame = 350, trackIndex = 0))
        h.vm.onIntent(EditorIntent.DragEnd(commit = true))

        assertEquals(250, h.clips("v1").last().durationFrames)
    }

    @Test
    fun `trimming the left edge moves the start and the source in point together`() = runTest(dispatcher) {
        val h = harness()
        h.select("c2")
        h.vm.onIntent(EditorIntent.DragStart(h.hitOn("c2", frame = 101, kind = HitKind.CLIP_LEFT_EDGE)))

        h.vm.onIntent(EditorIntent.DragMove(frame = 130, trackIndex = 0))
        h.vm.onIntent(EditorIntent.DragEnd(commit = true))

        val c2 = h.clips("v1").last()
        assertEquals(FrameIndex(130), c2.timelineStart)
        assertEquals(FrameIndex(30), c2.sourceIn)
        assertEquals(FrameIndex(200), c2.timelineEnd)
    }

    @Test
    fun `importing places a clip at the playhead on the video track and selects it`() = runTest(dispatcher) {
        val h = harness(importer = FakeImporter(mapOf("content://new" to video5s)))
        h.vm.onIntent(EditorIntent.SetPlayhead(300))

        h.vm.onIntent(EditorIntent.ImportMedia(listOf("content://new")))
        advanceUntilIdle()

        val placed = h.clips("v1").last()
        assertEquals(FrameIndex(300), placed.timelineStart)
        assertEquals(150, placed.durationFrames)
        assertEquals(h.state.selectedClipId, placed.id)
        assertEquals(2, h.state.assets.size)
        assertFalse(h.state.isImporting)
    }

    @Test
    fun `audio only media goes to the audio track and importing a known uri reuses its asset`() = runTest(dispatcher) {
        val h = harness(importer = FakeImporter(mapOf("content://song" to audio2s)))

        h.vm.onIntent(EditorIntent.ImportMedia(listOf("content://song")))
        advanceUntilIdle()
        h.vm.onIntent(EditorIntent.SetPlayhead(500))
        h.vm.onIntent(EditorIntent.ImportMedia(listOf("content://song")))
        advanceUntilIdle()

        assertEquals(2, h.clips("a1").size)
        assertEquals(2, h.state.assets.size)
    }

    @Test
    fun `several imports are placed back to back`() = runTest(dispatcher) {
        val h = harness(
            project = project(withClips = false),
            importer = FakeImporter(mapOf("content://one" to video5s, "content://two" to video5s)),
        )

        h.vm.onIntent(EditorIntent.ImportMedia(listOf("content://one", "content://two")))
        advanceUntilIdle()

        assertEquals(listOf(FrameIndex(0), FrameIndex(150)), h.clips("v1").map { it.timelineStart })
    }

    @Test
    fun `a failed import is reported and leaves the timeline alone`() = runTest(dispatcher) {
        val h = harness()

        h.vm.onIntent(EditorIntent.ImportMedia(listOf("content://broken")))
        advanceUntilIdle()

        assertEquals(2, h.clips("v1").size)
        assertFalse(h.state.isImporting)
        assertEquals("Unsupported file", (h.effects.single() as EditorEffect.ShowMessage).text)
    }

    @Test
    fun `adding a library asset places it at the playhead`() = runTest(dispatcher) {
        val h = harness(project(withClips = false))
        h.vm.onIntent(EditorIntent.SetPlayhead(20))

        h.vm.onIntent(EditorIntent.AddAsset("a1"))

        val clip = h.clips("v1").single()
        assertEquals(FrameIndex(20), clip.timelineStart)
        assertEquals(300, clip.durationFrames)
    }

    @Test
    fun `edits are saved once after the debounce and keep extras of derived clips`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")
        h.vm.onIntent(EditorIntent.SetPlayhead(40))
        h.vm.onIntent(EditorIntent.SplitAtPlayhead)
        h.vm.onIntent(EditorIntent.Undo)
        h.vm.onIntent(EditorIntent.Redo)

        advanceTimeBy(499)
        assertTrue(h.store.saved.isEmpty())
        advanceTimeBy(2)

        val saved = h.store.saved.single()
        val clips = saved.tracks.first { it.id == "v1" }.clips
        assertEquals(listOf("c1", "c1~n0", "c2"), clips.map { it.id })
        assertEquals(-6.0, clips.first { it.id == "c1~n0" }.gainDb, 0.0)
    }

    @Test
    fun `back saves immediately then closes`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")
        h.vm.onIntent(EditorIntent.RippleDeleteSelected)

        h.vm.onIntent(EditorIntent.Back)
        runCurrent()

        assertEquals(1, h.store.saved.size)
        assertEquals(listOf<EditorEffect>(EditorEffect.Close), h.effects)
    }

    @Test
    fun `back without edits does not rewrite the project`() = runTest(dispatcher) {
        val h = harness()

        h.vm.onIntent(EditorIntent.Back)
        runCurrent()

        assertTrue(h.store.saved.isEmpty())
        assertEquals(listOf<EditorEffect>(EditorEffect.Close), h.effects)
    }

    @Test
    fun `a failed save is reported and retried on the next flush`() = runTest(dispatcher) {
        val h = harness()
        h.store.failSave = true
        h.select("c1")
        h.vm.onIntent(EditorIntent.RippleDeleteSelected)
        advanceTimeBy(600)
        assertTrue(h.effects.single() is EditorEffect.ShowMessage)

        h.store.failSave = false
        h.vm.onIntent(EditorIntent.Flush)
        runCurrent()

        assertEquals(1, h.store.saved.size)
    }

    @Test
    fun `snapshot reflects the visible timeline selection and stable keys`() = runTest(dispatcher) {
        val h = harness()
        h.select("c2")

        val first = h.vm.snapshotOf(h.state)
        val second = h.vm.snapshotOf(h.state)

        assertEquals(first, second)
        assertEquals(listOf(false, true), first.clips.map { it.selected })
        assertEquals(listOf(0L, 100L), first.clips.map { it.startFrame })
        assertEquals(2, first.tracks.size)
        assertEquals(first.clips[0].assetKey, first.clips[1].assetKey)
        assertEquals(30, first.fpsNum)
    }
}
