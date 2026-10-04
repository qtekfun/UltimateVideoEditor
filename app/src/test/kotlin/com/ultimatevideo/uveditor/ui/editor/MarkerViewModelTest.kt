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
import com.ultimatevideo.uveditor.domain.MarkerColor
import com.ultimatevideo.uveditor.engine.timeline.HitKind
import com.ultimatevideo.uveditor.engine.timeline.SnapshotLabel
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

/** The LumaFusion-style marker flow: one tap to add, a popup to edit, drag along the ruler, previous / next. */
@OptIn(ExperimentalCoroutinesApi::class)
class MarkerViewModelTest {

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

    private val asset = MediaAssetDto("a1", "content://m/a1", durationFrames = 900, nativeFpsNum = 30, nativeFpsDen = 1, colorSpace = "Rec709-SDR")

    private fun project() = ProjectDto(
        id = "p1",
        name = "Test",
        settings = ProjectSettingsDto(1920, 1080, 30, 1, "Rec709-SDR"),
        mediaLibrary = listOf(asset),
        tracks = listOf(
            TrackDto("v1", "video", 0, listOf(ClipDto("c1", "a1", 0, 0, 300), ClipDto("c2", "a1", 300, 0, 150))),
            TrackDto("a1", "audio", 1),
        ),
    )

    private class Harness(val vm: EditorViewModel, val effects: MutableList<EditorEffect>) {
        val state get() = vm.state.value

        fun at(frame: Long) = vm.onIntent(EditorIntent.SetPlayhead(frame))

        fun markFrames() = state.timeline.markers.map { it.frame.value }

        fun mark(frame: Long) {
            at(frame)
            vm.onIntent(MarkerIntent.AddAtPlayhead)
        }

        fun messages(): List<String> = effects.filterIsInstance<EditorEffect.ShowMessage>().map { it.text }

        fun hit(index: Int, fingerFrame: Long) = TimelineHit(HitKind.MARKER, -1, index.toLong(), fingerFrame)
    }

