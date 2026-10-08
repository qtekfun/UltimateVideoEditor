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
import com.qtekfun.ultimatevideoeditor.domain.SilenceSettings
import com.qtekfun.ultimatevideoeditor.domain.beat.PeakEnvelope
import com.qtekfun.ultimatevideoeditor.engine.timeline.EnvelopeResult
import com.qtekfun.ultimatevideoeditor.engine.timeline.EnvelopeSource
import com.qtekfun.ultimatevideoeditor.engine.timeline.HitKind
import com.qtekfun.ultimatevideoeditor.engine.timeline.TimelineHit
import com.qtekfun.ultimatevideoeditor.engine.track.MotionTracker
import com.qtekfun.ultimatevideoeditor.engine.track.NoMotionTracker
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
class QuickEditsViewModelTest {

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

    /** 100 bins per second: loud for 3 s, silent for 2 s, loud for 5 s (10 s of audio), from source time 0. */
    private class FakeEnvelope : EnvelopeSource {
        var calls = 0
        var result: EnvelopeResult? = null

        override suspend fun envelope(assetId: String, startMicros: Long, endMicros: Long): EnvelopeResult {
            calls++
            result?.let { return it }
            val values = FloatArray(1000) { if (it in 300 until 500) 0.0f else 0.5f }
            return EnvelopeResult.Found(PeakEnvelope(100.0, values), 0L)
        }
    }

    private class FixedAspect(private val aspect: Double?) : MotionTracker by NoMotionTracker {
        override suspend fun frameAspect(asset: MediaAssetDto): Double? = aspect
    }

    private val asset = MediaAssetDto("a1", "content://m/a1", durationFrames = 300, nativeFpsNum = 30, nativeFpsDen = 1, colorSpace = "Rec709-SDR")
    private val silent = MediaAssetDto("a2", "content://m/a2", durationFrames = 300, nativeFpsNum = 30, nativeFpsDen = 1, colorSpace = "Rec709-SDR", hasAudio = false)

    private fun project() = ProjectDto(
        id = "p1",
        name = "Test",
        settings = ProjectSettingsDto(1080, 1920, 30, 1, "Rec709-SDR"),
        mediaLibrary = listOf(asset, silent),
        tracks = listOf(
            TrackDto("v1", "video", 0, listOf(ClipDto("c1", "a1", 0, 0, 300), ClipDto("c2", "a2", 300, 0, 100))),
            TrackDto("a1", "audio", 1),
        ),
    )

    private class Harness(val vm: EditorViewModel, val envelope: FakeEnvelope, val effects: MutableList<EditorEffect>) {
        val state get() = vm.state.value

        fun select(clipId: String) {
            val ordered = state.visibleTimeline.tracks.flatMap { it.clips }.map { it.id }
            val key = vm.snapshotOf(state).clips[ordered.indexOf(clipId)].clipKey
            vm.onIntent(EditorIntent.TapTimeline(TimelineHit(HitKind.CLIP, 0, key, 0)))
        }

        fun messages(): List<String> = effects.filterIsInstance<EditorEffect.ShowMessage>().map { it.text }
    }

