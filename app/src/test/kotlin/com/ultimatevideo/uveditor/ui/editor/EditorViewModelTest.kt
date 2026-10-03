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
import com.ultimatevideo.uveditor.domain.DropHint
import com.ultimatevideo.uveditor.domain.DropKind
import com.ultimatevideo.uveditor.domain.ClipTransform
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

    /** Overlay v2 (x, y) over the base v1 (c1, c2), for the free-form behaviour of non-base tracks. */
    private fun overlayProject() = ProjectDto(
        id = "p1",
        name = "Test",
        settings = settings,
        mediaLibrary = listOf(asset),
        tracks = listOf(
            TrackDto("v2", "video", 0, listOf(clipDto("x", 0), clipDto("y", 100))),
            TrackDto("v1", "video", 1, listOf(clipDto("c1", 0), clipDto("c2", 100))),
            TrackDto("a1", "audio", 2),
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
        clock: () -> Long = { 0L },
    ): Harness {
        var counter = 0
        val store = FakeStore(project)
        val vm = EditorViewModel("p1", store, importer, idGenerator = { "n${counter++}" }, nanoClock = clock)
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
    fun `media saved as having audio is re-probed on load and fixed and saved`() = runTest(dispatcher) {
        val silent = ProbedMedia(10_000_000, 30, 1, "Rec709-SDR", hasVideo = true, hasAudio = false)
        val h = harness(importer = FakeImporter(mapOf("content://m/a1" to silent)))

        assertFalse(h.state.assets.single().hasAudio)
        assertTrue(h.state.assets.single().hasVideo)
        advanceTimeBy(600)
        assertFalse(h.store.saved.single().mediaLibrary.single().hasAudio)
    }

    @Test
    fun `media that cannot be re-probed keeps its flags and nothing is rewritten`() = runTest(dispatcher) {
        val h = harness() // the fake importer does not know the asset's uri

        assertTrue(h.state.assets.single().hasAudio)
        advanceTimeBy(600)
        assertTrue(h.store.saved.isEmpty())
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

        // The base is magnetic: dragging c1 past c2 swaps them with no gap.
        assertEquals(FrameIndex(100), h.state.dragPreview?.track("v1")?.clip("c1")?.timelineStart)
        assertEquals(FrameIndex(0), h.clips("v1").first().timelineStart)
        assertFalse(h.state.canUndo)

        h.vm.onIntent(EditorIntent.DragEnd(commit = true))

        assertNull(h.state.dragPreview)
        assertEquals(FrameIndex(100), h.state.timeline.track("v1")?.clip("c1")?.timelineStart)
        assertEquals(FrameIndex(0), h.state.timeline.track("v1")?.clip("c2")?.timelineStart)
        assertTrue(h.state.canUndo)
    }

    @Test
    fun `dragging an overlay snaps to a neighbouring clip edge`() = runTest(dispatcher) {
        val h = harness(overlayProject())
        h.select("x")
        h.vm.onIntent(EditorIntent.DragStart(h.hitOn("x", frame = 0)))

        h.vm.onIntent(EditorIntent.DragMove(frame = 203, trackIndex = 0))
        h.vm.onIntent(EditorIntent.DragEnd(commit = true))

        assertEquals(FrameIndex(200), h.state.timeline.track("v2")?.clip("x")?.timelineStart)
        // Overlays are free: the base did not move.
        assertEquals(listOf(0L, 100L), h.clips("v1").map { it.timelineStart.value })
    }

    @Test
    fun `dragging a base clip past its neighbour reorders the base without a gap`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")
        h.vm.onIntent(EditorIntent.DragStart(h.hitOn("c1", frame = 0)))

        h.vm.onIntent(EditorIntent.DragMove(frame = 203, trackIndex = 0))
        h.vm.onIntent(EditorIntent.DragEnd(commit = true))

        assertEquals(listOf("c2" to 0L, "c1" to 100L), h.clips("v1").map { it.id to it.timelineStart.value })
    }

    // region drop zones: the indicator shown during the drag is what a release does

    private fun stackedProject() = ProjectDto(
        id = "p1",
        name = "Test",
        settings = settings,
        mediaLibrary = listOf(asset),
        tracks = listOf(
            TrackDto("v3", "video", 0),
            TrackDto("v2", "video", 1, listOf(clipDto("x", 0), clipDto("y", 100))),
            TrackDto("v1", "video", 2, listOf(clipDto("c1", 0), clipDto("c2", 100))),
            TrackDto("a1", "audio", 3),
        ),
    )

    private fun Harness.startDrag(clipId: String) {
        select(clipId)
        vm.onIntent(EditorIntent.DragStart(hitOn(clipId, frame = state.timeline.trackOfClip(clipId)!!.clip(clipId)!!.timelineStart.value)))
    }

    @Test
    fun `dropping an overlay on another clip overwrites it and the hint says so`() = runTest(dispatcher) {
        val h = harness(overlayProject())
        h.startDrag("x")

        h.vm.onIntent(EditorIntent.DragMove(frame = 150, trackIndex = 0))
        assertEquals(DropHint(DropKind.OVERWRITE, "v2", 150, 250), h.state.dropHint)
        assertNotNull(h.state.dragPreview)
        assertFalse(h.state.canUndo)

        h.vm.onIntent(EditorIntent.DragEnd(commit = true))
        assertNull(h.state.dropHint)
        assertNull(h.state.dragPreview)
        assertEquals(listOf("y" to (100L to 150L), "x" to (150L to 250L)), h.clips("v2").map { it.id to (it.timelineStart.value to it.timelineEnd.value) })
        // One undo step restores everything.
        h.vm.onIntent(EditorIntent.Undo)
        assertEquals(listOf("x" to 0L, "y" to 100L), h.clips("v2").map { it.id to it.timelineStart.value })
    }

    @Test
    fun `free space in an overlay lane is a plain move with no indicator`() = runTest(dispatcher) {
        val h = harness(overlayProject())
        h.startDrag("x")

        h.vm.onIntent(EditorIntent.DragMove(frame = 300, trackIndex = 0))
        assertNull(h.state.dropHint)
        assertNotNull(h.state.dragPreview)
        h.vm.onIntent(EditorIntent.DragEnd(commit = true))

        assertEquals(FrameIndex(300), h.clips("v2").first { it.id == "x" }.timelineStart)
    }

    @Test
    fun `dragging an overlay near a cut on the base inserts there and overlays follow`() = runTest(dispatcher) {
        val h = harness(overlayProject())
        h.startDrag("x")

        h.vm.onIntent(EditorIntent.DragMove(frame = 103, trackIndex = 1))
        assertEquals(DropHint(DropKind.INSERT, "v1", 100, 100), h.state.dropHint)

        h.vm.onIntent(EditorIntent.DragEnd(commit = true))
        assertEquals(listOf("c1", "x", "c2"), h.clips("v1").map { it.id })
        assertEquals(300L, h.clips("v1").last().timelineEnd.value)
        // y started at the cut, so it moves with the footage that was under it.
        assertEquals(listOf("y" to 200L), h.clips("v2").map { it.id to it.timelineStart.value })
        h.vm.onIntent(EditorIntent.Undo)
        assertEquals(listOf("c1", "c2"), h.clips("v1").map { it.id })
        assertEquals(listOf("x", "y"), h.clips("v2").map { it.id })
    }

    @Test
    fun `dragging an overlay over a base clip body overwrites it and keeps the base length`() = runTest(dispatcher) {
        val h = harness(overlayProject())
        h.startDrag("x")

        h.vm.onIntent(EditorIntent.DragMove(frame = 40, trackIndex = 1))
        assertEquals(DropHint(DropKind.OVERWRITE, "v1", 40, 140), h.state.dropHint)

        h.vm.onIntent(EditorIntent.DragEnd(commit = true))
        assertEquals(200L, h.state.timeline.track("v1")!!.end.value)
        assertTrue(h.clips("v1").any { it.id == "x" && it.timelineStart.value == 40L })
        assertEquals(emptyList<String>(), h.state.timeline.invariantViolations())
        h.vm.onIntent(EditorIntent.Undo)
        assertEquals(listOf("c1", "c2"), h.clips("v1").map { it.id })
    }

    @Test
    fun `the add-lane zone shows a new lane, stays stable while it appears, and is one undo step`() = runTest(dispatcher) {
        val h = harness(overlayProject())
        h.startDrag("x")

        h.vm.onIntent(EditorIntent.DragMove(frame = 10, trackIndex = -1, zone = DragZone.ABOVE_LANES))
        val hint = h.state.dropHint!!
        assertEquals(DropKind.NEW_LANE, hint.kind)
        assertEquals(4, h.state.dragPreview!!.tracks.size)
        assertEquals(hint.trackId, h.state.dragPreview!!.tracks.first().id)

        // The finger is now over the new lane (index 0 of what is shown): the target must not flip back.
        h.vm.onIntent(EditorIntent.DragMove(frame = 20, trackIndex = 0))
        assertEquals(DropKind.NEW_LANE, h.state.dropHint!!.kind)
        assertEquals(4, h.state.dragPreview!!.tracks.size)

        h.vm.onIntent(EditorIntent.DragEnd(commit = true))
        assertEquals(4, h.state.timeline.tracks.size)
        assertEquals(listOf("x"), h.state.timeline.tracks.first().clips.map { it.id })
        h.vm.onIntent(EditorIntent.Undo)
        assertEquals(3, h.state.timeline.tracks.size)
        assertEquals(listOf("x", "y"), h.clips("v2").map { it.id })
    }

    @Test
    fun `moving the finger back from the new lane drops into the lane below`() = runTest(dispatcher) {
        val h = harness(overlayProject())
        h.startDrag("x")
        h.vm.onIntent(EditorIntent.DragMove(frame = 10, trackIndex = -1, zone = DragZone.ABOVE_LANES))

        // In the shown timeline the old overlay lane is now index 1.
        h.vm.onIntent(EditorIntent.DragMove(frame = 300, trackIndex = 1))
        assertNull(h.state.dropHint)
        assertEquals(3, h.state.dragPreview!!.tracks.size)
    }

    @Test
    fun `leaving the panel cancels and release changes nothing`() = runTest(dispatcher) {
        val h = harness(overlayProject())
        h.startDrag("x")
        h.vm.onIntent(EditorIntent.DragMove(frame = 150, trackIndex = 0))
        assertNotNull(h.state.dragPreview)

        h.vm.onIntent(EditorIntent.DragMove(frame = 150, trackIndex = -1, zone = DragZone.OUTSIDE))
        assertEquals(DropKind.CANCEL, h.state.dropHint!!.kind)
        assertNull(h.state.dragPreview)

        h.vm.onIntent(EditorIntent.DragEnd(commit = true))
        assertNull(h.state.dropHint)
        assertFalse(h.state.canUndo)
        assertEquals(listOf("x" to 0L, "y" to 100L), h.clips("v2").map { it.id to it.timelineStart.value })
    }

    @Test
    fun `crossing a gap between lanes keeps the last target`() = runTest(dispatcher) {
        val h = harness(overlayProject())
        h.startDrag("x")
        h.vm.onIntent(EditorIntent.DragMove(frame = 40, trackIndex = 1))
        assertEquals(DropKind.OVERWRITE, h.state.dropHint!!.kind)

        h.vm.onIntent(EditorIntent.DragMove(frame = 50, trackIndex = -1))
        assertEquals(DropHint(DropKind.OVERWRITE, "v1", 50, 150), h.state.dropHint)
    }

    @Test
    fun `reordering inside the base shows an insertion marker at the cut`() = runTest(dispatcher) {
        val h = harness(overlayProject())
        h.select("c2")
        h.vm.onIntent(EditorIntent.DragStart(h.hitOn("c2", frame = 100, track = 1)))

        // The clip's centre has to pass the neighbour's centre, so drag it all the way to the start.
        h.vm.onIntent(EditorIntent.DragMove(frame = 0, trackIndex = 1))
        assertEquals(DropHint(DropKind.INSERT, "v1", 0, 0), h.state.dropHint)

        h.vm.onIntent(EditorIntent.DragEnd(commit = true))
        assertEquals(listOf("c2", "c1"), h.clips("v1").map { it.id })
    }

    @Test
    fun `a base clip cannot leave the base and the new-lane zone does nothing for it`() = runTest(dispatcher) {
        val h = harness(overlayProject())
        h.select("c1")
        h.vm.onIntent(EditorIntent.DragStart(h.hitOn("c1", frame = 0, track = 1)))

        h.vm.onIntent(EditorIntent.DragMove(frame = 10, trackIndex = -1, zone = DragZone.ABOVE_LANES))
        assertNull(h.state.dropHint?.takeIf { it.kind == DropKind.NEW_LANE })
        h.vm.onIntent(EditorIntent.DragEnd(commit = true))
        assertEquals(3, h.state.timeline.tracks.size)
    }

    @Test
    fun `an audio clip dropped on another audio clip overwrites, never inserts`() = runTest(dispatcher) {
        val project = ProjectDto(
            id = "p1", name = "Test", settings = settings, mediaLibrary = listOf(asset),
            tracks = listOf(
                TrackDto("v1", "video", 0, listOf(clipDto("c1", 0))),
                TrackDto("a1", "audio", 1, listOf(clipDto("m", 0), clipDto("n", 100))),
            ),
        )
        val h = harness(project)
        h.startDrag("m")
        // Right on the m|n cut: a base would insert here, an audio lane overwrites.
        h.vm.onIntent(EditorIntent.DragMove(frame = 100, trackIndex = 1))
        assertEquals(DropKind.OVERWRITE, h.state.dropHint!!.kind)
    }

    // endregion

    // region moving lanes

    @Test
    fun `the selected overlay lane moves up and down among overlays and undo restores it`() = runTest(dispatcher) {
        val h = harness(stackedProject())
        h.select("x")
        assertEquals("v2", h.state.selectedTrackId)

        h.vm.onIntent(EditorIntent.MoveSelectedTrack(-1))
        assertEquals(listOf("v2", "v3", "v1", "a1"), h.state.timeline.tracks.map { it.id })
        h.vm.onIntent(EditorIntent.MoveSelectedTrack(1))
        assertEquals(listOf("v3", "v2", "v1", "a1"), h.state.timeline.tracks.map { it.id })
        h.vm.onIntent(EditorIntent.Undo)
        assertEquals(listOf("v2", "v3", "v1", "a1"), h.state.timeline.tracks.map { it.id })
    }

    @Test
    fun `the base lane and the edge lanes refuse to move with a message`() = runTest(dispatcher) {
        val h = harness(stackedProject())
        h.select("c1")
        h.vm.onIntent(EditorIntent.MoveSelectedTrack(-1))
        assertEquals(listOf("v3", "v2", "v1", "a1"), h.state.timeline.tracks.map { it.id })
        assertTrue(h.effects.any { it is EditorEffect.ShowMessage })

        h.effects.clear()
        h.select("x")
        h.vm.onIntent(EditorIntent.MoveSelectedTrack(1))
        assertEquals(listOf("v3", "v2", "v1", "a1"), h.state.timeline.tracks.map { it.id })
        assertTrue(h.effects.any { it is EditorEffect.ShowMessage })
    }

    // endregion

    @Test
    fun `only the selected clip can be dragged`() = runTest(dispatcher) {
        val h = harness()
        val onC2 = h.hitOn("c2", frame = 120)

        assertFalse(h.vm.canDrag(onC2))
        h.select("c2")
        assertTrue(h.vm.canDrag(onC2))
        assertFalse(h.vm.canDrag(h.hitOn("c1", frame = 10)))
        // The ruler and playhead always scrub; nothing else is draggable without a selection.
        assertTrue(h.vm.canDrag(TimelineHit(HitKind.RULER, -1, -1, 5)))
        assertTrue(h.vm.canDrag(TimelineHit(HitKind.PLAYHEAD, -1, -1, 5)))
        assertFalse(h.vm.canDrag(TimelineHit(HitKind.EMPTY_TRACK, 0, -1, 5)))
    }

    @Test
    fun `dragging the playhead moves it without touching the timeline or history`() = runTest(dispatcher) {
        val h = harness()

        h.vm.onIntent(EditorIntent.DragStart(TimelineHit(HitKind.PLAYHEAD, -1, -1, 10)))
        h.vm.onIntent(EditorIntent.DragMove(frame = 75, trackIndex = -1))
        h.vm.onIntent(EditorIntent.DragMove(frame = -20, trackIndex = -1))
        assertEquals(FrameIndex(0), h.state.playhead)
        h.vm.onIntent(EditorIntent.DragMove(frame = 120, trackIndex = -1))
        h.vm.onIntent(EditorIntent.DragEnd(commit = true))

        assertEquals(FrameIndex(120), h.state.playhead)
        assertNull(h.state.dragPreview)
        assertFalse(h.state.canUndo)
        assertEquals(listOf("c1", "c2"), h.clips("v1").map { it.id })
    }

    @Test
    fun `play advances the playhead in real time and stops at the end of the timeline`() = runTest(dispatcher) {
        val h = harness(clock = { testScheduler.currentTime * 1_000_000 })

        h.vm.onIntent(EditorIntent.TogglePlay)
        assertTrue(h.state.isPlaying)

        advanceTimeBy(1_000)
        assertEquals(30.0, h.state.playhead.value.toDouble(), 2.0)

        advanceTimeBy(6_000)
        runCurrent()
        assertFalse(h.state.isPlaying)
        assertEquals(FrameIndex(200), h.state.playhead)
    }

    @Test
    fun `toggling play again pauses where it is`() = runTest(dispatcher) {
        val h = harness(clock = { testScheduler.currentTime * 1_000_000 })
        h.vm.onIntent(EditorIntent.TogglePlay)
        advanceTimeBy(500)

        h.vm.onIntent(EditorIntent.TogglePlay)
        val paused = h.state.playhead
        advanceTimeBy(2_000)

        assertFalse(h.state.isPlaying)
        assertEquals(paused, h.state.playhead)
    }

    @Test
    fun `play from the end restarts from the beginning and an empty timeline cannot play`() = runTest(dispatcher) {
        val h = harness(clock = { testScheduler.currentTime * 1_000_000 })
        h.vm.onIntent(EditorIntent.SetPlayhead(200))
        h.vm.onIntent(EditorIntent.TogglePlay)
        assertEquals(FrameIndex(0), h.state.playhead)
        h.vm.onIntent(EditorIntent.TogglePlay)

        val empty = harness(project(withClips = false))
        empty.vm.onIntent(EditorIntent.TogglePlay)
        runCurrent()

        assertFalse(empty.state.isPlaying)
        assertTrue(empty.effects.single() is EditorEffect.ShowMessage)
    }

    private class FakeOutput : PlaybackOutput {
        val calls = mutableListOf<String>()
        var heard: Long? = null

        override fun play(fromFrame: Long) {
            calls += "play($fromFrame)"
        }

        override fun pause() {
            calls += "pause"
        }

        override fun seek(frame: Long) {
            calls += "seek($frame)"
        }

        override fun heardFrame(): Long? = heard
    }

    @Test
    fun `playing starts the output at the playhead and pausing stops it`() = runTest(dispatcher) {
        val h = harness(clock = { testScheduler.currentTime * 1_000_000 })
        val out = FakeOutput().also { h.vm.playbackOutput = it }
        h.vm.onIntent(EditorIntent.SetPlayhead(40))

        h.vm.onIntent(EditorIntent.TogglePlay)
        h.vm.onIntent(EditorIntent.TogglePlay)

        assertEquals(listOf("seek(40)", "play(40)", "pause"), out.calls)
    }

    @Test
    fun `the audio clock drives the playhead and is never allowed to run backwards`() = runTest(dispatcher) {
        val h = harness(clock = { testScheduler.currentTime * 1_000_000 })
        val out = FakeOutput().also { h.vm.playbackOutput = it }
        h.vm.onIntent(EditorIntent.SetPlayhead(50))
        h.vm.onIntent(EditorIntent.TogglePlay)

        out.heard = 48 // latency compensation puts the heard position slightly before the start
        advanceTimeBy(20)
        assertEquals(FrameIndex(50), h.state.playhead)

        out.heard = 120
        advanceTimeBy(20)
        assertEquals(FrameIndex(120), h.state.playhead)

        h.vm.onIntent(EditorIntent.TogglePlay) // stop the tick loop so runTest can finish
    }

    @Test
    fun `reaching the end stops the output`() = runTest(dispatcher) {
        val h = harness(clock = { testScheduler.currentTime * 1_000_000 })
        val out = FakeOutput().also { h.vm.playbackOutput = it }
        h.vm.onIntent(EditorIntent.TogglePlay)
        out.heard = 250

        advanceTimeBy(20)

        assertFalse(h.state.isPlaying)
        assertEquals(FrameIndex(200), h.state.playhead)
        assertEquals("pause", out.calls.last())
    }

    @Test
    fun `scrubbing seeks the output and tapping the ruler while playing restarts from there`() = runTest(dispatcher) {
        val h = harness(clock = { testScheduler.currentTime * 1_000_000 })
        val out = FakeOutput().also { h.vm.playbackOutput = it }

        h.vm.onIntent(EditorIntent.DragStart(TimelineHit(HitKind.PLAYHEAD, -1, -1, 10)))
        h.vm.onIntent(EditorIntent.DragMove(frame = 80, trackIndex = -1))
        assertEquals("seek(80)", out.calls.last())

        out.calls.clear()
        h.vm.onIntent(EditorIntent.TogglePlay)
        h.vm.onIntent(EditorIntent.TapTimeline(TimelineHit(HitKind.RULER, -1, -1, 30)))

        assertEquals(listOf("play(80)", "pause", "seek(30)", "play(30)"), out.calls)
        assertTrue(h.state.isPlaying)

        h.vm.onIntent(EditorIntent.TogglePlay)
    }

    @Test
    fun `without an output the transport falls back to the system clock`() = runTest(dispatcher) {
        val h = harness(clock = { testScheduler.currentTime * 1_000_000 })

        h.vm.onIntent(EditorIntent.TogglePlay)
        advanceTimeBy(1_000)

        assertEquals(30.0, h.state.playhead.value.toDouble(), 2.0)
        h.vm.onIntent(EditorIntent.TogglePlay)
    }

    @Test
    fun `seeking jumps between clip boundaries`() = runTest(dispatcher) {
        val h = harness()

        h.vm.onIntent(EditorIntent.SeekNext)
        assertEquals(FrameIndex(100), h.state.playhead)
        h.vm.onIntent(EditorIntent.SeekNext)
        assertEquals(FrameIndex(200), h.state.playhead)
        h.vm.onIntent(EditorIntent.SeekNext)
        assertEquals(FrameIndex(200), h.state.playhead)

        h.vm.onIntent(EditorIntent.SetPlayhead(150))
        h.vm.onIntent(EditorIntent.SeekPrevious)
        assertEquals(FrameIndex(100), h.state.playhead)
        h.vm.onIntent(EditorIntent.SeekPrevious)
        assertEquals(FrameIndex(0), h.state.playhead)
        h.vm.onIntent(EditorIntent.SeekPrevious)
        assertEquals(FrameIndex(0), h.state.playhead)
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
    fun `trimming the left edge of a base clip keeps its start and advances the source in point`() = runTest(dispatcher) {
        val h = harness()
        h.select("c2")
        h.vm.onIntent(EditorIntent.DragStart(h.hitOn("c2", frame = 101, kind = HitKind.CLIP_LEFT_EDGE)))

        h.vm.onIntent(EditorIntent.DragMove(frame = 130, trackIndex = 0))
        h.vm.onIntent(EditorIntent.DragEnd(commit = true))

        // Magnetic base: the clip stays where the previous one ends and simply gets shorter.
        val c2 = h.clips("v1").last()
        assertEquals(FrameIndex(100), c2.timelineStart)
        assertEquals(FrameIndex(30), c2.sourceIn)
        assertEquals(FrameIndex(170), c2.timelineEnd)
    }

    @Test
    fun `trimming the left edge of an overlay moves the start and the source in point together`() = runTest(dispatcher) {
        val h = harness(overlayProject())
        h.select("y")
        h.vm.onIntent(EditorIntent.DragStart(h.hitOn("y", frame = 101, kind = HitKind.CLIP_LEFT_EDGE)))

        h.vm.onIntent(EditorIntent.DragMove(frame = 130, trackIndex = 0))
        h.vm.onIntent(EditorIntent.DragEnd(commit = true))

        val y = h.clips("v2").last()
        assertEquals(FrameIndex(130), y.timelineStart)
        assertEquals(FrameIndex(30), y.sourceIn)
        assertEquals(FrameIndex(200), y.timelineEnd)
    }

    @Test
    fun `importing past the end of the base appends the clip there and selects it`() = runTest(dispatcher) {
        val h = harness(importer = FakeImporter(mapOf("content://new" to video5s)))
        h.vm.onIntent(EditorIntent.SetPlayhead(300))

        h.vm.onIntent(EditorIntent.ImportMedia(listOf("content://new")))
        advanceUntilIdle()

        // The base has no gaps: a playhead past its end appends right after the last clip.
        val placed = h.clips("v1").last()
        assertEquals(FrameIndex(200), placed.timelineStart)
        assertEquals(150, placed.durationFrames)
        assertEquals(h.state.selectedClipId, placed.id)
        assertEquals(2, h.state.assets.size)
        assertFalse(h.state.isImporting)
    }

    @Test
    fun `importing with the playhead inside a base clip inserts at the nearest cut and ripples the rest`() = runTest(dispatcher) {
        val h = harness(importer = FakeImporter(mapOf("content://new" to video5s)))
        h.vm.onIntent(EditorIntent.SetPlayhead(130))

        h.vm.onIntent(EditorIntent.ImportMedia(listOf("content://new")))
        advanceUntilIdle()

        // 130 is closer to the start of c2 (100) than to its end: the new clip goes before c2.
        val layout = h.clips("v1").map { it.timelineStart.value to it.timelineEnd.value }
        assertEquals(listOf(0L to 100L, 100L to 250L, 250L to 350L), layout)
        assertEquals("c2", h.clips("v1").last().id)
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
    fun `adding a library asset to an empty base starts it at zero whatever the playhead`() = runTest(dispatcher) {
        val h = harness(project(withClips = false))
        h.vm.onIntent(EditorIntent.SetPlayhead(20))

        h.vm.onIntent(EditorIntent.AddAsset("a1"))

        val clip = h.clips("v1").single()
        assertEquals(FrameIndex(0), clip.timelineStart)
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
    fun `a new video track goes on top and an audio track at the bottom, both undoable`() = runTest(dispatcher) {
        val h = harness()

        h.vm.onIntent(EditorIntent.AddTrack(TrackType.VIDEO))
        assertEquals(listOf(TrackType.VIDEO, TrackType.VIDEO, TrackType.AUDIO), h.state.timeline.tracks.map { it.type })
        assertEquals("track-v1", h.state.timeline.tracks.first().id)
        assertEquals("track-v1", h.state.selectedTrackId)
        assertEquals("V2", h.state.selectedTrackLabel)

        h.vm.onIntent(EditorIntent.AddTrack(TrackType.AUDIO))
        assertEquals("track-a1", h.state.timeline.tracks.last().id)
        assertEquals("A2", h.state.selectedTrackLabel)

        h.vm.onIntent(EditorIntent.Undo)
        h.vm.onIntent(EditorIntent.Undo)
        assertEquals(listOf("v1", "a1"), h.state.timeline.tracks.map { it.id })
        assertEquals("v1", h.state.selectedTrackId)
    }

    @Test
    fun `tapping a clip or an empty lane selects its track`() = runTest(dispatcher) {
        val h = harness()
        h.vm.onIntent(EditorIntent.AddTrack(TrackType.VIDEO))
        assertEquals("track-v1", h.state.selectedTrackId)

        h.select("c1")
        assertEquals("v1", h.state.selectedTrackId)

        h.vm.onIntent(EditorIntent.TapTimeline(TimelineHit(HitKind.EMPTY_TRACK, trackIndex = 0, clipKey = -1, frame = 10)))
        assertEquals("track-v1", h.state.selectedTrackId)
        assertNull(h.state.selectedClipId)
    }

    @Test
    fun `imports land on the selected track when its type fits`() = runTest(dispatcher) {
        val h = harness(importer = FakeImporter(mapOf("content://new" to video5s)))
        h.vm.onIntent(EditorIntent.AddTrack(TrackType.VIDEO))

        h.vm.onIntent(EditorIntent.ImportMedia(listOf("content://new")))
        advanceUntilIdle()

        assertEquals(1, h.clips("track-v1").size)
        assertEquals(2, h.clips("v1").size)
    }

    @Test
    fun `media of the other type ignores the selected track and uses the first track of its type`() = runTest(dispatcher) {
        val h = harness(importer = FakeImporter(mapOf("content://song" to audio2s)))
        h.vm.onIntent(EditorIntent.AddTrack(TrackType.VIDEO))

        h.vm.onIntent(EditorIntent.ImportMedia(listOf("content://song")))
        advanceUntilIdle()

        assertEquals(1, h.clips("a1").size)
    }

    @Test
    fun `an empty track can be removed but a track with clips and the last of its type cannot`() = runTest(dispatcher) {
        val h = harness()
        h.vm.onIntent(EditorIntent.AddTrack(TrackType.VIDEO))

        h.vm.onIntent(EditorIntent.RemoveSelectedTrack)
        assertEquals(listOf("v1", "a1"), h.state.timeline.tracks.map { it.id })

        // v1 is now the only video track, and it has clips.
        h.vm.onIntent(EditorIntent.RemoveSelectedTrack)
        runCurrent()
        assertEquals(listOf("v1", "a1"), h.state.timeline.tracks.map { it.id })
        assertTrue(h.effects.last() is EditorEffect.ShowMessage)
    }

    @Test
    fun `removing a track that still has clips is refused with an explanation`() = runTest(dispatcher) {
        val h = harness()
        h.vm.onIntent(EditorIntent.AddTrack(TrackType.VIDEO))
        h.select("c1")
        h.vm.onIntent(EditorIntent.AddTrack(TrackType.VIDEO))
        h.select("c1")

        h.vm.onIntent(EditorIntent.RemoveSelectedTrack)
        runCurrent()

        assertEquals(3, h.state.timeline.tracks.count { it.type == TrackType.VIDEO } + 0)
        assertTrue(h.effects.last() is EditorEffect.ShowMessage)
    }

    @Test
    fun `tracks survive a save`() = runTest(dispatcher) {
        val h = harness()
        h.vm.onIntent(EditorIntent.AddTrack(TrackType.VIDEO))
        advanceTimeBy(600)

        val saved = h.store.saved.single()

        assertEquals(listOf("track-v1", "v1", "a1"), saved.tracks.map { it.id })
        assertEquals(listOf(0, 1, 2), saved.tracks.map { it.order })
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

    // region appearance

    private fun EditorViewModel.gesture(panX: Double = 0.0, panY: Double = 0.0, zoom: Double = 1.0, rotation: Double = 0.0) =
        onIntent(EditorIntent.TransformGesture(panX, panY, zoom, rotation))

    @Test
    fun `canvas size and clip gain come from the project`() = runTest(dispatcher) {
        val h = harness()

        assertEquals(1920 to 1080, h.state.canvasWidth to h.state.canvasHeight)
        assertEquals(-6.0, h.clips("v1")[0].gainDb, 0.0)
        assertTrue(h.clips("v1")[0].transform.isIdentity)
    }

    @Test
    fun `a gesture is shown live, committed as one undo step on release and undone in one step`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")
        h.vm.onIntent(EditorIntent.SetPlayhead(10))

        h.vm.gesture(panX = 30.0, panY = -10.0)
        h.vm.gesture(zoom = 2.0, rotation = 20.0)

        val live = h.state.visibleTimeline.track("v1")!!.clip("c1")!!.transform
        assertEquals(ClipTransform(30.0, -10.0, 2.0, 2.0, 20.0), live)
        assertTrue("history is untouched until release", h.clips("v1")[0].transform.isIdentity && !h.state.canUndo)

        h.vm.onIntent(EditorIntent.EndAppearanceEdit(commit = true))

        assertEquals(live, h.clips("v1")[0].transform)
        assertNull(h.state.dragPreview)
        h.vm.onIntent(EditorIntent.Undo)
        assertTrue(h.clips("v1")[0].transform.isIdentity)
        assertFalse(h.state.canUndo)
    }

    @Test
    fun `cancelling a gesture leaves the clip untouched`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")

        h.vm.gesture(panX = 50.0)
        h.vm.onIntent(EditorIntent.EndAppearanceEdit(commit = false))

        assertTrue(h.clips("v1")[0].transform.isIdentity)
        assertNull(h.state.dragPreview)
        assertFalse(h.state.canUndo)
    }

    @Test
    fun `a gesture does nothing without a selected clip under the playhead`() = runTest(dispatcher) {
        val h = harness()

        h.vm.gesture(panX = 50.0)
        assertNull(h.state.dragPreview)

        h.select("c1")
        h.vm.onIntent(EditorIntent.SetPlayhead(150)) // inside c2, not the selected c1
        h.vm.gesture(panX = 50.0)
        assertNull(h.state.dragPreview)
        assertFalse(h.state.canUndo)
    }

    @Test
    fun `a gesture that changes nothing does not add an undo step`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")

        h.vm.gesture()
        h.vm.onIntent(EditorIntent.EndAppearanceEdit(commit = true))

        assertFalse(h.state.canUndo)
    }

    @Test
    fun `gain and transform edited from the inspector are one undo step and are saved`() = runTest(dispatcher) {
        val h = harness()
        h.select("c2")

        h.vm.onIntent(EditorIntent.BeginAppearanceEdit)
        h.vm.onIntent(EditorIntent.UpdateGain(-12.0))
        h.vm.onIntent(EditorIntent.UpdateTransform(ClipTransform(scaleX = 0.5, scaleY = 0.5, opacity = 0.4)))
        h.vm.onIntent(EditorIntent.EndAppearanceEdit(commit = true))
        advanceTimeBy(600)

        val clip = h.clips("v1")[1]
        assertEquals(-12.0, clip.gainDb, 0.0)
        assertEquals(0.4, clip.transform.opacity, 0.0)
        val saved = h.store.saved.last().tracks.first { it.id == "v1" }.clips.first { it.id == "c2" }
        assertEquals(-12.0, saved.gainDb, 0.0)
        assertEquals(listOf(0.5, 0.5), saved.transform.scale)
        assertEquals(0.4, saved.transform.opacity, 0.0)
        h.vm.onIntent(EditorIntent.Undo)
        assertEquals(0.0, h.clips("v1")[1].gainDb, 0.0)
        assertFalse(h.state.canUndo)
    }

    @Test
    fun `values that cannot be used are refused with a message`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")

        h.vm.onIntent(EditorIntent.UpdateTransform(ClipTransform(scaleX = 0.0)))
        h.vm.onIntent(EditorIntent.UpdateGain(500.0))

        assertEquals(2, h.effects.filterIsInstance<EditorEffect.ShowMessage>().size)
        assertNull(h.state.dragPreview)
    }

    @Test
    fun `editing the look needs a selected clip`() = runTest(dispatcher) {
        val h = harness()

        h.vm.onIntent(EditorIntent.BeginAppearanceEdit)

        assertEquals("Select a clip first", (h.effects.single() as EditorEffect.ShowMessage).text)
    }

    @Test
    fun `reset restores identity and unity gain in one undo step`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")
        h.vm.onIntent(EditorIntent.UpdateTransform(ClipTransform(positionX = 99.0)))
        h.vm.onIntent(EditorIntent.EndAppearanceEdit(commit = true))

        h.vm.onIntent(EditorIntent.ResetAppearance)

        assertTrue(h.clips("v1")[0].transform.isIdentity)
        assertEquals(0.0, h.clips("v1")[0].gainDb, 0.0)
        h.vm.onIntent(EditorIntent.Undo)
        assertEquals(99.0, h.clips("v1")[0].transform.positionX, 0.0)
    }

    @Test
    fun `closing the inspector commits an edit in progress`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")
        h.vm.onIntent(EditorIntent.ToggleInspector)
        assertTrue(h.state.inspectorOpen)

        h.vm.onIntent(EditorIntent.UpdateGain(3.0))
        h.vm.onIntent(EditorIntent.ToggleInspector)

        assertFalse(h.state.inspectorOpen)
        assertEquals(3.0, h.clips("v1")[0].gainDb, 0.0)
    }

    @Test
    fun `an edit in progress is dropped when the timeline changes under it`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")
        h.vm.onIntent(EditorIntent.SetPlayhead(10))
        h.vm.gesture(panX = 10.0)

        h.vm.onIntent(EditorIntent.SplitAtPlayhead)
        h.vm.onIntent(EditorIntent.EndAppearanceEdit(commit = true))

        assertTrue(h.clips("v1").all { it.transform.isIdentity })
    }

    // endregion
}
