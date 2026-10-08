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
import com.qtekfun.ultimatevideoeditor.domain.CurvePoint
import com.qtekfun.ultimatevideoeditor.domain.EffectType
import com.qtekfun.ultimatevideoeditor.domain.GradeCurve
import com.qtekfun.ultimatevideoeditor.domain.GradeCurves
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
class ColorGradeViewModelTest {

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

    private val asset = MediaAssetDto("a1", "content://m/a1", durationFrames = 300, nativeFpsNum = 30, nativeFpsDen = 1, colorSpace = "Rec709-SDR")

    private val project = ProjectDto(
        id = "p1",
        name = "Test",
        settings = ProjectSettingsDto(1920, 1080, 30, 1, "Rec709-SDR"),
        mediaLibrary = listOf(asset),
        tracks = listOf(
            TrackDto("v1", "video", 0, listOf(ClipDto("c1", "a1", 0, 0, 100), ClipDto("c2", "a1", 100, 0, 100))),
            TrackDto("a1", "audio", 1, listOf(ClipDto("s1", "a1", 0, 0, 100))),
        ),
    )

    private class Harness(val vm: EditorViewModel, val effects: MutableList<EditorEffect>) {
        val state get() = vm.state.value
        fun clip(id: String): Clip = checkNotNull(state.timeline.track("v1")!!.clip(id))
        val shownC1: Clip get() = checkNotNull(state.visibleTimeline.track("v1")!!.clip("c1"))

        fun select(clipId: String) {
            val ordered = state.visibleTimeline.tracks.flatMap { it.clips }.map { it.id }
            val key = vm.snapshotOf(state).clips[ordered.indexOf(clipId)].clipKey
            vm.onIntent(EditorIntent.TapTimeline(TimelineHit(HitKind.CLIP, 0, key, 0)))
        }

        fun messages(): List<String> = effects.filterIsInstance<EditorEffect.ShowMessage>().map { it.text }
    }

