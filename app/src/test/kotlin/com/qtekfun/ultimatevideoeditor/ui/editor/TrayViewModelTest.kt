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
import com.qtekfun.ultimatevideoeditor.data.model.TrackDto
import com.qtekfun.ultimatevideoeditor.domain.Clip
import com.qtekfun.ultimatevideoeditor.domain.DropKind
import androidx.compose.ui.geometry.Offset
import com.qtekfun.ultimatevideoeditor.engine.timeline.HitKind
import com.qtekfun.ultimatevideoeditor.engine.timeline.TimelineHit
import com.qtekfun.ultimatevideoeditor.ui.editor.tray.AssetKind
import com.qtekfun.ultimatevideoeditor.ui.editor.tray.RootBounds
import com.qtekfun.ultimatevideoeditor.ui.editor.tray.TrayCarry
import com.qtekfun.ultimatevideoeditor.ui.editor.tray.TrayDragController
import com.qtekfun.ultimatevideoeditor.ui.editor.tray.TrayDragSink
import com.qtekfun.ultimatevideoeditor.ui.editor.tray.TrayPointer
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Dragging media from the tray (or from another app) onto the timeline, and the tray's own library edits. */
@OptIn(ExperimentalCoroutinesApi::class)
class TrayViewModelTest {

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

    private class FakeImporter(val media: Map<String, ProbedMedia>) : MediaImporter {
        override suspend fun import(uri: String): ProbedMedia = media[uri] ?: throw MediaImportException("Unsupported file")
    }

    private val settings = ProjectSettingsDto(1920, 1080, 30, 1, "Rec709-SDR")
    private val video = MediaAssetDto("vid", "content://m/vid", 300, 30, 1, "Rec709-SDR", hasVideo = true, hasAudio = false)
    private val sound = MediaAssetDto("snd", "content://m/snd", 240, 30, 1, "Rec709-SDR", hasVideo = false, hasAudio = true)
    private val newVideo = ProbedMedia(10_000_000, 30, 1, "Rec709-SDR", hasVideo = true, hasAudio = false)
    private val newAudio = ProbedMedia(4_000_000, 0, 0, "Rec709-SDR", hasVideo = false, hasAudio = true)

    // Lane indexes: 0 = v2 (overlay), 1 = v1 (base), 2 = a1.
    private fun project() = ProjectDto(
        id = "p1", name = "T", settings = settings, mediaLibrary = listOf(video, sound),
        tracks = listOf(
            TrackDto("v2", "video", 0, listOf(ClipDto("o1", "vid", 300, 0, 60))),
            TrackDto("v1", "video", 1, listOf(ClipDto("c1", "vid", 0, 0, 100), ClipDto("c2", "vid", 100, 0, 100))),
            TrackDto("a1", "audio", 2),
        ),
    )

    private class Harness(val vm: EditorViewModel, val store: FakeStore, val effects: MutableList<EditorEffect>) {
        val state get() = vm.state.value
        fun clips(trackId: String): List<Clip> = state.timeline.track(trackId)?.clips.orEmpty()
        fun layout(trackId: String) = clips(trackId).map { Triple(it.id, it.timelineStart.value, it.timelineEnd.value) }
    }

    /** The project's own files are readable (an unknown file would be flagged missing at load). */
    private fun known(vararg extra: Pair<String, ProbedMedia>) = FakeImporter(
        mapOf(
            "content://m/vid" to ProbedMedia(10_000_000, 30, 1, "Rec709-SDR", hasVideo = true, hasAudio = false),
            "content://m/snd" to ProbedMedia(8_000_000, 0, 0, "Rec709-SDR", hasVideo = false, hasAudio = true),
        ) + extra,
    )

