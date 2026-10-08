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
import com.qtekfun.ultimatevideoeditor.data.model.TitleDto
import com.qtekfun.ultimatevideoeditor.data.model.TrackDto
import com.qtekfun.ultimatevideoeditor.domain.Clip
import com.qtekfun.ultimatevideoeditor.domain.Effect
import com.qtekfun.ultimatevideoeditor.domain.EffectType
import com.qtekfun.ultimatevideoeditor.domain.Qualifier
import com.qtekfun.ultimatevideoeditor.engine.sample.FrameSampler
import com.qtekfun.ultimatevideoeditor.engine.sample.SampledColor
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QualifierViewModelTest {
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

    private class FakeSampler : FrameSampler {
        var aspect: Double? = 16.0 / 9.0
        var colour: SampledColor? = SampledColor(0.8, 0.2, 0.2)
        val asked = mutableListOf<Triple<Long, Double, Double>>()

        override suspend fun aspect(asset: MediaAssetDto): Double? = aspect

        override suspend fun colorAt(asset: MediaAssetDto, timeMicros: Long, u: Double, v: Double): SampledColor? {
            asked += Triple(timeMicros, u, v)
            return colour
        }
    }

    private val video = MediaAssetDto("a1", "content://m/a1", durationFrames = 900, nativeFpsNum = 30, nativeFpsDen = 1, colorSpace = "Rec709-SDR")
    private val photo = MediaAssetDto(
        "img", "content://m/img", durationFrames = 150, nativeFpsNum = 30, nativeFpsDen = 1, colorSpace = "Rec709-SDR",
        hasVideo = false, hasAudio = false, isImage = true,
    )

    private fun project() = ProjectDto(
        id = "p1",
        name = "Test",
        settings = ProjectSettingsDto(1920, 1080, 30, 1, "Rec709-SDR"),
        mediaLibrary = listOf(video, photo),
        tracks = listOf(
            TrackDto("t1", "title", 0, listOf(ClipDto("t", null, 0, 0, 60, title = TitleDto(text = "Hi")))),
            TrackDto("v2", "video", 1, listOf(ClipDto("p", "img", 0, 0, 150, still = "photo"))),
            TrackDto("v1", "video", 2, listOf(ClipDto("c1", "a1", 0, 0, 300), ClipDto("c2", "a1", 300, 60, 450))),
        ),
    )

    private class Harness(val vm: EditorViewModel, val sampler: FakeSampler, val effects: MutableList<EditorEffect>) {
        val state get() = vm.state.value

        fun select(clipId: String) {
            val ordered = state.visibleTimeline.tracks.flatMap { it.clips }.map { it.id }
            val key = vm.snapshotOf(state).clips[ordered.indexOf(clipId)].clipKey
            vm.onIntent(EditorIntent.TapTimeline(TimelineHit(HitKind.CLIP, 0, key, 0)))
        }

        fun clip(id: String): Clip = checkNotNull(state.timeline.trackOfClip(id)?.clip(id))

        fun qualifierOf(clipId: String): Effect = clip(clipId).fx.effects.single { it.type == EffectType.QUALIFIER }

        fun messages(): List<String> = effects.filterIsInstance<EditorEffect.ShowMessage>().map { it.text }
    }

    private fun TestScope.harness(sampler: FakeSampler = FakeSampler()): Harness {
        var counter = 0
        val vm = EditorViewModel(
            "p1", FakeStore(project()), NoImporter(), idGenerator = { "n${counter++}" },
            stabDispatcher = dispatcher, frameSampler = sampler, sampleDispatcher = dispatcher,
        )
        val effects = mutableListOf<EditorEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { effects += it } }
        advanceUntilIdle()
        return Harness(vm, sampler, effects)
    }

    /** Selects [clipId] and gives it a qualifier effect; returns the effect's id. */
    private fun TestScope.withQualifier(h: Harness, clipId: String): String {
        h.select(clipId)
        h.vm.onIntent(EditorIntent.AddEffect(EffectType.QUALIFIER))
        advanceUntilIdle()
        return h.qualifierOf(clipId).id
    }

    @Test
    fun `arming waits for a tap on the preview and cancel stops waiting`() = runTest(dispatcher) {
        val h = harness()
        val id = withQualifier(h, "c1")
        h.vm.onIntent(QualifierIntent.Arm(id))
        assertEquals(QualifierPickState(effectId = id), h.state.qualifierPick)
        assertTrue(h.state.qualifierPick.armed)
        h.vm.onIntent(QualifierIntent.Cancel)
        assertFalse(h.state.qualifierPick.armed)
    }

    @Test
    fun `a tap keys the effect on the colour, in one undo step`() = runTest(dispatcher) {
        val h = harness()
        val id = withQualifier(h, "c1")
        val before = h.qualifierOf("c1").values
        h.vm.onIntent(QualifierIntent.Arm(id))
        h.vm.onIntent(QualifierIntent.Pick(0.0, 0.0))
        advanceUntilIdle()

        val expected = Qualifier.keyedOn(before, 0.8, 0.2, 0.2)
        assertEquals(expected, h.qualifierOf("c1").values)
        assertFalse(h.state.qualifierPick.armed)
        // The key sits on the colour's hue (red) with the default softness and no correction.
        assertEquals(Qualifier.hsl(0.8, 0.2, 0.2).first, h.qualifierOf("c1").values[Qualifier.HUE], 1e-9)
        assertEquals(0.0, h.qualifierOf("c1").values[Qualifier.HUE_SHIFT], 0.0)
        h.vm.onIntent(EditorIntent.Undo)
        advanceUntilIdle()
        assertEquals(before, h.qualifierOf("c1").values)
    }

    @Test
    fun `the tap is read from the picture at the playhead's source time`() = runTest(dispatcher) {
        val h = harness()
        val id = withQualifier(h, "c2")           // starts at project frame 300 and reads from source frame 60
        h.vm.onIntent(EditorIntent.SetPlayhead(330))
        h.vm.onIntent(QualifierIntent.Arm(id))
        h.vm.onIntent(QualifierIntent.Pick(0.0, 0.0))
        advanceUntilIdle()

        val (micros, u, v) = h.sampler.asked.single()
        assertEquals(3_000_000L, micros) // source frame 60 + 30 into the clip, at 30 fps
        assertEquals(0.5, u, 1e-9)
        assertEquals(0.5, v, 1e-9)
    }

    @Test
    fun `the tap position follows the clip's own transform`() = runTest(dispatcher) {
        val h = harness()
        val id = withQualifier(h, "c1")
        h.vm.onIntent(QualifierIntent.Arm(id))
        // A tap at the right edge of the picture of a 16:9 clip on a 1920x1080 canvas is u = 1.
        h.vm.onIntent(QualifierIntent.Pick(960.0, 0.0))
        advanceUntilIdle()
        val (_, u, v) = h.sampler.asked.single()
        assertEquals(1.0, u, 1e-9)
        assertEquals(0.5, v, 1e-9)
    }

    @Test
    fun `a tap beside the picture says so and keeps waiting for a better one`() = runTest(dispatcher) {
        val h = harness()
        val id = withQualifier(h, "c1")
        val before = h.qualifierOf("c1").values
        h.vm.onIntent(QualifierIntent.Arm(id))
        h.vm.onIntent(QualifierIntent.Pick(5000.0, 0.0))
        advanceUntilIdle()
        assertEquals(before, h.qualifierOf("c1").values)
        assertTrue(h.messages().any { it.contains("picture") })
        assertTrue(h.sampler.asked.isEmpty())
        assertEquals(QualifierPickState(effectId = id), h.state.qualifierPick)
    }

    @Test
    fun `an unreadable picture or colour leaves the key alone and tells the user`() = runTest(dispatcher) {
        val sampler = FakeSampler().also { it.colour = null }
        val h = harness(sampler)
        val id = withQualifier(h, "c1")
        val before = h.qualifierOf("c1").values
        h.vm.onIntent(QualifierIntent.Arm(id))
        h.vm.onIntent(QualifierIntent.Pick(0.0, 0.0))
        advanceUntilIdle()
        assertEquals(before, h.qualifierOf("c1").values)
        assertFalse(h.state.qualifierPick.armed)
        assertTrue(h.messages().any { it.contains("colour") })

        sampler.aspect = null
        h.effects.clear()
        h.vm.onIntent(QualifierIntent.Arm(id))
        h.vm.onIntent(QualifierIntent.Pick(0.0, 0.0))
        advanceUntilIdle()
        assertEquals(before, h.qualifierOf("c1").values)
        assertTrue(h.messages().any { it.contains("could not be read") })
    }

    @Test
    fun `a photo can be picked from and a title cannot`() = runTest(dispatcher) {
        val h = harness()
        val id = withQualifier(h, "p")
        h.vm.onIntent(QualifierIntent.Arm(id))
        assertTrue(h.state.qualifierPick.armed)
        h.vm.onIntent(QualifierIntent.Pick(0.0, 0.0))
        advanceUntilIdle()
        assertEquals(0L, h.sampler.asked.single().first) // a photo has no time
        assertEquals(Qualifier.hsl(0.8, 0.2, 0.2).first, h.qualifierOf("p").values[Qualifier.HUE], 1e-9)

        h.select("t")
        h.vm.onIntent(QualifierIntent.Arm("whatever"))
        assertFalse(h.state.qualifierPick.armed)
        assertTrue(h.messages().any { it.contains("video or photo") })
    }

    @Test
    fun `the playhead must be on the clip and the effect must be a qualifier`() = runTest(dispatcher) {
        val h = harness()
        val id = withQualifier(h, "c1")
        h.vm.onIntent(EditorIntent.SetPlayhead(350)) // on c2
        h.vm.onIntent(QualifierIntent.Arm(id))
        assertFalse(h.state.qualifierPick.armed)
        assertTrue(h.messages().any { it.contains("playhead") })

        h.vm.onIntent(EditorIntent.SetPlayhead(10))
        h.vm.onIntent(EditorIntent.AddEffect(EffectType.SEPIA))
        advanceUntilIdle()
        val sepia = h.clip("c1").fx.effects.first { it.type == EffectType.SEPIA }
        h.vm.onIntent(QualifierIntent.Arm(sepia.id))
        assertFalse(h.state.qualifierPick.armed)
    }

    @Test
    fun `a tap with nothing armed does nothing`() = runTest(dispatcher) {
        val h = harness()
        withQualifier(h, "c1")
        h.vm.onIntent(QualifierIntent.Pick(0.0, 0.0))
        advanceUntilIdle()
        assertTrue(h.sampler.asked.isEmpty())
        assertNull(h.state.qualifierPick.effectId)
    }

    @Test
    fun `sliders keep a range's minimum below its maximum`() {
        val defaults = EffectType.QUALIFIER.defaults
        // Raising the minimum above the maximum drags the maximum up with it.
        val up = Qualifier.withBound(defaults, Qualifier.LUMA_MIN, Qualifier.LUMA_MAX, Qualifier.LUMA_MIN, 0.5)
            .let { Qualifier.withBound(it, Qualifier.LUMA_MIN, Qualifier.LUMA_MAX, Qualifier.LUMA_MAX, 0.3) }
        assertEquals(0.3, up[Qualifier.LUMA_MAX], 0.0)
        assertEquals(0.3, up[Qualifier.LUMA_MIN], 0.0) // lowering the maximum below the minimum pulls the minimum down
        val sat = Qualifier.withBound(defaults, Qualifier.SAT_MIN, Qualifier.SAT_MAX, Qualifier.SAT_MIN, 1.0)
        assertEquals(1.0, sat[Qualifier.SAT_MIN], 0.0)
        assertEquals(1.0, sat[Qualifier.SAT_MAX], 0.0)
        // Other values are untouched and a move inside the range changes only that bound.
        val inside = Qualifier.withBound(defaults, Qualifier.SAT_MIN, Qualifier.SAT_MAX, Qualifier.SAT_MAX, 0.8)
        assertEquals(0.8, inside[Qualifier.SAT_MAX], 0.0)
        assertEquals(defaults[Qualifier.SAT_MIN], inside[Qualifier.SAT_MIN], 0.0)
        assertEquals(defaults[Qualifier.HUE], inside[Qualifier.HUE], 0.0)
    }
}