    private fun TestScope.harness(): Harness {
        var counter = 0
        val vm = EditorViewModel("p1", FakeStore(project), NoImporter(), idGenerator = { "n${counter++}" })
        val effects = mutableListOf<EditorEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { effects += it } }
        advanceUntilIdle()
        return Harness(vm, effects)
    }

    private fun values(vararg changes: Pair<Int, Double>): List<Double> =
        EffectType.COLOR_GRADE.defaults.toMutableList().also { for ((i, v) in changes) it[i] = v }

    @Test
    fun `a grade is added from the effect menu with neutral values`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")

        h.vm.onIntent(EditorIntent.AddEffect(EffectType.COLOR_GRADE))

        val grade = h.clip("c1").fx.effects.single()
        assertEquals(EffectType.COLOR_GRADE, grade.type)
        assertEquals(EffectType.COLOR_GRADE.defaults, grade.values)
        assertNull(grade.curves)
    }

    @Test
    fun `dragging a wheel is shown live and committed as one undo step on release`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")
        h.vm.onIntent(EditorIntent.AddEffect(EffectType.COLOR_GRADE))
        val id = h.clip("c1").fx.effects.single().id

        h.vm.onIntent(EditorIntent.UpdateGrade(id, values(0 to 0.1), null))
        h.vm.onIntent(EditorIntent.UpdateGrade(id, values(0 to 0.2, 1 to -0.1), null))

        assertEquals(0.2, h.shownC1.fx.effects.single().values[0], 0.0) // live preview
        assertEquals(0.0, h.clip("c1").fx.effects.single().values[0], 0.0) // not committed yet
        h.vm.onIntent(EditorIntent.EndFxEdit(commit = true))
        assertEquals(values(0 to 0.2, 1 to -0.1), h.clip("c1").fx.effects.single().values)

        h.vm.onIntent(EditorIntent.Undo)
        assertEquals(EffectType.COLOR_GRADE.defaults, h.clip("c1").fx.effects.single().values)
    }

    @Test
    fun `curve edits travel with the grade and identity curves are dropped`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")
        h.vm.onIntent(EditorIntent.AddEffect(EffectType.COLOR_GRADE))
        val id = h.clip("c1").fx.effects.single().id
        val s = GradeCurves(master = GradeCurve(listOf(CurvePoint(0.0, 0.0), CurvePoint(0.5, 0.4), CurvePoint(1.0, 1.0))))

        h.vm.onIntent(EditorIntent.UpdateGrade(id, EffectType.COLOR_GRADE.defaults, s))
        h.vm.onIntent(EditorIntent.EndFxEdit(commit = true))
        assertEquals(s, h.clip("c1").fx.effects.single().curves)

        h.vm.onIntent(EditorIntent.UpdateGrade(id, EffectType.COLOR_GRADE.defaults, GradeCurves.IDENTITY))
        h.vm.onIntent(EditorIntent.EndFxEdit(commit = true))
        assertNull(h.clip("c1").fx.effects.single().curves)
    }

    @Test
    fun `an out of range grade value is refused with a message and changes nothing`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")
        h.vm.onIntent(EditorIntent.AddEffect(EffectType.COLOR_GRADE))
        val id = h.clip("c1").fx.effects.single().id

        h.vm.onIntent(EditorIntent.UpdateGrade(id, values(15 to 9.0), null))
        h.vm.onIntent(EditorIntent.EndFxEdit(commit = true))

        assertEquals(EffectType.COLOR_GRADE.defaults, h.clip("c1").fx.effects.single().values)
        assertTrue(h.messages().any { it.contains("not allowed") })
    }

    @Test
    fun `updating a grade that is not a grade does nothing`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")
        h.vm.onIntent(EditorIntent.AddEffect(EffectType.BLUR))
        val blur = h.clip("c1").fx.effects.single()

        h.vm.onIntent(EditorIntent.UpdateGrade(blur.id, values(), null))
        h.vm.onIntent(EditorIntent.EndFxEdit(commit = true))

        assertEquals(blur, h.clip("c1").fx.effects.single())
    }

    @Test
    fun `applying a look adds a grade to a clip that has none, in one undo step`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")
        val s = GradeCurves(blue = GradeCurve(listOf(CurvePoint(0.0, 0.1), CurvePoint(1.0, 1.0))))

        h.vm.onIntent(EditorIntent.ApplyGrade(values(17 to 1.4), s))

        val grade = h.clip("c1").fx.effects.single()
        assertEquals(EffectType.COLOR_GRADE, grade.type)
        assertEquals(1.4, grade.values[17], 0.0)
        assertEquals(s, grade.curves)
        h.vm.onIntent(EditorIntent.Undo)
        assertTrue(h.clip("c1").fx.effects.isEmpty())
    }

    @Test
    fun `applying a look replaces the clips existing grade instead of stacking a second one`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")
        h.vm.onIntent(EditorIntent.AddEffect(EffectType.COLOR_GRADE))
        val id = h.clip("c1").fx.effects.single().id

        h.vm.onIntent(EditorIntent.ApplyGrade(values(15 to 1.3), null))

        val effects = h.clip("c1").fx.effects
        assertEquals(1, effects.size)
        assertEquals(id, effects.single().id)
        assertEquals(1.3, effects.single().values[15], 0.0)
    }

    @Test
    fun `a grade copied from one clip pastes onto another`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")
        h.vm.onIntent(EditorIntent.ApplyGrade(values(19 to 0.5), null))
        val copied = h.clip("c1").fx.effects.single()

        h.select("c2")
        h.vm.onIntent(EditorIntent.ApplyGrade(copied.values, copied.curves))

        assertEquals(copied.values, h.clip("c2").fx.effects.single().values)
        assertFalse(h.clip("c2").fx.effects.single().id == copied.id)
        assertNotNull(h.clip("c2").fx.effects.single())
    }

    @Test
    fun `a clip with eight effects cannot take another grade but still replaces its own`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")
        repeat(8) { h.vm.onIntent(EditorIntent.AddEffect(EffectType.BLUR)) }
        h.vm.onIntent(EditorIntent.ApplyGrade(values(), null))
        assertEquals(8, h.clip("c1").fx.effects.size)
        assertTrue(h.messages().any { it.contains("at most") })
    }

    @Test
    fun `an audio clip cannot be graded`() = runTest(dispatcher) {
        val h = harness()
        val ordered = h.state.visibleTimeline.tracks.flatMap { it.clips }.map { it.id }
        val key = h.vm.snapshotOf(h.state).clips[ordered.indexOf("s1")].clipKey
        h.vm.onIntent(EditorIntent.TapTimeline(TimelineHit(HitKind.CLIP, 0, key, 0)))

        h.vm.onIntent(EditorIntent.ApplyGrade(values(), null))

        assertTrue(h.state.timeline.track("a1")!!.clip("s1")!!.fx.effects.isEmpty())
    }
}
