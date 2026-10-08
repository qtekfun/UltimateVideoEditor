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
import com.qtekfun.ultimatevideoeditor.domain.MarkerKind
import com.qtekfun.ultimatevideoeditor.domain.TrackType
import com.qtekfun.ultimatevideoeditor.domain.beat.BeatGrid
import com.qtekfun.ultimatevideoeditor.engine.timeline.BeatResult
import com.qtekfun.ultimatevideoeditor.engine.timeline.BeatSource
import com.qtekfun.ultimatevideoeditor.engine.timeline.HitKind
import com.qtekfun.ultimatevideoeditor.engine.timeline.TimelineHit
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BeatTemplateViewModelTest {

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

    /** A song with a beat every half second, from 0, whatever stretch is asked for. */
    private class FakeBeats(var result: ((startMicros: Long, endMicros: Long) -> BeatResult)? = null) : BeatSource {
        var calls = 0
        val requests = mutableListOf<Triple<String, Long, Long>>()

        override suspend fun analyze(assetId: String, startMicros: Long, endMicros: Long): BeatResult {
            calls++
            requests += Triple(assetId, startMicros, endMicros)
            result?.let { return it(startMicros, endMicros) }
            val beats = generateSequence(0L) { it + 500_000L }.takeWhile { it < 60_000_000L }.filter { it >= startMicros && it < endMicros }
            return BeatResult.Found(BeatGrid(120.0, 3.0, beats.map { it - startMicros }.toList()), startMicros)
        }
    }

    private val asset = MediaAssetDto("a1", "content://m/a1", durationFrames = 900, nativeFpsNum = 30, nativeFpsDen = 1, colorSpace = "Rec709-SDR")
    private val silent = MediaAssetDto("a2", "content://m/a2", durationFrames = 900, nativeFpsNum = 30, nativeFpsDen = 1, colorSpace = "Rec709-SDR", hasAudio = false)

    private fun project() = ProjectDto(
        id = "p1",
        name = "Test",
        settings = ProjectSettingsDto(1920, 1080, 30, 1, "Rec709-SDR"),
        mediaLibrary = listOf(asset, silent),
        tracks = listOf(
            TrackDto(
                "v1", "video", 0,
                listOf(ClipDto("c1", "a1", 0, 0, 300), ClipDto("c2", "a1", 300, 0, 150), ClipDto("c3", "a2", 450, 0, 100)),
            ),
            TrackDto("a1", "audio", 1),
        ),
    )

    private class Harness(val vm: EditorViewModel, val beats: FakeBeats, val effects: MutableList<EditorEffect>) {
        val state get() = vm.state.value

        fun select(clipId: String) {
            val ordered = state.visibleTimeline.tracks.flatMap { it.clips }.map { it.id }
            val key = vm.snapshotOf(state).clips[ordered.indexOf(clipId)].clipKey
            vm.onIntent(EditorIntent.TapTimeline(TimelineHit(HitKind.CLIP, 0, key, 0)))
        }

        fun at(frame: Long) = vm.onIntent(EditorIntent.SetPlayhead(frame))

        fun messages(): List<String> = effects.filterIsInstance<EditorEffect.ShowMessage>().map { it.text }

        fun beatFrames() = state.timeline.markers.filter { it.kind == MarkerKind.BEAT }.map { it.frame.value }
    }

    private fun TestScope.harness(beats: FakeBeats = FakeBeats()): Harness {
        var counter = 0
        val vm = EditorViewModel("p1", FakeStore(project()), NoImporter(), idGenerator = { "n${counter++}" }, beatSource = beats)
        val effects = mutableListOf<EditorEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { effects += it } }
        advanceUntilIdle()
        return Harness(vm, beats, effects)
    }

    @Test
    fun `the marker button adds a marker at the playhead and a second press edits it instead of duplicating`() = runTest(dispatcher) {
        val h = harness()
        h.at(90)
        h.vm.onIntent(MarkerIntent.AddAtPlayhead)
        assertEquals(listOf(90L), h.state.timeline.markers.map { it.frame.value })
        assertEquals(MarkerKind.MANUAL, h.state.timeline.markers.single().kind)

        h.at(91)
        h.vm.onIntent(MarkerIntent.AddAtPlayhead)
        assertEquals(listOf(90L), h.state.timeline.markers.map { it.frame.value })
        assertEquals(90L, h.state.markerPopup?.frame)

        h.vm.onIntent(MarkerIntent.Close)
        h.vm.onIntent(EditorIntent.Undo)
        assertEquals(emptyList<Long>(), h.state.timeline.markers.map { it.frame.value })
    }

    @Test
    fun `markers reach the native snapshot with their kind`() = runTest(dispatcher) {
        val h = harness()
        h.at(60)
        h.vm.onIntent(MarkerIntent.AddAtPlayhead)
        h.select("c1")
        h.vm.onIntent(EditorIntent.AnalyzeBeats)
        advanceUntilIdle()

        val markers = h.vm.snapshotOf(h.state).markers
        assertEquals(h.state.timeline.markers.size, markers.size)
        assertTrue(markers.any { it.frame == 60L && !it.beat })
        assertTrue(markers.any { it.frame == 15L && it.beat })
    }

    @Test
    fun `analysing the selected clip marks its beats in one undo step`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")
        h.vm.onIntent(EditorIntent.AnalyzeBeats)
        assertTrue(h.state.isAnalyzingBeats)
        advanceUntilIdle()

        assertFalse(h.state.isAnalyzingBeats)
        // A beat every 15 frames over the clip's 300 frames; none from outside it.
        assertEquals((0L until 300L step 15).toList(), h.beatFrames())
        assertTrue(h.messages().any { it.contains("20 beats") && it.contains("120 BPM") })

        // The audio was read with a few seconds of padding on the end (and not before frame 0).
        val request = h.beats.requests.single()
        assertEquals("a1", request.first)
        assertEquals(0L, request.second)
        assertEquals(10_000_000L + 8_000_000L, request.third)

        h.vm.onIntent(EditorIntent.Undo)
        assertEquals(emptyList<Long>(), h.beatFrames())
    }

    @Test
    fun `analysing another clip keeps the beats of the first`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")
        h.vm.onIntent(EditorIntent.AnalyzeBeats)
        advanceUntilIdle()
        val first = h.beatFrames()
        h.select("c2")
        h.vm.onIntent(EditorIntent.AnalyzeBeats)
        advanceUntilIdle()

        val all = h.beatFrames()
        assertTrue(all.containsAll(first))
        assertEquals((300L until 450L step 15).toList(), all.filter { it >= 300 })
    }

    @Test
    fun `analysing again replaces the beats over the clip instead of adding more`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")
        h.vm.onIntent(EditorIntent.AnalyzeBeats)
        advanceUntilIdle()
        h.beats.result = { start, _ -> BeatResult.Found(BeatGrid(60.0, 3.0, listOf(1_000_000L - start, 3_000_000L - start)), start) }
        h.vm.onIntent(EditorIntent.AnalyzeBeats)
        advanceUntilIdle()
        assertEquals(listOf(30L, 90L), h.beatFrames())
    }

    @Test
    fun `no waveform, no beat, no audio and no selection are each explained`() = runTest(dispatcher) {
        val h = harness()
        h.vm.onIntent(EditorIntent.AnalyzeBeats)
        assertTrue(h.messages().last().contains("Select a clip with audio"))
        assertEquals(0, h.beats.calls)

        h.select("c3")
        h.vm.onIntent(EditorIntent.AnalyzeBeats)
        assertTrue(h.messages().last().contains("Select a clip with audio"))
        assertEquals(0, h.beats.calls)

        h.select("c1")
        h.beats.result = { _, _ -> BeatResult.NoWaveform }
        h.vm.onIntent(EditorIntent.AnalyzeBeats)
        advanceUntilIdle()
        assertTrue(h.messages().last().contains("waveform is still being prepared"))

        h.beats.result = { _, _ -> BeatResult.NoBeat }
        h.vm.onIntent(EditorIntent.AnalyzeBeats)
        advanceUntilIdle()
        assertTrue(h.messages().last().contains("No clear beat"))
        assertEquals(emptyList<Long>(), h.beatFrames())
        assertFalse(h.state.isAnalyzingBeats)
    }

    @Test
    fun `clearing beats keeps the markers placed by hand`() = runTest(dispatcher) {
        val h = harness()
        h.at(5)
        h.vm.onIntent(MarkerIntent.AddAtPlayhead)
        h.select("c1")
        h.vm.onIntent(EditorIntent.AnalyzeBeats)
        advanceUntilIdle()
        assertTrue(h.state.timeline.markers.size > 1)

        h.vm.onIntent(EditorIntent.ClearBeatMarkers)
        assertEquals(listOf(5L), h.state.timeline.markers.map { it.frame.value })
        h.vm.onIntent(EditorIntent.ClearBeatMarkers)
        assertTrue(h.messages().last().contains("no beat markers"))
    }

    @Test
    fun `marker snapping can be turned off`() = runTest(dispatcher) {
        val h = harness()
        assertTrue(h.state.snapToMarkers)
        h.vm.onIntent(EditorIntent.ToggleMarkerSnap)
        assertFalse(h.state.snapToMarkers)
    }

    @Test
    fun `cut to beat ends the selected clip and later ones on markers in one step`() = runTest(dispatcher) {
        val h = harness()
        for (frame in listOf(150L, 330L, 500L)) {
            h.at(frame)
            h.vm.onIntent(MarkerIntent.AddAtPlayhead)
        }
        h.select("c1")
        h.vm.onIntent(EditorIntent.CutToBeatFromSelected)

        val base = h.state.timeline.track("v1")!!.clips.map { Triple(it.id, it.timelineStart.value, it.timelineEnd.value) }
        // c1 aims at 300 and takes the marker at 330 (its asset has room); c2 starts at 330, aims at 480, takes 500; c3 has no marker left.
        assertEquals(listOf(Triple("c1", 0L, 330L), Triple("c2", 330L, 500L), Triple("c3", 500L, 600L)), base)
        h.vm.onIntent(EditorIntent.Undo)
        assertEquals(listOf(0L to 300L, 300L to 450L, 450L to 550L), h.state.timeline.track("v1")!!.clips.map { it.timelineStart.value to it.timelineEnd.value })
    }

    @Test
    fun `cut to beat needs a selected base clip and markers`() = runTest(dispatcher) {
        val h = harness()
        h.vm.onIntent(EditorIntent.CutToBeatFromSelected)
        assertTrue(h.messages().last().contains("Select a clip on the base track"))

        h.select("c1")
        h.vm.onIntent(EditorIntent.CutToBeatFromSelected)
        assertTrue(h.messages().last().contains("no beat markers"))
        assertFalse(h.state.canUndo)
    }

    @Test
    fun `a text template lands at the playhead as one undo step and selects its text`() = runTest(dispatcher) {
        val h = harness()
        h.at(60)
        h.vm.onIntent(EditorIntent.ApplyTextTemplate("lower-third", "Ada Lovelace"))

        val timeline = h.state.timeline
        val title = timeline.tracks.first { it.type == TrackType.TITLE }.clips.single()
        assertEquals("Ada Lovelace", title.title!!.text)
        assertEquals(60L, title.timelineStart.value)
        assertEquals(h.state.selectedClipId, title.id)
        assertTrue(h.state.inspectorOpen)
        // A template is one multilayer title: no extra overlay lane for the bar.
        assertEquals(1, timeline.tracks.count { it.type == TrackType.VIDEO })
        assertTrue(title.title.isLayered)
        assertTrue(timeline.invariantViolations().isEmpty())

        h.vm.onIntent(EditorIntent.Undo)
        assertEquals(listOf("v1", "a1"), h.state.timeline.tracks.map { it.id })
        assertFalse(h.state.canUndo)
    }

    @Test
    fun `an empty text falls back to the template's own and an unknown template is refused`() = runTest(dispatcher) {
        val h = harness()
        h.vm.onIntent(EditorIntent.ApplyTextTemplate("pop-title", "  "))
        assertEquals("Big idea", h.state.timeline.tracks.first { it.type == TrackType.TITLE }.clips.single().title!!.text)

        h.vm.onIntent(EditorIntent.ApplyTextTemplate("nope", "x"))
        assertTrue(h.messages().last().contains("not available"))
    }
}