    private fun TestScope.harness(): Harness {
        var counter = 0
        val vm = EditorViewModel("p1", FakeStore(project()), NoImporter(), idGenerator = { "n${counter++}" })
        val effects = mutableListOf<EditorEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { effects += it } }
        advanceUntilIdle()
        return Harness(vm, effects)
    }

    @Test
    fun `one tap drops a marker at the playhead with a hint and one undo step`() = runTest(dispatcher) {
        val h = harness()
        h.at(90)
        h.vm.onIntent(MarkerIntent.AddAtPlayhead)
        assertEquals(listOf(90L), h.markFrames())
        assertNull(h.state.markerPopup)
        assertEquals(MarkerHint(h.state.timeline.markers.single().id, 90), h.state.markerHint)

        h.vm.onIntent(EditorIntent.Undo)
        assertEquals(emptyList<Long>(), h.markFrames())
        h.vm.onIntent(EditorIntent.Redo)
        assertEquals(listOf(90L), h.markFrames())
    }

    @Test
    fun `the hint goes away by itself and on request`() = runTest(dispatcher) {
        val h = harness()
        h.mark(30)
        assertNotNull(h.state.markerHint)
        advanceTimeBy(MARKER_HINT_MILLIS + 100)
        assertNull(h.state.markerHint)

        h.mark(60)
        h.vm.onIntent(MarkerIntent.DismissHint)
        assertNull(h.state.markerHint)
    }

    @Test
    fun `the hint's Edit action opens the popup of that marker`() = runTest(dispatcher) {
        val h = harness()
        h.mark(45)
        h.vm.onIntent(MarkerIntent.Open(h.state.markerHint!!.markerId))
        assertEquals(45L, h.state.markerPopup?.frame)
        assertNull(h.state.markerHint)
    }

    @Test
    fun `a marker already at the playhead is edited, not duplicated`() = runTest(dispatcher) {
        val h = harness()
        h.mark(90)
        h.at(91)
        h.vm.onIntent(MarkerIntent.AddAtPlayhead)
        assertEquals(listOf(90L), h.markFrames())
        assertEquals(90L, h.state.markerPopup?.frame)
    }

    @Test
    fun `popup edits show live and make a single undo step`() = runTest(dispatcher) {
        val h = harness()
        h.mark(90)
        val id = h.state.timeline.markers.single().id
        h.vm.onIntent(MarkerIntent.Open(id))

        h.vm.onIntent(MarkerIntent.NameChanged("Intro cut"))
        h.vm.onIntent(MarkerIntent.NoteChanged("fix the audio here"))
        h.vm.onIntent(MarkerIntent.ColorChosen(MarkerColor.BLUE))
        // Live: the canvas reads the preview, the committed timeline is still untouched.
        assertEquals("Intro cut", h.state.visibleTimeline.markers.single().name)
        assertNull(h.state.timeline.markers.single().name)
        val labels = h.vm.snapshotOf(h.state).labels
        assertTrue(labels.any { it.clipKey == SnapshotLabel.markerKey(0) && it.text == "INTRO CUT" })

        h.vm.onIntent(MarkerIntent.Close)
        assertNull(h.state.markerPopup)
        val marker = h.state.timeline.markers.single()
        assertEquals("Intro cut", marker.name)
        assertEquals("fix the audio here", marker.note)
        assertEquals(MarkerColor.BLUE, marker.color)

        h.vm.onIntent(MarkerIntent.NameChanged("ignored: popup is closed"))
        h.vm.onIntent(EditorIntent.Undo)
        val undone = h.state.timeline.markers.single()
        assertNull(undone.name)
        assertNull(undone.note)
        assertNull(undone.color)
        assertEquals(90L, undone.frame.value)
    }

    @Test
    fun `closing the popup without a change adds no undo step`() = runTest(dispatcher) {
        val h = harness()
        h.mark(90)
        h.vm.onIntent(MarkerIntent.Open(h.state.timeline.markers.single().id))
        h.vm.onIntent(MarkerIntent.Close)
        // The only step is the add: one undo empties the list.
        h.vm.onIntent(EditorIntent.Undo)
        assertEquals(emptyList<Long>(), h.markFrames())
    }

    @Test
    fun `the name is one line and the colour clears when chosen again`() = runTest(dispatcher) {
        val h = harness()
        h.mark(90)
        h.vm.onIntent(MarkerIntent.Open(h.state.timeline.markers.single().id))
        h.vm.onIntent(MarkerIntent.NameChanged("a\nb" + "x".repeat(100)))
        assertFalse(h.state.markerPopup!!.name.contains('\n'))
        assertEquals(com.ultimatevideo.uveditor.domain.MarkerOps.MAX_NAME_LENGTH, h.state.markerPopup!!.name.length)
        h.vm.onIntent(MarkerIntent.ColorChosen(MarkerColor.RED))
        h.vm.onIntent(MarkerIntent.ColorChosen(null))
        assertNull(h.state.markerPopup!!.color)
    }

    @Test
    fun `delete from the popup is one undo step`() = runTest(dispatcher) {
        val h = harness()
        h.mark(90)
        h.vm.onIntent(MarkerIntent.Open(h.state.timeline.markers.single().id))
        h.vm.onIntent(MarkerIntent.DeleteOpen)
        assertNull(h.state.markerPopup)
        assertEquals(emptyList<Long>(), h.markFrames())
        h.vm.onIntent(EditorIntent.Undo)
        assertEquals(listOf(90L), h.markFrames())
    }

    @Test
    fun `a tap on a marker opens its popup and a tap elsewhere closes it, keeping the edit`() = runTest(dispatcher) {
        val h = harness()
        h.mark(90)
        h.mark(200)
        h.vm.onIntent(EditorIntent.TapTimeline(h.hit(0, 93)))
        assertEquals(90L, h.state.markerPopup?.frame)
        h.vm.onIntent(MarkerIntent.NameChanged("A"))
        h.vm.onIntent(EditorIntent.TapTimeline(TimelineHit(HitKind.RULER, -1, -1, 400)))
        assertNull(h.state.markerPopup)
        assertEquals("A", h.state.timeline.markers.first().name)
        assertEquals(400L, h.state.playhead.value)
    }

    @Test
    fun `dragging a marker moves it in whole frames as one undo step`() = runTest(dispatcher) {
        val h = harness()
        h.mark(100)
        h.mark(220)
        h.vm.onIntent(EditorIntent.DragStart(h.hit(0, 103)))
        h.vm.onIntent(EditorIntent.DragMove(153, -1, DragZone.ABOVE_LANES))
        assertEquals(listOf(150L, 220L), h.state.visibleTimeline.markers.map { it.frame.value })
        assertEquals(listOf(100L, 220L), h.markFrames())  // committed only on release
        h.vm.onIntent(EditorIntent.DragEnd(commit = true))
        assertEquals(listOf(150L, 220L), h.markFrames())

        h.vm.onIntent(EditorIntent.Undo)
        assertEquals(listOf(100L, 220L), h.markFrames())
    }

    @Test
    fun `a dragged marker snaps to a clip edge and never lands on another marker`() = runTest(dispatcher) {
        val h = harness()
        h.mark(100)
        h.mark(220)
        h.at(0)
        h.vm.onIntent(EditorIntent.DragStart(h.hit(0, 100)))
        h.vm.onIntent(EditorIntent.DragMove(296, -1, DragZone.ABOVE_LANES))
        assertEquals(300L, h.state.visibleTimeline.markers.map { it.frame.value }.max())
        h.vm.onIntent(EditorIntent.DragMove(223, -1, DragZone.ABOVE_LANES))  // would snap onto the marker at 220
        assertEquals(listOf(220L, 300L), h.state.visibleTimeline.markers.map { it.frame.value })  // the last valid spot stays
        h.vm.onIntent(EditorIntent.DragEnd(commit = true))
        assertEquals(listOf(220L, 300L), h.markFrames())
    }

    @Test
    fun `a cancelled marker drag changes nothing`() = runTest(dispatcher) {
        val h = harness()
        h.mark(100)
        h.vm.onIntent(EditorIntent.DragStart(h.hit(0, 100)))
        h.vm.onIntent(EditorIntent.DragMove(180, -1, DragZone.ABOVE_LANES))
        h.vm.onIntent(EditorIntent.DragEnd(commit = false))
        assertEquals(listOf(100L), h.markFrames())
        assertNull(h.state.dragPreview)
    }

    @Test
    fun `previous and next walk the markers from the popup and move the playhead`() = runTest(dispatcher) {
        val h = harness()
        for (frame in listOf(40L, 120L, 260L)) h.mark(frame)
        h.vm.onIntent(MarkerIntent.Open(h.state.timeline.markers[1].id))
        assertTrue(h.state.markerPopup!!.hasPrevious)
        assertTrue(h.state.markerPopup!!.hasNext)

        h.vm.onIntent(MarkerIntent.PopupNext)
        assertEquals(260L, h.state.markerPopup?.frame)
        assertEquals(260L, h.state.playhead.value)
        assertFalse(h.state.markerPopup!!.hasNext)

        h.vm.onIntent(MarkerIntent.PopupPrevious)
        h.vm.onIntent(MarkerIntent.PopupPrevious)
        assertEquals(40L, h.state.markerPopup?.frame)
        assertEquals(40L, h.state.playhead.value)
        assertFalse(h.state.markerPopup!!.hasPrevious)
    }

    @Test
    fun `stepping to another marker commits the edit of the one left`() = runTest(dispatcher) {
        val h = harness()
        h.mark(40)
        h.mark(120)
        h.vm.onIntent(MarkerIntent.Open(h.state.timeline.markers[0].id))
        h.vm.onIntent(MarkerIntent.NameChanged("First"))
        h.vm.onIntent(MarkerIntent.PopupNext)
        assertEquals("First", h.state.timeline.markers[0].name)
        assertEquals(120L, h.state.markerPopup?.frame)
    }

    @Test
    fun `seek previous and next move the playhead without opening anything`() = runTest(dispatcher) {
        val h = harness()
        h.mark(40)
        h.mark(120)
        h.vm.onIntent(MarkerIntent.DismissHint)
        h.at(100)
        h.vm.onIntent(MarkerIntent.SeekPrevious)
        assertEquals(40L, h.state.playhead.value)
        h.vm.onIntent(MarkerIntent.SeekNext)
        assertEquals(120L, h.state.playhead.value)
        assertNull(h.state.markerPopup)
        h.vm.onIntent(MarkerIntent.SeekNext)
        assertEquals("No marker after the playhead", h.messages().last())
    }

    @Test
    fun `marker names reach the canvas and an unnamed marker sends no label`() = runTest(dispatcher) {
        val h = harness()
        h.mark(40)
        h.mark(120)
        h.vm.onIntent(MarkerIntent.Open(h.state.timeline.markers[1].id))
        h.vm.onIntent(MarkerIntent.NameChanged("Drop"))
        h.vm.onIntent(MarkerIntent.Close)
        val keys = h.vm.snapshotOf(h.state).labels.filter { SnapshotLabel.markerIndexOf(it.clipKey) != null }
        assertEquals(listOf(SnapshotLabel.markerKey(1)), keys.map { it.clipKey })
        assertEquals("DROP", keys.single().text)
    }

    @Test
    fun `adding a marker is instant`() = runTest(dispatcher) {
        val h = harness()
        // Warm the JIT, then time the tap-to-state step on a timeline that already has markers.
        repeat(50) { h.mark(it * 3L) }
        val samples = (0 until 200).map { i ->
            h.at(1_000L + i * 3)
            val start = System.nanoTime()
            h.vm.onIntent(MarkerIntent.AddAtPlayhead)
            (System.nanoTime() - start) / 1_000_000.0
        }
        println("marker add: median %.3f ms, max %.3f ms over %d taps".format(samples.sorted()[samples.size / 2], samples.max(), samples.size))
        assertTrue("one tap must stay under a 60 fps frame, was ${samples.max()} ms", samples.sorted()[samples.size / 2] < 16.0)
        assertEquals(250, h.state.timeline.markers.size)
    }
}