    private fun TestScope.harness(envelope: FakeEnvelope = FakeEnvelope(), tracker: MotionTracker = NoMotionTracker): Harness {
        var counter = 0
        val vm = EditorViewModel(
            "p1",
            FakeStore(project()),
            NoImporter(),
            idGenerator = { "n${counter++}" },
            envelopeSource = envelope,
            motionTracker = tracker,
            trackDispatcher = dispatcher,
        )
        val effects = mutableListOf<EditorEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { effects += it } }
        advanceUntilIdle()
        return Harness(vm, envelope, effects)
    }

    // region auto cut

    @Test
    fun `cut silences needs a base clip with audio`() = runTest(dispatcher) {
        val h = harness()
        h.vm.onIntent(QuickEditIntent.OpenAutoCut)
        assertFalse(h.state.quickEdits.autoCut.open)
        assertTrue(h.messages().last().contains("base track"))

        h.select("c2")
        h.vm.onIntent(QuickEditIntent.OpenAutoCut)
        assertFalse(h.state.quickEdits.autoCut.open)
        assertTrue(h.messages().last().contains("audio"))
    }

    @Test
    fun `finding silences proposes the quiet stretch mapped onto the timeline`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")
        h.vm.onIntent(QuickEditIntent.OpenAutoCut)
        assertTrue(h.state.quickEdits.autoCut.open)
        h.vm.onIntent(QuickEditIntent.SetSilenceSettings(SilenceSettings(thresholdDb = -40.0, minSilenceSeconds = 0.5, paddingSeconds = 0.1)))
        h.vm.onIntent(QuickEditIntent.FindSilences)
        advanceUntilIdle()

        val auto = h.state.quickEdits.autoCut
        assertFalse(auto.analyzing)
        // Silence is 3.0..5.0 s; with 0.1 s of padding it is 3.1..4.9 s = frames 93..147.
        assertEquals(listOf(93L to 147L), auto.cuts.map { it.startFrame to it.endFrame })
        assertEquals(null, auto.message)
    }

    @Test
    fun `applying the cuts shortens the base and one undo restores it`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")
        h.vm.onIntent(QuickEditIntent.OpenAutoCut)
        h.vm.onIntent(QuickEditIntent.FindSilences)
        advanceUntilIdle()
        val before = h.state.timeline
        val baseLength = before.track("v1")!!.end.value

        h.vm.onIntent(QuickEditIntent.ApplyAutoCut)
        advanceUntilIdle()

        assertFalse(h.state.quickEdits.autoCut.open)
        assertEquals(baseLength - 54L, h.state.timeline.track("v1")!!.end.value)
        assertTrue(h.messages().last().startsWith("Removed 1 silences"))
        h.vm.onIntent(EditorIntent.Undo)
        advanceUntilIdle()
        assertEquals(before, h.state.timeline)
    }

    @Test
    fun `a cut the user switches off is not applied`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")
        h.vm.onIntent(QuickEditIntent.OpenAutoCut)
        h.vm.onIntent(QuickEditIntent.FindSilences)
        advanceUntilIdle()
        h.vm.onIntent(QuickEditIntent.ToggleCut(0))
        assertTrue(h.state.quickEdits.autoCut.chosen.isEmpty())
        val before = h.state.timeline

        h.vm.onIntent(QuickEditIntent.ApplyAutoCut)
        advanceUntilIdle()
        assertEquals(before, h.state.timeline)
    }

    @Test
    fun `changing the settings clears the proposals and a missing waveform is explained`() = runTest(dispatcher) {
        val envelope = FakeEnvelope().apply { result = EnvelopeResult.NoWaveform }
        val h = harness(envelope)
        h.select("c1")
        h.vm.onIntent(QuickEditIntent.OpenAutoCut)
        h.vm.onIntent(QuickEditIntent.FindSilences)
        advanceUntilIdle()
        assertTrue(h.state.quickEdits.autoCut.message!!.contains("waveform"))
        assertTrue(h.state.quickEdits.autoCut.cuts.isEmpty())

        envelope.result = null
        h.vm.onIntent(QuickEditIntent.FindSilences)
        advanceUntilIdle()
        assertEquals(1, h.state.quickEdits.autoCut.cuts.size)
        h.vm.onIntent(QuickEditIntent.SetSilenceSettings(SilenceSettings(minSilenceSeconds = 3.0)))
        assertTrue(h.state.quickEdits.autoCut.cuts.isEmpty())
    }

    @Test
    fun `a clip with changed speed cannot be cut by silence`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")
        h.vm.onIntent(EditorIntent.SetSpeed(2, 1))
        advanceUntilIdle()
        h.vm.onIntent(QuickEditIntent.OpenAutoCut)
        assertFalse(h.state.quickEdits.autoCut.open)
        assertTrue(h.messages().last().contains("speed"))
    }

    // endregion

    // region reframe

    @Test
    fun `reframe applies one point to the whole clip and is one undo step`() = runTest(dispatcher) {
        val h = harness(tracker = FixedAspect(16.0 / 9.0))
        h.select("c1")
        h.vm.onIntent(QuickEditIntent.OpenReframe)
        assertTrue(h.state.quickEdits.reframe.open)
        h.vm.onIntent(QuickEditIntent.SetReframe(0.3, 0.5, 1.0))
        val before = h.state.timeline

        h.vm.onIntent(QuickEditIntent.ApplyReframe)
        advanceUntilIdle()

        val clip = h.state.timeline.track("v1")!!.clip("c1")!!
        assertTrue(clip.transform.scaleX > 1.0)
        assertTrue(clip.keyframes.isEmpty())
        assertFalse(h.state.quickEdits.reframe.open)
        h.vm.onIntent(EditorIntent.Undo)
        advanceUntilIdle()
        assertEquals(before, h.state.timeline)
    }

    @Test
    fun `marking several moments writes keyframes at those clip frames`() = runTest(dispatcher) {
        val h = harness(tracker = FixedAspect(16.0 / 9.0))
        h.select("c1")
        h.vm.onIntent(QuickEditIntent.OpenReframe)
        h.vm.onIntent(EditorIntent.SetPlayhead(30))
        h.vm.onIntent(QuickEditIntent.SetReframe(0.2, 0.5, 1.0))
        h.vm.onIntent(QuickEditIntent.MarkReframePoint)
        h.vm.onIntent(EditorIntent.SetPlayhead(120))
        h.vm.onIntent(QuickEditIntent.SetReframe(0.8, 0.5, 1.0))
        h.vm.onIntent(QuickEditIntent.MarkReframePoint)
        assertEquals(listOf(30L, 120L), h.state.quickEdits.reframe.points.map { it.frame })

        h.vm.onIntent(QuickEditIntent.ApplyReframe)
        advanceUntilIdle()
        val keys = h.state.timeline.track("v1")!!.clip("c1")!!.keyframes
        assertEquals(listOf(30L, 120L), keys.map { it.frame })
        assertTrue(keys[0].transform.positionX > keys[1].transform.positionX)
    }

    @Test
    fun `marking needs the playhead on the clip and an unreadable picture is reported`() = runTest(dispatcher) {
        val h = harness(tracker = FixedAspect(null))
        h.select("c1")
        h.vm.onIntent(QuickEditIntent.OpenReframe)
        h.vm.onIntent(EditorIntent.SetPlayhead(350))
        h.vm.onIntent(QuickEditIntent.MarkReframePoint)
        assertTrue(h.state.quickEdits.reframe.message!!.contains("playhead"))

        h.vm.onIntent(QuickEditIntent.ApplyReframe)
        advanceUntilIdle()
        assertTrue(h.state.quickEdits.reframe.message!!.contains("shape"))
        assertTrue(h.state.quickEdits.reframe.open)
    }

    // endregion
}
