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
import com.ultimatevideo.uveditor.domain.AlignEdge
import com.ultimatevideo.uveditor.domain.Clip
import com.ultimatevideo.uveditor.domain.DropKind
import com.ultimatevideo.uveditor.domain.GroupTransition
import com.ultimatevideo.uveditor.engine.timeline.HitKind
import com.ultimatevideo.uveditor.engine.timeline.TimelineHit
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
class SelectionViewModelTest {
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

    private object NoImporter : MediaImporter {
        override suspend fun import(uri: String): ProbedMedia = throw MediaImportException("Unsupported file")
    }

    private val settings = ProjectSettingsDto(1920, 1080, 30, 1, "Rec709-SDR")
    private val asset = MediaAssetDto("a1", "content://m/a1", durationFrames = 600, nativeFpsNum = 30, nativeFpsDen = 1, colorSpace = "Rec709-SDR")

    // sourceIn 50 so the clips have media before their in point and can take crossfades.
    private fun clipDto(id: String, start: Long, len: Long = 100) =
        ClipDto(id, "a1", timelineStartFrame = start, sourceInFrame = 50, sourceOutFrame = 50 + len)

    /** Overlay v2 (x 0..100, y 100..200, z 300..400) over the base v1 (c1, c2, c3 of 100 frames each). */
    private fun project() = ProjectDto(
        id = "p1",
        name = "Test",
        settings = settings,
        mediaLibrary = listOf(asset),
        tracks = listOf(
            TrackDto("v2", "video", 0, listOf(clipDto("x", 0), clipDto("y", 100), clipDto("z", 300))),
            TrackDto("v1", "video", 1, listOf(clipDto("c1", 0), clipDto("c2", 100), clipDto("c3", 200))),
            TrackDto("a1", "audio", 2),
        ),
    )

    private class Harness(val vm: EditorViewModel, val effects: MutableList<EditorEffect>) {
        val state get() = vm.state.value
        val timeline get() = state.timeline
        fun clips(trackId: String): List<Clip> = timeline.track(trackId)?.clips.orEmpty()
        fun starts(trackId: String): List<Long> = clips(trackId).map { it.timelineStart.value }
        fun messages(): List<String> = effects.filterIsInstance<EditorEffect.ShowMessage>().map { it.text }

        fun hit(clipId: String, frame: Long = 5, kind: HitKind = HitKind.CLIP, track: Int = 0) = TimelineHit(kind, track, keyOf(clipId), frame)

        // Keys are assigned lazily by snapshotOf, so touch every clip once before looking one up.
        fun keyOf(clipId: String): Long {
            val ordered = state.visibleTimeline.tracks.flatMap { it.clips }.map { it.id }
            return vm.snapshotOf(state).clips[ordered.indexOf(clipId)].clipKey
        }

        fun tap(clipId: String) = vm.onIntent(EditorIntent.TapTimeline(hit(clipId)))
        fun longPress(clipId: String) = vm.onIntent(SelectionIntent.LongPress(hit(clipId)))
        fun select(vararg ids: String) {
            tap(ids.first())
            ids.drop(1).forEach { longPress(it) }
        }
    }

