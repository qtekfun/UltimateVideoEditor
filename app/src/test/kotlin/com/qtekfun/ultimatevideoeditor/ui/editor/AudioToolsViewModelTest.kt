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
import com.qtekfun.ultimatevideoeditor.domain.AudioRole
import com.qtekfun.ultimatevideoeditor.domain.ClipAudio
import com.qtekfun.ultimatevideoeditor.domain.Denoise
import com.qtekfun.ultimatevideoeditor.domain.Ducking
import com.qtekfun.ultimatevideoeditor.domain.TrackAudio
import com.qtekfun.ultimatevideoeditor.domain.VoiceFx
import com.qtekfun.ultimatevideoeditor.domain.VoicePreset
import com.qtekfun.ultimatevideoeditor.engine.audio.LoudnessResult
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AudioToolsViewModelTest {

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

    private class FakeAnalyzer : AudioAnalyzer {
        var lufs: Double? = -26.0
        var failure: AudioAnalysisException? = null
        var loudnessCalls = 0
        var noiseCalls = 0
        var cancelled = 0
        val loudnessRanges = mutableListOf<Pair<Long, Long>>()
        val noiseRanges = mutableListOf<Pair<Long, Long>>()

        override suspend fun loudness(asset: MediaAssetDto, assetKey: Long, startMicros: Long, endMicros: Long): LoudnessResult {
            loudnessCalls++
            loudnessRanges += startMicros to endMicros
            failure?.let { throw it }
            return LoudnessResult(lufs, 0.5)
        }

        override suspend fun noiseProfile(asset: MediaAssetDto, assetKey: Long, startMicros: Long, endMicros: Long): FloatArray {
            noiseCalls++
            noiseRanges += startMicros to endMicros
            failure?.let { throw it }
            return FloatArray(Denoise.BINS) { 0.02f }
        }

        override fun cancel() {
            cancelled++
        }
    }

    private class MemoryCache : LoudnessCache {
        val values = mutableMapOf<String, Double>()

        override fun get(key: String): Double? = values[key]

        override fun put(key: String, lufs: Double) {
            values[key] = lufs
        }
    }

    private val loud = MediaAssetDto("a1", "content://m/a1", durationFrames = 900, nativeFpsNum = 30, nativeFpsDen = 1, colorSpace = "Rec709-SDR")
    private val silent = MediaAssetDto("a2", "content://m/a2", durationFrames = 900, nativeFpsNum = 30, nativeFpsDen = 1, colorSpace = "Rec709-SDR", hasAudio = false)

    private fun project() = ProjectDto(
        id = "p1",
        name = "Test",
        settings = ProjectSettingsDto(1920, 1080, 30, 1, "Rec709-SDR"),
        mediaLibrary = listOf(loud, silent),
        tracks = listOf(
            TrackDto("v1", "video", 0, listOf(ClipDto("c1", "a1", 0, 30, 330), ClipDto("c2", "a2", 300, 0, 100))),
            TrackDto("a1", "audio", 1, listOf(ClipDto("m1", "a1", 0, 0, 200))),
        ),
    )

    private class Harness(val vm: EditorViewModel, val analyzer: FakeAnalyzer, val cache: MemoryCache, val effects: MutableList<EditorEffect>) {
        val state get() = vm.state.value

        fun select(clipId: String) {
            val ordered = state.visibleTimeline.tracks.flatMap { it.clips }.map { it.id }
            val key = vm.snapshotOf(state).clips[ordered.indexOf(clipId)].clipKey
            vm.onIntent(EditorIntent.TapTimeline(TimelineHit(HitKind.CLIP, 0, key, 0)))
        }

        fun at(frame: Long) = vm.onIntent(EditorIntent.SetPlayhead(frame))

        fun messages(): List<String> = effects.filterIsInstance<EditorEffect.ShowMessage>().map { it.text }

        fun clip(id: String) = state.timeline.trackOfClip(id)!!.clip(id)!!
    }

    private fun TestScope.harness(analyzer: FakeAnalyzer = FakeAnalyzer(), withAnalyzer: Boolean = true): Harness {
        var counter = 0
        val cache = MemoryCache()
        val vm = EditorViewModel("p1", FakeStore(project()), NoImporter(), idGenerator = { "n${counter++}" }, loudnessCache = cache)
        if (withAnalyzer) vm.audioAnalyzer = analyzer
        val effects = mutableListOf<EditorEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { effects += it } }
        advanceUntilIdle()
        return Harness(vm, analyzer, cache, effects)
    }

    // region live edits

    @Test
    fun `a pan slider is shown live and committed as one undo step on release`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")

        h.vm.onIntent(EditorIntent.UpdateClipAudio(ClipAudio(pan = 0.2)))
        h.vm.onIntent(EditorIntent.UpdateClipAudio(ClipAudio(pan = 0.5)))
        h.vm.onIntent(EditorIntent.UpdateClipAudio(ClipAudio(pan = 0.8)))

        // Live: the visible timeline and the mixer source change, the committed one and the history do not.
        assertTrue(h.state.audioSessionActive)
        assertEquals(0.8, h.state.selectedClip!!.audio.pan, 0.0)
        assertEquals(0.8, h.state.audioSource.trackOfClip("c1")!!.clip("c1")!!.audio.pan, 0.0)
        assertEquals(ClipAudio.NONE, h.clip("c1").audio)
        assertFalse(h.state.canUndo)

        h.vm.onIntent(EditorIntent.EndAudioEdit(commit = true))
        advanceUntilIdle()

        assertFalse(h.state.audioSessionActive)
        assertNull(h.state.dragPreview)
        assertEquals(0.8, h.clip("c1").audio.pan, 0.0)
        assertTrue(h.state.canUndo)
        h.vm.onIntent(EditorIntent.Undo)
        assertEquals(ClipAudio.NONE, h.clip("c1").audio) // one step back to the start
        assertFalse(h.state.canUndo)
    }

    @Test
    fun `releasing without committing discards the slider and an unchanged value adds no undo step`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")

        h.vm.onIntent(EditorIntent.UpdateClipAudio(ClipAudio(pan = 0.6)))
        h.vm.onIntent(EditorIntent.EndAudioEdit(commit = false))
        assertEquals(ClipAudio.NONE, h.clip("c1").audio)
        assertFalse(h.state.audioSessionActive)

        h.vm.onIntent(EditorIntent.UpdateClipAudio(ClipAudio.NONE)) // dragged back to where it was
        h.vm.onIntent(EditorIntent.EndAudioEdit(commit = true))
        assertFalse(h.state.canUndo)
    }

    @Test
    fun `any other action makes a slider final first`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")
        h.vm.onIntent(EditorIntent.UpdateClipAudio(ClipAudio(pan = -0.7)))

        h.vm.onIntent(EditorIntent.ToggleMixer)

        assertEquals(-0.7, h.clip("c1").audio.pan, 0.0)
        assertTrue(h.state.canUndo)
        assertFalse(h.state.audioSessionActive)
    }

    @Test
    fun `changing to another target commits the previous slider`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")
        h.vm.onIntent(EditorIntent.UpdateClipAudio(ClipAudio(pan = 0.4)))
        h.vm.onIntent(EditorIntent.UpdateTrackAudio("a1", TrackAudio(volumeDb = -6.0)))

        assertEquals(0.4, h.clip("c1").audio.pan, 0.0) // committed when the track slider took over
        h.vm.onIntent(EditorIntent.EndAudioEdit())
        advanceUntilIdle()
        assertEquals(-6.0, h.state.timeline.track("a1")!!.audio.volumeDb, 0.0)
        h.vm.onIntent(EditorIntent.Undo)
        assertEquals(TrackAudio.NONE, h.state.timeline.track("a1")!!.audio)
        assertEquals(0.4, h.clip("c1").audio.pan, 0.0)
    }

    @Test
    fun `an invalid value is refused with a message and starts no edit`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")

        h.vm.onIntent(EditorIntent.UpdateClipAudio(ClipAudio(pan = 5.0)))

        assertFalse(h.state.audioSessionActive)
        assertTrue(h.messages().any { it.contains("not allowed") })
        assertEquals(ClipAudio.NONE, h.clip("c1").audio)
    }

    @Test
    fun `track mute, solo, role and ducking are edited from the mixer and undone`() = runTest(dispatcher) {
        val h = harness()

        h.vm.onIntent(EditorIntent.UpdateTrackAudio("a1", TrackAudio(mute = true, role = AudioRole.MUSIC)))
        h.vm.onIntent(EditorIntent.EndAudioEdit())
        h.vm.onIntent(EditorIntent.UpdateTrackAudio("v1", TrackAudio(role = AudioRole.VOICE, solo = true)))
        h.vm.onIntent(EditorIntent.EndAudioEdit())
        h.vm.onIntent(EditorIntent.UpdateDucking(Ducking(amountDb = 9.0)))
        h.vm.onIntent(EditorIntent.EndAudioEdit())

        assertTrue(h.state.timeline.track("a1")!!.audio.mute)
        assertEquals(AudioRole.VOICE, h.state.timeline.track("v1")!!.audio.role)
        assertEquals(9.0, h.state.timeline.ducking!!.amountDb, 0.0)
        h.vm.onIntent(EditorIntent.Undo)
        assertNull(h.state.timeline.ducking)
        h.vm.onIntent(EditorIntent.Undo)
        assertEquals(TrackAudio.NONE, h.state.timeline.track("v1")!!.audio)
    }

    @Test
    fun `reset puts a clip's sound back to neutral in one step and tells when there is nothing to reset`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")
        h.vm.onIntent(EditorIntent.ResetClipAudio)
        assertTrue(h.messages().any { it.contains("no audio changes") })

        h.vm.onIntent(EditorIntent.UpdateClipAudio(ClipAudio(pan = 0.3, fadeInFrames = 10)))
        h.vm.onIntent(EditorIntent.EndAudioEdit())
        h.vm.onIntent(EditorIntent.ResetClipAudio)
        assertEquals(ClipAudio.NONE, h.clip("c1").audio)
        h.vm.onIntent(EditorIntent.Undo)
        assertEquals(0.3, h.clip("c1").audio.pan, 0.0)
    }

    // endregion

    // region loudness

    @Test
    fun `normalising measures the source range and stores the gain to the target`() = runTest(dispatcher) {
        val h = harness(FakeAnalyzer().apply { lufs = -26.0 })
        h.select("c1")

        h.vm.onIntent(EditorIntent.NormalizeLoudness(-16.0))
        assertEquals("Measuring loudness…", h.state.audioBusy)
        advanceUntilIdle()

        assertNull(h.state.audioBusy)
        assertEquals(10.0, h.clip("c1").audio.normalizeDb, 1e-9)
        assertEquals(-16.0, h.clip("c1").audio.targetLufs!!, 0.0)
        // The clip's source range 30..330 frames at 30 fps is 1 s .. 11 s.
        assertEquals(listOf(1_000_000L to 11_000_000L), h.analyzer.loudnessRanges)
        assertTrue(h.messages().any { it.contains("-26.0 LUFS") && it.contains("+10.0 dB") })
        h.vm.onIntent(EditorIntent.Undo)
        assertEquals(ClipAudio.NONE, h.clip("c1").audio)
    }

    @Test
    fun `the measurement is cached per file and range`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")
        h.vm.onIntent(EditorIntent.NormalizeLoudness(-16.0))
        advanceUntilIdle()
        h.vm.onIntent(EditorIntent.NormalizeLoudness(-23.0))
        advanceUntilIdle()

        assertEquals(1, h.analyzer.loudnessCalls)  // the second target reused the number
        assertEquals(3.0, h.clip("c1").audio.normalizeDb, 1e-9)
        assertEquals(1, h.cache.values.size)
    }

    @Test
    fun `a very quiet clip is limited to the allowed normalise gain and a silent one is left alone`() = runTest(dispatcher) {
        val h = harness(FakeAnalyzer().apply { lufs = -80.0 })
        h.select("c1")
        h.vm.onIntent(EditorIntent.NormalizeLoudness(-16.0))
        advanceUntilIdle()
        assertEquals(ClipAudio.MAX_NORMALIZE_DB, h.clip("c1").audio.normalizeDb, 0.0)

        val silentHarness = harness(FakeAnalyzer().apply { lufs = null })
        silentHarness.select("c1")
        silentHarness.vm.onIntent(EditorIntent.NormalizeLoudness(-16.0))
        advanceUntilIdle()
        assertEquals(ClipAudio.NONE, silentHarness.clip("c1").audio)
        assertTrue(silentHarness.messages().any { it.contains("silent") })
    }

    @Test
    fun `normalising refuses clips without sound, bad targets and a missing engine`() = runTest(dispatcher) {
        val h = harness()
        h.select("c2") // media without audio
        h.vm.onIntent(EditorIntent.NormalizeLoudness(-16.0))
        assertTrue(h.messages().any { it.contains("Select a clip with audio") })
        assertEquals(0, h.analyzer.loudnessCalls)

        h.select("c1")
        h.vm.onIntent(EditorIntent.NormalizeLoudness(3.0))
        assertTrue(h.messages().any { it.contains("between -40.0 and -5.0") })

        val none = harness(withAnalyzer = false)
        none.select("c1")
        none.vm.onIntent(EditorIntent.NormalizeLoudness(-16.0))
        assertTrue(none.messages().any { it.contains("not available") })
    }

    @Test
    fun `a measurement that fails is reported and leaves the clip alone`() = runTest(dispatcher) {
        val h = harness(FakeAnalyzer().apply { failure = AudioAnalysisException("The file could not be read") })
        h.select("c1")
        h.vm.onIntent(EditorIntent.NormalizeLoudness(-16.0))
        advanceUntilIdle()

        assertNull(h.state.audioBusy)
        assertTrue(h.messages().contains("The file could not be read"))
        assertEquals(ClipAudio.NONE, h.clip("c1").audio)
    }

    @Test
    fun `clearing the normalise gain and cancelling a measurement`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")
        h.vm.onIntent(EditorIntent.ClearNormalize)
        assertTrue(h.messages().any { it.contains("not normalised") })
        h.vm.onIntent(EditorIntent.NormalizeLoudness(-16.0))
        advanceUntilIdle()
        h.vm.onIntent(EditorIntent.ClearNormalize)
        assertEquals(0.0, h.clip("c1").audio.normalizeDb, 0.0)
        assertNull(h.clip("c1").audio.targetLufs)

        h.vm.onIntent(EditorIntent.CancelAudioAnalysis)
        assertEquals(1, h.analyzer.cancelled)
    }

    // endregion

    // region noise suppression

    @Test
    fun `marking a quiet stretch then analysing turns suppression on from that region`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")
        h.at(30)
        h.vm.onIntent(EditorIntent.MarkNoiseRegion(atStart = true))
        h.at(60)
        h.vm.onIntent(EditorIntent.MarkNoiseRegion(atStart = false))
        assertEquals(NoiseRegion("c1", 30, 60), h.state.noiseRegion)
        assertTrue(h.state.noiseRegion!!.isComplete)

        h.vm.onIntent(EditorIntent.AnalyzeNoise(0.7))
        assertEquals("Listening to the noise…", h.state.audioBusy)
        advanceUntilIdle()

        val denoise = h.clip("c1").audio.denoise
        assertNotNull(denoise)
        assertEquals(0.7, denoise!!.strength, 0.0)
        assertEquals(Denoise.BINS, denoise.profile.size)
        // Clip frames 30..60 are source frames 60..90 (the clip starts 30 frames into its file): 2 s .. 3 s.
        assertEquals(listOf(2_000_000L to 3_000_000L), h.analyzer.noiseRanges)
        assertNull(h.state.audioBusy)
        h.vm.onIntent(EditorIntent.Undo)
        assertNull(h.clip("c1").audio.denoise)
    }

    @Test
    fun `analysing needs a marked region of at least a tenth of a second`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")
        h.vm.onIntent(EditorIntent.AnalyzeNoise(0.5))
        assertTrue(h.messages().any { it.contains("Mark a quiet stretch") })

        h.at(10)
        h.vm.onIntent(EditorIntent.MarkNoiseRegion(atStart = true))
        h.at(12) // 2 frames = 0.067 s
        h.vm.onIntent(EditorIntent.MarkNoiseRegion(atStart = false))
        h.vm.onIntent(EditorIntent.AnalyzeNoise(0.5))
        assertTrue(h.messages().any { it.contains("at least 0.1 s") })
        assertEquals(0, h.analyzer.noiseCalls)

        h.vm.onIntent(EditorIntent.AnalyzeNoise(0.0))
        assertTrue(h.messages().any { it.contains("above 0") })
    }

    @Test
    fun `marking outside the clip is refused and a start after the end picks a new stretch`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")
        h.at(450) // c1 ends at 300
        h.vm.onIntent(EditorIntent.MarkNoiseRegion(atStart = true))
        assertNull(h.state.noiseRegion)
        assertTrue(h.messages().any { it.contains("inside the clip") })

        h.at(100)
        h.vm.onIntent(EditorIntent.MarkNoiseRegion(atStart = false))
        h.at(150)
        h.vm.onIntent(EditorIntent.MarkNoiseRegion(atStart = true)) // after the end: the end is dropped
        assertEquals(NoiseRegion("c1", 150, null), h.state.noiseRegion)
        h.vm.onIntent(EditorIntent.ClearNoiseRegion)
        assertNull(h.state.noiseRegion)
    }

    @Test
    fun `a failed noise measurement is reported and removing suppression is one undo step`() = runTest(dispatcher) {
        val failing = FakeAnalyzer().apply { failure = AudioAnalysisException("That stretch is too short to measure") }
        val h = harness(failing)
        h.select("c1")
        h.at(0)
        h.vm.onIntent(EditorIntent_markStart())
        h.at(60)
        h.vm.onIntent(EditorIntent.MarkNoiseRegion(atStart = false))
        h.vm.onIntent(EditorIntent.AnalyzeNoise(0.5))
        advanceUntilIdle()
        assertTrue(h.messages().contains("That stretch is too short to measure"))
        assertNull(h.clip("c1").audio.denoise)

        // With a working analyzer the suppression can then be removed again.
        val ok = harness()
        ok.select("c1")
        ok.at(0)
        ok.vm.onIntent(EditorIntent_markStart())
        ok.at(60)
        ok.vm.onIntent(EditorIntent.MarkNoiseRegion(atStart = false))
        ok.vm.onIntent(EditorIntent.AnalyzeNoise(0.5))
        advanceUntilIdle()
        ok.vm.onIntent(EditorIntent.RemoveNoiseSuppression)
        assertNull(ok.clip("c1").audio.denoise)
        ok.vm.onIntent(EditorIntent.RemoveNoiseSuppression)
        assertTrue(ok.messages().any { it.contains("not on") })
    }

    @Test
    fun `the strength slider keeps the profile and is one undo step`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")
        h.at(0)
        h.vm.onIntent(EditorIntent_markStart())
        h.at(60)
        h.vm.onIntent(EditorIntent.MarkNoiseRegion(atStart = false))
        h.vm.onIntent(EditorIntent.AnalyzeNoise(0.4))
        advanceUntilIdle()
        val profile = h.clip("c1").audio.denoise!!.profile

        h.vm.onIntent(EditorIntent.UpdateClipAudio(h.state.selectedClip!!.audio.copy(denoise = Denoise(0.9, profile))))
        h.vm.onIntent(EditorIntent.UpdateClipAudio(h.state.selectedClip!!.audio.copy(denoise = Denoise(0.95, profile))))
        h.vm.onIntent(EditorIntent.EndAudioEdit())
        advanceUntilIdle()

        assertEquals(0.95, h.clip("c1").audio.denoise!!.strength, 0.0)
        h.vm.onIntent(EditorIntent.Undo)
        assertEquals(0.4, h.clip("c1").audio.denoise!!.strength, 0.0)
    }

    // endregion

    @Test
    fun `the mixer sheet toggles`() = runTest(dispatcher) {
        val h = harness()
        assertFalse(h.state.mixerOpen)
        h.vm.onIntent(EditorIntent.ToggleMixer)
        assertTrue(h.state.mixerOpen)
        h.vm.onIntent(EditorIntent.ToggleMixer)
        assertFalse(h.state.mixerOpen)
    }

    // region voice effects

    @Test
    fun `a voice effect is one undo step and reaches the mixer snapshot`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")

        h.vm.onIntent(EditorIntent.UpdateClipAudio(ClipAudio(voice = VoicePreset.ECHO.defaults())))
        h.vm.onIntent(EditorIntent.EndAudioEdit(commit = true))
        advanceUntilIdle()

        assertEquals(VoicePreset.ECHO, h.clip("c1").audio.voice?.preset)
        assertTrue(h.state.canUndo)
        val keys = KeyRegistry()
        val spec = audioSnapshotOf(h.state.timeline, listOf(loud, silent), h.state.fps, keys::keyFor, keys::keyFor)
            .clips.single { it.voice.echoMs > 0f }.voice
        assertEquals(280f, spec.echoMs, 0f)

        // Changing a slider replaces the effect in one more step; undo walks back through both.
        h.vm.onIntent(EditorIntent.UpdateClipAudio(h.clip("c1").audio.copy(voice = h.clip("c1").audio.voice!!.with(0, 500.0))))
        h.vm.onIntent(EditorIntent.EndAudioEdit(commit = true))
        assertEquals(500.0, h.clip("c1").audio.voice!!.values[0], 0.0)
        h.vm.onIntent(EditorIntent.Undo)
        assertEquals(280.0, h.clip("c1").audio.voice!!.values[0], 0.0)
        h.vm.onIntent(EditorIntent.Undo)
        assertNull(h.clip("c1").audio.voice)
        assertFalse(h.state.canUndo)
    }

    @Test
    fun `a voice effect with impossible values is refused with a message`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")

        h.vm.onIntent(EditorIntent.UpdateClipAudio(ClipAudio(voice = VoiceFx(VoicePreset.ECHO, listOf(5000.0, 0.5, 0.5)))))

        assertFalse(h.state.audioSessionActive)
        assertTrue(h.messages().any { it.contains("not allowed") })
        assertNull(h.clip("c1").audio.voice)
    }

    // endregion

    private fun EditorIntent_markStart() = EditorIntent.MarkNoiseRegion(atStart = true)
}