    private fun TestScope.harness(importer: MediaImporter = known()): Harness {
        var counter = 0
        val store = FakeStore(project())
        val vm = EditorViewModel("p1", store, importer, idGenerator = { "n${counter++}" })
        val effects = mutableListOf<EditorEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { effects += it } }
        advanceUntilIdle()
        return Harness(vm, store, effects)
    }

    private fun Harness.move(frame: Long, lane: Int, zone: DragZone = DragZone.LANES) =
        vm.onIntent(EditorIntent.TrayDragMove(frame, lane, zone))

    // region dragging an asset from the tray

    @Test
    fun `near a cut on the base the indicator shows an insert and a release inserts and selects the clip`() = runTest(dispatcher) {
        val h = harness()
        h.vm.onIntent(EditorIntent.TrayDragStart("vid"))
        h.move(104, lane = 1)

        assertEquals(DropKind.INSERT, h.state.dropHint?.kind)
        assertEquals("v1", h.state.dropHint?.trackId)
        assertEquals(100L, h.state.dropHint?.startFrame)

        h.vm.onIntent(EditorIntent.TrayDragEnd(commit = true))

        val base = h.clips("v1")
        assertEquals(3, base.size)
        assertEquals(100L, base[1].timelineStart.value)
        assertEquals(300L, base[1].durationFrames)
        assertEquals(400L, base[2].timelineStart.value)
        assertEquals(base[1].id, h.state.selectedClipId)
        assertNull(h.state.dropHint)
        assertEquals(emptyList<String>(), h.state.timeline.invariantViolations())

        h.vm.onIntent(EditorIntent.Undo)
        assertEquals(2, h.clips("v1").size)
    }

    @Test
    fun `over the body of a base clip the indicator shows an overwrite`() = runTest(dispatcher) {
        val h = harness()
        h.vm.onIntent(EditorIntent.TrayDragStart("vid"))
        h.move(150, lane = 1)

        assertEquals(DropKind.OVERWRITE, h.state.dropHint?.kind)
        h.vm.onIntent(EditorIntent.TrayDragEnd(commit = true))

        assertEquals(emptyList<String>(), h.state.timeline.invariantViolations())
        assertTrue(h.clips("v1").any { it.timelineStart.value == 150L && it.assetId == "vid" })
    }

    @Test
    fun `free space on an overlay lane is shown as the range the clip will cover and places the clip`() = runTest(dispatcher) {
        val h = harness()
        h.vm.onIntent(EditorIntent.TrayDragStart("vid"))
        h.move(20, lane = 0)

        assertEquals(DropKind.OVERWRITE, h.state.dropHint?.kind)
        assertEquals("v2", h.state.dropHint?.trackId)
        assertEquals(20L, h.state.dropHint?.startFrame)
        assertEquals(320L, h.state.dropHint?.endFrame)

        h.vm.onIntent(EditorIntent.TrayDragEnd(commit = true))
        assertTrue(h.clips("v2").any { it.timelineStart.value == 20L && it.assetId == "vid" })
    }

    @Test
    fun `above the lanes a new lane is shown and created on release in one undo step`() = runTest(dispatcher) {
        val h = harness()
        h.vm.onIntent(EditorIntent.TrayDragStart("vid"))
        h.move(40, lane = -1, zone = DragZone.ABOVE_LANES)

        assertEquals(DropKind.NEW_LANE, h.state.dropHint?.kind)
        // The canvas draws the placeholder on a lane of the timeline it shows, so one is in the preview.
        val laneId = checkNotNull(h.state.dropHint?.trackId)
        assertNotNull(h.state.dragPreview?.track(laneId))
        assertEquals(3, h.state.timeline.tracks.size)

        h.vm.onIntent(EditorIntent.TrayDragEnd(commit = true))
        assertEquals(4, h.state.timeline.tracks.size)
        val top = h.state.timeline.tracks.first()
        assertEquals(listOf("vid"), top.clips.map { it.assetId })
        assertEquals(40L, top.clips.single().timelineStart.value)

        h.vm.onIntent(EditorIntent.Undo)
        assertEquals(3, h.state.timeline.tracks.size)
    }

    @Test
    fun `the new lane stays open when the row it adds shifts the finger onto an existing lane`() = runTest(dispatcher) {
        val h = harness()
        h.vm.onIntent(EditorIntent.TrayDragStart("vid"))
        h.move(40, lane = -1, zone = DragZone.ABOVE_LANES)
        assertEquals(DropKind.NEW_LANE, h.state.dropHint?.kind)

        // The shown timeline now has the provisional lane first, so the same spot hits v2 at index 1, then jitters.
        repeat(3) {
            h.move(40, lane = 1)
            assertEquals(DropKind.NEW_LANE, h.state.dropHint?.kind)
            h.move(42, lane = 0)
            assertEquals(DropKind.NEW_LANE, h.state.dropHint?.kind)
            h.move(40, lane = -1, zone = DragZone.ABOVE_LANES)
            assertEquals(DropKind.NEW_LANE, h.state.dropHint?.kind)
        }

        h.vm.onIntent(EditorIntent.TrayDragEnd(commit = true))
        assertEquals(4, h.state.timeline.tracks.size)
        assertEquals(listOf("vid"), h.state.timeline.tracks.first().clips.map { it.assetId })
    }

    @Test
    fun `an audio asset cancels over a video lane and lands on an audio lane`() = runTest(dispatcher) {
        val h = harness()
        h.vm.onIntent(EditorIntent.TrayDragStart("snd"))
        h.move(50, lane = 1)
        assertEquals(DropKind.CANCEL, h.state.dropHint?.kind)
        h.vm.onIntent(EditorIntent.TrayDragEnd(commit = true))
        assertEquals(2, h.clips("v1").size)
        assertTrue(h.clips("a1").isEmpty())
        assertTrue(h.state.timeline.tracks.sumOf { it.clips.size } == 3)

        h.vm.onIntent(EditorIntent.TrayDragStart("snd"))
        h.move(50, lane = 2)
        h.vm.onIntent(EditorIntent.TrayDragEnd(commit = true))
        assertEquals(listOf(Triple(h.clips("a1")[0].id, 50L, 290L)), h.layout("a1"))
    }

    @Test
    fun `leaving the canvas hides the indicator, coming back shows it, and a cancel changes nothing`() = runTest(dispatcher) {
        val h = harness()
        val before = h.state.timeline
        h.vm.onIntent(EditorIntent.TrayDragStart("vid"))
        h.move(104, lane = 1)
        assertNotNull(h.state.dropHint)

        h.vm.onIntent(EditorIntent.TrayDragLeave)
        assertNull(h.state.dropHint)
        h.move(104, lane = 1)
        assertEquals(DropKind.INSERT, h.state.dropHint?.kind)

        h.vm.onIntent(EditorIntent.TrayDragEnd(commit = false))
        assertNull(h.state.dropHint)
        assertEquals(before, h.state.timeline)
        // With the session gone, a release is ignored.
        h.vm.onIntent(EditorIntent.TrayDragEnd(commit = true))
        assertEquals(before, h.state.timeline)
    }

    @Test
    fun `dragging off every lane cancels and a release does nothing`() = runTest(dispatcher) {
        val h = harness()
        val before = h.state.timeline
        h.vm.onIntent(EditorIntent.TrayDragStart("vid"))
        h.move(10, lane = -1, zone = DragZone.OUTSIDE)
        assertEquals(DropKind.CANCEL, h.state.dropHint?.kind)
        h.vm.onIntent(EditorIntent.TrayDragEnd(commit = true))
        assertEquals(before, h.state.timeline)
    }

    @Test
    fun `a carried asset whose start is pulled onto a clip edge shows the snap line, and leaving or releasing clears it`() = runTest(dispatcher) {
        val h = harness()
        h.vm.onIntent(EditorIntent.TrayDragStart("snd"))
        // The clip edge of the overlay clip at frame 300 is a target; 2 frames short of it the audio start snaps there.
        h.move(298, lane = 2)
        assertEquals(DropKind.OVERWRITE, h.state.dropHint?.kind)
        assertEquals(300L, h.state.dropHint?.startFrame)
        assertEquals(300L, h.state.dragOverlay?.guideFrame)
        assertEquals(emptyList<String>(), h.state.dragOverlay?.clipIds)

        // Well away from every edge: no snap, no line.
        h.move(150, lane = 2)
        assertNull(h.state.dragOverlay)

        h.move(298, lane = 2)
        assertNotNull(h.state.dragOverlay)
        h.vm.onIntent(EditorIntent.TrayDragLeave)
        assertNull(h.state.dragOverlay)

        h.move(298, lane = 2)
        h.vm.onIntent(EditorIntent.TrayDragEnd(commit = true))
        assertNull(h.state.dragOverlay)
        assertEquals(300L, h.clips("a1").single().timelineStart.value)
    }

    @Test
    fun `an insert on the base has no snap line`() = runTest(dispatcher) {
        val h = harness()
        h.vm.onIntent(EditorIntent.TrayDragStart("vid"))
        h.move(104, lane = 1)
        assertEquals(DropKind.INSERT, h.state.dropHint?.kind)
        assertNull(h.state.dragOverlay)
    }

    // The whole chain of a tile drag below the gesture: root pointer samples -> controller -> TimelineTrayDrop -> intents -> editor.
    private fun Harness.carryTo(hit: (Float, Float) -> TimelineHit, bounds: RootBounds?, path: List<Pair<Float, Float>>, lift: Boolean = true): TrayDragController {
        val drop = TimelineTrayDrop(hit, { _, _ -> }, vm::onIntent, density = 1f)
        drop.bounds = bounds
        val controller = TrayDragController()
        controller.sink = drop
        controller.logger = { _, e -> throw e }
        controller.pickUp(TrayCarry("vid", "vid", "", AssetKind.VIDEO, null, Offset(path.first().first, path.first().second), Offset.Zero), 7L)
        for ((x, y) in path) assertTrue(controller.onPointerEvent(listOf(TrayPointer(7L, x, y, pressed = true, newDown = false))))
        if (lift) controller.onPointerEvent(listOf(TrayPointer(7L, path.last().first, path.last().second, pressed = false, newDown = false)))
        return controller
    }

    private val timelineBounds = RootBounds(0f, 100f, 1000f, 700f)

    /** The same chain for a file of a linked folder, which is not in the library until it is released over the timeline. */
    private fun Harness.carryFileTo(uri: String, hit: (Float, Float) -> TimelineHit, bounds: RootBounds?, path: List<Pair<Float, Float>>): TrayDragController {
        val drop = TimelineTrayDrop(hit, { _, _ -> }, vm::onIntent, density = 1f)
        drop.bounds = bounds
        val controller = TrayDragController()
        controller.sink = drop
        controller.logger = { _, e -> throw e }
        controller.pickUp(TrayCarry(uri, "clip", "0:10", AssetKind.VIDEO, null, Offset(path.first().first, path.first().second), Offset.Zero, fileUri = uri), 7L)
        for ((x, y) in path) assertTrue(controller.onPointerEvent(listOf(TrayPointer(7L, x, y, pressed = true, newDown = false))))
        controller.onPointerEvent(listOf(TrayPointer(7L, path.last().first, path.last().second, pressed = false, newDown = false)))
        return controller
    }

    @Test
    fun `a file of a linked folder released over the timeline joins the project and lands where it was dropped`() = runTest(dispatcher) {
        val inFolder = "content://m/tree/root/document/shot"
        val h = harness(known(inFolder to newVideo))
        val c = h.carryFileTo(inFolder, { x, _ -> TimelineHit(HitKind.CLIP, 1, 0L, x.toLong()) }, timelineBounds, listOf(50f to 400f, 104f to 400f))
        advanceUntilIdle()

        assertNull(c.carry)
        val asset = h.state.assets.single { it.uri == inFolder }
        assertEquals(3, h.clips("v1").size)
        assertTrue(h.clips("v1").any { it.assetId == asset.id })
        assertEquals(emptyList<String>(), h.state.timeline.invariantViolations())
        assertNull(h.state.dropHint)
    }

    @Test
    fun `a file of a linked folder released outside the timeline adds nothing and the ghost ends`() = runTest(dispatcher) {
        val inFolder = "content://m/tree/root/document/shot"
        val h = harness(known(inFolder to newVideo))
        val before = h.state.timeline
        val c = h.carryFileTo(inFolder, { x, _ -> TimelineHit(HitKind.CLIP, 1, 0L, x.toLong()) }, timelineBounds, listOf(50f to 50f))
        advanceUntilIdle()

        assertTrue(c.carry?.returning == true)
        assertEquals(2, h.state.assets.size)
        assertEquals(before, h.state.timeline)
        assertNull(h.state.dropHint)
    }

    @Test
    fun `a file of a linked folder is never reordered in the tray`() {
        val controller = TrayDragController()
        var reordered = false
        controller.onReorder = { _, _ -> reordered = true }
        controller.sink = object : TrayDragSink {
            override fun begin(assetId: String) = Unit
            override fun move(x: Float, y: Float) = Unit
            override fun drop(x: Float, y: Float) = false
            override fun cancel() = Unit
            override fun frame(nowMs: Long) = Unit
        }
        controller.pickUp(TrayCarry("u", "u", "", AssetKind.VIDEO, null, Offset(1f, 1f), Offset(5f, 5f), fileUri = "u"), 7L)
        controller.onPointerEvent(listOf(TrayPointer(7L, 1f, 1f, pressed = false, newDown = false)))
        assertEquals(false, reordered)
        assertTrue(controller.carry?.returning == true)
    }

    @Test
    fun `lifting the finger over the base cut inserts the carried asset and clears the ghost`() = runTest(dispatcher) {
        val h = harness()
                val c = h.carryTo({ x, _ -> TimelineHit(HitKind.CLIP, 1, 0L, x.toLong()) }, timelineBounds, listOf(50f to 400f, 104f to 400f))
        assertNull(c.carry)
        assertEquals(3, h.clips("v1").size)
        assertEquals(100L, h.clips("v1")[1].timelineStart.value)
        assertNull(h.state.dropHint)
    }

    @Test
    fun `lifting over free overlay space overwrites there`() = runTest(dispatcher) {
        val h = harness()
        val c = h.carryTo({ x, _ -> TimelineHit(HitKind.EMPTY_TRACK, 0, 0L, x.toLong()) }, timelineBounds, listOf(150f to 300f))
        assertNull(c.carry)
        assertEquals(listOf(150L), h.clips("v2").map { it.timelineStart.value }.filter { it == 150L })
        assertEquals(emptyList<String>(), h.state.timeline.invariantViolations())
    }

    @Test
    fun `lifting outside the timeline places nothing and the ghost still ends`() = runTest(dispatcher) {
        val h = harness()
        val before = h.state.timeline
        val c = h.carryTo({ x, _ -> TimelineHit(HitKind.CLIP, 1, 0L, x.toLong()) }, timelineBounds, listOf(500f to 50f))
        assertEquals(before, h.state.timeline)
        assertTrue(c.carry?.returning == true)
        c.finishReturn()
        assertNull(c.carry)
        assertNull(h.state.dropHint)
    }

    @Test
    fun `a timeline whose bounds are not known yet cancels instead of sticking`() = runTest(dispatcher) {
        val h = harness()
        val before = h.state.timeline
        val c = h.carryTo({ x, _ -> TimelineHit(HitKind.CLIP, 1, 0L, x.toLong()) }, null, listOf(104f to 400f))
        assertEquals(before, h.state.timeline)
        c.finishReturn()
        assertNull(c.carry)
    }

    @Test
    fun `an unknown asset cannot be dragged`() = runTest(dispatcher) {
        val h = harness()
        h.vm.onIntent(EditorIntent.TrayDragStart("nope"))
        h.move(104, lane = 1)
        assertNull(h.state.dropHint)
        assertTrue(h.effects.any { it is EditorEffect.ShowMessage })
    }

    // endregion

    // region files from other apps

    @Test
    fun `files dropped from another app are imported and placed where they landed`() = runTest(dispatcher) {
        val h = harness(known("content://ext/new" to newVideo))
        h.vm.onIntent(EditorIntent.ExternalDragStart(listOf(AssetKind.VIDEO)))
        h.move(104, lane = 1)
        assertEquals(DropKind.INSERT, h.state.dropHint?.kind)

        h.vm.onIntent(EditorIntent.ExternalDrop(listOf("content://ext/new"), 104, 1))
        advanceUntilIdle()

        assertNull(h.state.dropHint)
        val asset = h.state.assets.single { it.uri == "content://ext/new" }
        assertEquals(300L, asset.durationFrames)
        val base = h.clips("v1")
        assertEquals(3, base.size)
        assertEquals(asset.id, base[1].assetId)
        assertEquals(100L, base[1].timelineStart.value)
        assertEquals(emptyList<String>(), h.state.timeline.invariantViolations())
        h.vm.onIntent(EditorIntent.Undo)
        assertEquals(2, h.clips("v1").size)
    }

    @Test
    fun `an audio file dropped on a video lane is imported but not placed, with a message`() = runTest(dispatcher) {
        val h = harness(known("content://ext/song" to newAudio))
        val before = h.state.timeline
        h.vm.onIntent(EditorIntent.ExternalDrop(listOf("content://ext/song"), 50, 1))
        advanceUntilIdle()

        assertEquals(before, h.state.timeline)
        assertTrue(h.state.assets.any { it.uri == "content://ext/song" })
        assertTrue(h.effects.any { it is EditorEffect.ShowMessage })
    }

    @Test
    fun `an unreadable dropped file reports an error and the rest still go in`() = runTest(dispatcher) {
        val h = harness(known("content://ext/new" to newVideo))
        h.vm.onIntent(EditorIntent.ExternalDrop(listOf("content://ext/bad", "content://ext/new"), 104, 1))
        advanceUntilIdle()

        assertTrue(h.effects.any { it is EditorEffect.ShowMessage })
        assertTrue(h.state.assets.any { it.uri == "content://ext/new" })
        assertTrue(h.clips("v1").any { it.assetId == h.state.assets.first { a -> a.uri == "content://ext/new" }.id })
    }

    // endregion

    // region the library

    @Test
    fun `importing to the tray adds the asset without touching the timeline`() = runTest(dispatcher) {
        val h = harness(known("content://ext/new" to newVideo))
        val before = h.state.timeline
        h.vm.onIntent(EditorIntent.ImportToTray(listOf("content://ext/new")))
        advanceUntilIdle()

        assertEquals(3, h.state.assets.size)
        assertEquals(before, h.state.timeline)
        assertEquals(false, h.state.isImporting)
    }

    @Test
    fun `a file reached through a linked folder is the asset the project already has`() = runTest(dispatcher) {
        // The importer knows only the picker's URI of the file, so a second asset would fail: the file is found by what the provider calls it.
        val h = harness(known("content://m/document/ext" to newVideo))
        h.vm.onIntent(EditorIntent.ImportToTray(listOf("content://m/document/ext")))
        advanceUntilIdle()
        val messagesBefore = h.effects.size
        val ids = h.state.assets.map { it.id }

        h.vm.onIntent(EditorIntent.ImportToTray(listOf("content://m/tree/root/document/ext")))
        advanceUntilIdle()

        assertEquals(ids, h.state.assets.map { it.id })
        assertEquals(3, h.state.assets.size)
        assertEquals(messagesBefore, h.effects.size)
    }

    @Test
    fun `a new file from a linked folder joins the project once, however many times it is added`() = runTest(dispatcher) {
        val inFolder = "content://m/tree/root/document/new"
        val h = harness(known(inFolder to newVideo))
        h.vm.onIntent(EditorIntent.ImportMedia(listOf(inFolder)))
        advanceUntilIdle()
        // The same file by the picker's URI is not known to the importer: it must be found as the asset already there.
        h.vm.onIntent(EditorIntent.ImportMedia(listOf("content://m/document/new", inFolder)))
        advanceUntilIdle()

        assertEquals(1, h.state.assets.count { it.uri == inFolder })
        assertEquals(3, h.state.assets.size)
    }

    @Test
    fun `adding to the end puts the files after everything already on the timeline`() = runTest(dispatcher) {
        val inFolder = "content://m/tree/root/document/tail"
        val h = harness(known(inFolder to newVideo))
        val end = h.state.timeline.tracks.maxOf { it.end }.value
        h.vm.onIntent(EditorIntent.ImportMedia(listOf(inFolder), atEnd = true))
        advanceUntilIdle()

        val asset = h.state.assets.single { it.uri == inFolder }
        val placed = h.state.timeline.tracks.flatMap { it.clips }.single { it.assetId == asset.id }
        assertEquals(end, placed.timelineStart.value)
        assertEquals(emptyList<String>(), h.state.timeline.invariantViolations())
    }

    private val voiceTake = ProbedMedia(4_000_000, 0, 0, "Rec709-SDR", hasVideo = false, hasAudio = true)

    @Test
    fun `a voice-over take goes on its own new audio lane when the existing one has sound under it, in one undo step`() = runTest(dispatcher) {
        val take = "file:///voice/take1.wav"
        val h = harness(known(take to voiceTake))
        // The project's a1 lane is empty; put sound on it where the voice will go.
        h.vm.onIntent(EditorIntent.TrayDragStart("snd"))
        h.vm.onIntent(EditorIntent.TrayDragMove(10, 2))
        h.vm.onIntent(EditorIntent.TrayDragEnd(commit = true))
        val music = h.clips("a1").single()
        val lanes = h.state.timeline.tracks.size

        h.vm.onIntent(EditorIntent.PlaceVoiceOver(take, startFrame = 20, trimMs = 0))
        advanceUntilIdle()

        assertEquals(lanes + 1, h.state.timeline.tracks.size)
        // The music is untouched.
        assertEquals(listOf(music), h.clips("a1"))
        val placed = h.state.timeline.tracks.last().clips.single()
        assertEquals(20L, placed.timelineStart.value)
        assertEquals(0L, placed.sourceIn.value)
        assertEquals(h.state.assets.single { it.uri == take }.id, placed.assetId)
        assertEquals(placed.id, h.state.selectedClipId)
        assertEquals(emptyList<String>(), h.state.timeline.invariantViolations())
        // The recorder panel is told, so it can offer Undo and Record again.
        assertTrue(h.effects.contains(EditorEffect.VoiceOverPlaced))

        h.vm.onIntent(EditorIntent.Undo)
        assertEquals(lanes, h.state.timeline.tracks.size)
    }

    @Test
    fun `a voice-over take uses the free audio lane and cuts the lead off its start`() = runTest(dispatcher) {
        val take = "file:///voice/take2.wav"
        val h = harness(known(take to voiceTake))
        val lanes = h.state.timeline.tracks.size

        h.vm.onIntent(EditorIntent.PlaceVoiceOver(take, startFrame = 50, trimMs = 200))
        advanceUntilIdle()

        assertEquals(lanes, h.state.timeline.tracks.size)
        val placed = h.clips("a1").single()
        assertEquals(50L, placed.timelineStart.value)
        // 200 ms at 30 fps.
        assertEquals(6L, placed.sourceIn.value)
        assertEquals(placed.sourceOut.value - 6, placed.durationFrames)
    }

    @Test
    fun `a voice-over take that cannot be read adds nothing and says so`() = runTest(dispatcher) {
        val h = harness()
        val before = h.state.timeline
        val messages = h.effects.size
        h.vm.onIntent(EditorIntent.PlaceVoiceOver("file:///voice/missing.wav", 0, 0))
        advanceUntilIdle()
        assertEquals(before, h.state.timeline)
        assertEquals(messages + 1, h.effects.size)
    }

    @Test
    fun `adding at the playhead is unchanged`() = runTest(dispatcher) {
        val inFolder = "content://m/tree/root/document/here"
        val h = harness(known(inFolder to newVideo))
        h.vm.onIntent(EditorIntent.SetPlayhead(40))
        h.vm.onIntent(EditorIntent.ImportMedia(listOf(inFolder)))
        advanceUntilIdle()
        val asset = h.state.assets.single { it.uri == inFolder }
        val placed = h.state.timeline.tracks.flatMap { it.clips }.single { it.assetId == asset.id }
        assertEquals(40L, placed.timelineStart.value)
    }

    @Test
    fun `reordering moves an asset within the library and saves the new order`() = runTest(dispatcher) {
        val h = harness()
        h.vm.onIntent(EditorIntent.ReorderAsset("snd", 0))
        assertEquals(listOf("snd", "vid"), h.state.assets.map { it.id })

        h.vm.onIntent(EditorIntent.Flush)
        advanceUntilIdle()
        assertEquals(listOf("snd", "vid"), h.store.project?.mediaLibrary?.map { it.id })

        // Out-of-range and unknown moves change nothing.
        h.vm.onIntent(EditorIntent.ReorderAsset("snd", 0))
        h.vm.onIntent(EditorIntent.ReorderAsset("missing", 1))
        assertEquals(listOf("snd", "vid"), h.state.assets.map { it.id })
    }

    // endregion
}