    private fun TestScope.harness(): Harness {
        var counter = 0
        val vm = EditorViewModel("p1", FakeStore(project()), NoImporter, idGenerator = { "n${counter++}" })
        val effects = mutableListOf<EditorEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { effects += it } }
        advanceUntilIdle()
        return Harness(vm, effects)
    }

    // region selecting

    @Test
    fun `a long press adds clips, the last one becomes primary, and a tap goes back to one clip`() = runTest(dispatcher) {
        val h = harness()
        h.select("x", "y")
        assertEquals(setOf("x", "y"), h.state.selection)
        assertEquals("y", h.state.selectedClipId)
        assertTrue(h.state.isMultiSelection)
        // Long pressing a selected clip takes it out again.
        h.longPress("y")
        assertEquals(setOf("x"), h.state.selection)
        assertEquals("x", h.state.selectedClipId)
        assertFalse(h.state.isMultiSelection)
        // A plain tap on another clip replaces the selection.
        h.select("x", "y")
        h.tap("z")
        assertEquals(setOf("z"), h.state.selection)
        // A long press away from a clip does nothing.
        h.vm.onIntent(SelectionIntent.LongPress(TimelineHit(HitKind.EMPTY_TRACK, 0, -1, 50)))
        assertEquals(setOf("z"), h.state.selection)
    }

    @Test
    fun `a plain tap on a clip of the group goes back to just that clip and a cleared group stays cleared`() = runTest(dispatcher) {
        val h = harness()
        h.select("x", "y", "z")
        h.tap("y")
        assertEquals(setOf("y"), h.state.selection)
        assertFalse(h.state.isMultiSelection)
        h.select("x", "y")
        h.vm.onIntent(EditorIntent.TapTimeline(TimelineHit(HitKind.EMPTY_TRACK, 0, -1, 250)))
        assertTrue(h.state.selection.isEmpty())
        // The old group must not come back when one of its clips is tapped later.
        h.tap("x")
        assertEquals(setOf("x"), h.state.selection)
        h.select("x", "y")
        h.vm.onIntent(EditorIntent.TapTimeline(TimelineHit(HitKind.NONE, -1, -1, 250)))
        h.tap("y")
        assertEquals(setOf("y"), h.state.selection)
    }

    @Test
    fun `select mode makes taps toggle clips and keeps the selection on empty space`() = runTest(dispatcher) {
        val h = harness()
        h.vm.onIntent(SelectionIntent.ToggleSelectMode)
        assertTrue(h.state.selectMode)
        h.tap("x")
        h.tap("y")
        h.tap("c2")
        assertEquals(setOf("x", "y", "c2"), h.state.selection)
        h.tap("y")
        assertEquals(setOf("x", "c2"), h.state.selection)
        h.vm.onIntent(EditorIntent.TapTimeline(TimelineHit(HitKind.EMPTY_TRACK, 0, -1, 250)))
        assertEquals(setOf("x", "c2"), h.state.selection)
        assertEquals("v2", h.state.selectedTrackId)
        h.vm.onIntent(SelectionIntent.ToggleSelectMode)
        assertFalse(h.state.selectMode)
        // Out of select mode an empty tap clears the selection as before.
        h.vm.onIntent(EditorIntent.TapTimeline(TimelineHit(HitKind.EMPTY_TRACK, 0, -1, 250)))
        assertTrue(h.state.selection.isEmpty())
    }

    @Test
    fun `a marquee adds the clips inside it and ignores unknown keys`() = runTest(dispatcher) {
        val h = harness()
        h.tap("c1")
        h.vm.onIntent(SelectionIntent.Marquee(listOf(h.keyOf("x"), h.keyOf("y"), 987654321L)))
        assertEquals(setOf("c1", "x", "y"), h.state.selection)
        assertEquals("y", h.state.selectedClipId)
        h.vm.onIntent(SelectionIntent.Marquee(listOf(987654321L)))
        assertEquals(setOf("c1", "x", "y"), h.state.selection)
    }

    @Test
    fun `select lane, from playhead, all and clear`() = runTest(dispatcher) {
        val h = harness()
        h.tap("y")
        h.vm.onIntent(SelectionIntent.SelectLane)
        assertEquals(setOf("x", "y", "z"), h.state.selection)
        h.vm.onIntent(EditorIntent.SetPlayhead(150))
        h.vm.onIntent(SelectionIntent.SelectFromPlayhead)
        assertEquals(setOf("y", "z", "c2", "c3"), h.state.selection)
        h.vm.onIntent(SelectionIntent.SelectAll)
        assertEquals(setOf("x", "y", "z", "c1", "c2", "c3"), h.state.selection)
        h.vm.onIntent(SelectionIntent.ClearSelection)
        assertTrue(h.state.selection.isEmpty())
        assertNull(h.state.selectedClipId)
    }

    @Test
    fun `the canvas gets every selected clip with one of them primary`() = runTest(dispatcher) {
        val h = harness()
        h.select("x", "c2")
        val clips = h.vm.snapshotOf(h.state).clips
        val ordered = h.state.visibleTimeline.tracks.flatMap { it.clips }.map { it.id }
        fun flags(id: String) = clips[ordered.indexOf(id)].let { it.selected to it.primary }
        assertEquals(true to false, flags("x"))
        assertEquals(true to true, flags("c2"))
        assertEquals(false to false, flags("y"))
        assertTrue(h.vm.canDrag(h.hit("x")) && h.vm.canDrag(h.hit("c2")))
        assertFalse(h.vm.canDrag(h.hit("y")))
    }

    @Test
    fun `a selection loses the clips that disappear`() = runTest(dispatcher) {
        val h = harness()
        h.select("x", "y", "z")
        h.vm.onIntent(SelectionIntent.DeleteSelection)
        assertTrue(h.state.selection.isEmpty())
        h.vm.onIntent(EditorIntent.Undo)
        h.select("x", "y")
        h.vm.onIntent(EditorIntent.RippleDeleteSelected)
        assertEquals(listOf("z"), h.clips("v2").map { it.id })
    }

    // endregion

    // region group drag

    @Test
    fun `dragging one selected clip moves the whole group and commits one undo step`() = runTest(dispatcher) {
        val h = harness()
        h.select("x", "y")
        h.vm.onIntent(EditorIntent.DragStart(h.hit("x", frame = 10)))
        h.vm.onIntent(EditorIntent.DragMove(frame = 40, trackIndex = 0))
        val preview = checkNotNull(h.state.dragPreview)
        assertEquals(listOf(30L, 130L, 300L), preview.track("v2")!!.clips.map { it.timelineStart.value })
        assertEquals(listOf(0L, 100L, 200L), preview.track("v1")!!.clips.map { it.timelineStart.value })
        h.vm.onIntent(EditorIntent.DragEnd(commit = true))
        assertEquals(listOf(30L, 130L, 300L), h.starts("v2"))
        assertEquals(setOf("x", "y"), h.state.selection)
        h.vm.onIntent(EditorIntent.Undo)
        assertEquals(listOf(0L, 100L, 300L), h.starts("v2"))
    }

    @Test
    fun `a position that would land on another clip keeps the last valid preview`() = runTest(dispatcher) {
        val h = harness()
        h.select("x", "y")
        h.vm.onIntent(EditorIntent.DragStart(h.hit("x", frame = 10)))
        h.vm.onIntent(EditorIntent.DragMove(frame = 40, trackIndex = 0))
        h.vm.onIntent(EditorIntent.DragMove(frame = 200, trackIndex = 0)) // y would end on z (300..400)
        assertEquals(listOf(30L, 130L, 300L), checkNotNull(h.state.dragPreview).track("v2")!!.clips.map { it.timelineStart.value })
        h.vm.onIntent(EditorIntent.DragEnd(commit = true))
        assertEquals(listOf(30L, 130L, 300L), h.starts("v2"))
    }

    @Test
    fun `dragging off the lanes cancels the group move`() = runTest(dispatcher) {
        val h = harness()
        val before = h.timeline
        h.select("x", "y")
        h.vm.onIntent(EditorIntent.DragStart(h.hit("x", frame = 10)))
        h.vm.onIntent(EditorIntent.DragMove(frame = 40, trackIndex = 0))
        h.vm.onIntent(EditorIntent.DragMove(frame = 40, trackIndex = -1, zone = DragZone.OUTSIDE))
        assertEquals(DropKind.CANCEL, h.state.dropHint?.kind)
        assertNull(h.state.dragPreview)
        h.vm.onIntent(EditorIntent.DragEnd(commit = true))
        assertEquals(before, h.timeline)
        assertNull(h.state.dropHint)
    }

    @Test
    fun `a group never drags before the start of the timeline`() = runTest(dispatcher) {
        val h = harness()
        h.select("x", "y")
        h.vm.onIntent(EditorIntent.DragStart(h.hit("y", frame = 110)))
        h.vm.onIntent(EditorIntent.DragMove(frame = 10, trackIndex = 0)) // asks for -100: clamped so x stays at 0
        h.vm.onIntent(EditorIntent.DragEnd(commit = true))
        assertEquals(listOf(0L, 100L, 300L), h.starts("v2"))
    }

    // endregion

    // region group edits

    @Test
    fun `copy, paste and duplicate select what they create and are single undo steps`() = runTest(dispatcher) {
        val h = harness()
        h.vm.onIntent(SelectionIntent.Paste)
        assertEquals("Nothing to paste: copy some clips first", h.messages().last())
        h.select("x", "y")
        h.vm.onIntent(SelectionIntent.Copy)
        assertEquals(2, h.state.clipboardCount)
        assertEquals("Copied 2 clips", h.messages().last())
        h.vm.onIntent(EditorIntent.SetPlayhead(500))
        h.vm.onIntent(SelectionIntent.Paste)
        assertEquals(listOf(0L, 100L, 300L, 500L, 600L), h.starts("v2"))
        assertEquals(2, h.state.selection.size)
        assertTrue(h.state.selection.all { it.contains("~c") })
        h.vm.onIntent(EditorIntent.Undo)
        assertEquals(listOf(0L, 100L, 300L), h.starts("v2"))

        h.select("z")
        h.vm.onIntent(SelectionIntent.Duplicate)
        assertEquals(listOf(0L, 100L, 300L, 400L), h.starts("v2"))
        assertEquals(1, h.state.selection.size)
    }

    @Test
    fun `cut copies then deletes in one step`() = runTest(dispatcher) {
        val h = harness()
        h.select("x", "y")
        h.vm.onIntent(SelectionIntent.Cut)
        assertEquals(2, h.state.clipboardCount)
        assertEquals(listOf(300L), h.starts("v2"))
        h.vm.onIntent(EditorIntent.Undo)
        assertEquals(listOf(0L, 100L, 300L), h.starts("v2"))
    }

    @Test
    fun `deleting base clips as a group closes the gaps`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1", "c3")
        h.vm.onIntent(SelectionIntent.DeleteSelection)
        assertEquals(listOf("c2"), h.clips("v1").map { it.id })
        assertEquals(listOf(0L), h.starts("v1"))
    }

    @Test
    fun `paste attributes puts the copied clip's look on the whole selection in one undo step`() = runTest(dispatcher) {
        val h = harness()
        h.tap("c1")
        h.vm.onIntent(EditorIntent.AddEffect(com.ultimatevideo.uveditor.domain.EffectType.SEPIA))
        h.vm.onIntent(SelectionIntent.Copy)
        h.select("x", "y", "z")
        h.vm.onIntent(SelectionIntent.PasteAttributes)
        assertTrue(h.clips("v2").all { it.fx.effects.size == 1 })
        h.vm.onIntent(EditorIntent.Undo)
        assertTrue(h.clips("v2").all { it.fx.isNeutral })
        assertTrue(h.clips("v1").first().fx.effects.size == 1)
    }

    @Test
    fun `paste attributes without a copied clip says so`() = runTest(dispatcher) {
        val h = harness()
        h.select("x", "y")
        h.vm.onIntent(SelectionIntent.PasteAttributes)
        assertEquals("Copy a clip first, then paste its attributes", h.messages().last())
    }

    @Test
    fun `group speed, gain and opacity act on every selected clip`() = runTest(dispatcher) {
        val h = harness()
        h.select("x", "z")
        h.vm.onIntent(SelectionIntent.SetGroupGain(-6.0))
        assertTrue(h.clips("v2").filter { it.id in setOf("x", "z") }.all { it.gainDb == -6.0 })
        assertEquals(0.0, h.clips("v2").first { it.id == "y" }.gainDb, 0.0)
        h.vm.onIntent(SelectionIntent.SetGroupOpacity(0.5))
        assertTrue(h.clips("v2").filter { it.id in setOf("x", "z") }.all { it.transform.opacity == 0.5 })
        h.vm.onIntent(SelectionIntent.SetGroupSpeed(2, 1))
        assertEquals(50L, h.clips("v2").first { it.id == "x" }.durationFrames)
        assertEquals(50L, h.clips("v2").first { it.id == "z" }.durationFrames)
        // Each of the three edits was its own undo step.
        h.vm.onIntent(EditorIntent.Undo)
        assertEquals(100L, h.clips("v2").first { it.id == "x" }.durationFrames)
        h.vm.onIntent(EditorIntent.Undo)
        assertEquals(1.0, h.clips("v2").first { it.id == "x" }.transform.opacity, 0.0)
    }

    @Test
    fun `align lines the selected clips up and explains when it cannot`() = runTest(dispatcher) {
        val h = harness()
        h.select("x", "z")
        h.vm.onIntent(SelectionIntent.Align(AlignEdge.START))
        // x (0) and z (300) are on the same lane: aligning starts would stack them, which is refused.
        assertEquals(listOf(0L, 100L, 300L), h.starts("v2"))
        assertEquals("That would overlap another clip", h.messages().last())
        h.tap("c2")
        h.vm.onIntent(SelectionIntent.Align(AlignEdge.START))
        assertEquals("Select at least two clips to align", h.messages().last())
    }

    @Test
    fun `transitions are added to the whole selection`() = runTest(dispatcher) {
        val h = harness()
        h.select("x", "y")
        h.vm.onIntent(SelectionIntent.ApplyTransitions(GroupTransition.BETWEEN))
        assertEquals(1, h.timeline.transitions.size)
        h.vm.onIntent(EditorIntent.Undo)
        assertEquals(0, h.timeline.transitions.size)
        h.vm.onIntent(SelectionIntent.ApplyTransitions(GroupTransition.HEAD_AND_TAIL))
        assertTrue(h.clips("v2").filter { it.id in setOf("x", "y") }.all { it.keyframes.size >= 3 })
        assertNotNull(h.state.selectedClipId)
    }

    @Test
    fun `an operation with nothing selected asks for a selection`() = runTest(dispatcher) {
        val h = harness()
        h.vm.onIntent(SelectionIntent.Duplicate)
        assertEquals("Select a clip first", h.messages().last())
        h.vm.onIntent(SelectionIntent.Copy)
        assertEquals("Select clips to copy first", h.messages().last())
    }

    // endregion
}
