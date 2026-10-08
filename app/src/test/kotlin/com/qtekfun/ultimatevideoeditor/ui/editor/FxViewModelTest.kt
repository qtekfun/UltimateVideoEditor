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
import com.qtekfun.ultimatevideoeditor.domain.BlendMode
import com.qtekfun.ultimatevideoeditor.domain.Clip
import com.qtekfun.ultimatevideoeditor.domain.ClipMask
import com.qtekfun.ultimatevideoeditor.domain.EffectType
import com.qtekfun.ultimatevideoeditor.domain.MaskShape
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
class FxViewModelTest {

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

    private class Harness(val vm: EditorViewModel, val store: FakeStore, val effects: MutableList<EditorEffect>) {
        val state get() = vm.state.value
        val c1: Clip get() = checkNotNull(state.timeline.track("v1")!!.clip("c1"))
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
        val store = FakeStore(project)
        val vm = EditorViewModel("p1", store, NoImporter(), idGenerator = { "n${counter++}" })
        val effects = mutableListOf<EditorEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { effects += it } }
        advanceUntilIdle()
        return Harness(vm, store, effects)
    }

    @Test
    fun `adding an effect puts it on the selected clip as one undo step`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")

        h.vm.onIntent(EditorIntent.AddEffect(EffectType.SEPIA))

        assertEquals(listOf(EffectType.SEPIA), h.c1.fx.effects.map { it.type })
        assertEquals(EffectType.SEPIA.defaults, h.c1.fx.effects.single().values)
        assertTrue(h.state.canUndo)
        assertTrue(h.vm.snapshotOf(h.state).clips[0].hasFx)
        assertFalse(h.vm.snapshotOf(h.state).clips[1].hasFx)

        h.vm.onIntent(EditorIntent.Undo)
        assertTrue(h.c1.fx.isNeutral)
        assertFalse(h.vm.snapshotOf(h.state).clips[0].hasFx)
    }

    @Test
    fun `effects need a selected video clip or title`() = runTest(dispatcher) {
        val h = harness()

        h.vm.onIntent(EditorIntent.AddEffect(EffectType.BLUR))
        assertEquals(listOf("Select a clip first"), h.messages())

        h.select("s1")
        h.vm.onIntent(EditorIntent.AddEffect(EffectType.BLUR))
        assertTrue(h.messages().last().contains("not possible"))
        assertTrue(h.state.timeline.track("a1")!!.clip("s1")!!.fx.isNeutral)
    }

    @Test
    fun `dragging a slider shows the value live and commits one step on release`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")
        h.vm.onIntent(EditorIntent.AddEffect(EffectType.CONTRAST))
        val effectId = h.c1.fx.effects.single().id

        h.vm.onIntent(EditorIntent.UpdateEffect(effectId, listOf(1.2)))
        h.vm.onIntent(EditorIntent.UpdateEffect(effectId, listOf(1.4)))
        h.vm.onIntent(EditorIntent.UpdateEffect(effectId, listOf(1.6)))

        // Shown on the canvas, not yet in the committed timeline.
        assertEquals(listOf(1.6), h.shownC1.fx.effects.single().values)
        assertEquals(listOf(1.0), h.c1.fx.effects.single().values)

        h.vm.onIntent(EditorIntent.EndFxEdit(commit = true))
        assertEquals(listOf(1.6), h.c1.fx.effects.single().values)
        assertNull(h.state.dragPreview)

        // The whole drag undoes in one step, back to the effect as it was added.
        h.vm.onIntent(EditorIntent.Undo)
        assertEquals(listOf(1.0), h.c1.fx.effects.single().values)
    }

    @Test
    fun `releasing without committing discards the drag`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")
        h.vm.onIntent(EditorIntent.AddEffect(EffectType.CONTRAST))
        val effectId = h.c1.fx.effects.single().id

        h.vm.onIntent(EditorIntent.UpdateEffect(effectId, listOf(1.9)))
        h.vm.onIntent(EditorIntent.EndFxEdit(commit = false))

        assertEquals(listOf(1.0), h.c1.fx.effects.single().values)
        assertNull(h.state.dragPreview)
    }

    @Test
    fun `any other intent makes a pending slider drag final`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")
        h.vm.onIntent(EditorIntent.AddEffect(EffectType.CONTRAST))
        val effectId = h.c1.fx.effects.single().id

        h.vm.onIntent(EditorIntent.UpdateEffect(effectId, listOf(1.5)))
        h.vm.onIntent(EditorIntent.SetBlendMode(BlendMode.SCREEN))

        assertEquals(listOf(1.5), h.c1.fx.effects.single().values)
        assertEquals(BlendMode.SCREEN, h.c1.fx.blendMode)
    }

    @Test
    fun `out of range values are refused and nothing changes`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")
        h.vm.onIntent(EditorIntent.AddEffect(EffectType.CONTRAST))
        val effectId = h.c1.fx.effects.single().id

        h.vm.onIntent(EditorIntent.UpdateEffect(effectId, listOf(9.0)))
        h.vm.onIntent(EditorIntent.EndFxEdit(commit = true))

        assertEquals(listOf(1.0), h.shownC1.fx.effects.single().values)
        assertTrue(h.messages().last().contains("not allowed"))
    }

    @Test
    fun `remove and reorder are one step each`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")
        h.vm.onIntent(EditorIntent.AddEffect(EffectType.BLUR))
        h.vm.onIntent(EditorIntent.AddEffect(EffectType.SHARPEN))
        h.vm.onIntent(EditorIntent.AddEffect(EffectType.VIGNETTE))
        val ids = h.c1.fx.effects.map { it.id }

        h.vm.onIntent(EditorIntent.MoveEffect(ids[2], 0))
        assertEquals(listOf(ids[2], ids[0], ids[1]), h.c1.fx.effects.map { it.id })

        h.vm.onIntent(EditorIntent.RemoveEffect(ids[0]))
        assertEquals(listOf(ids[2], ids[1]), h.c1.fx.effects.map { it.id })

        h.vm.onIntent(EditorIntent.Undo)
        assertEquals(listOf(ids[2], ids[0], ids[1]), h.c1.fx.effects.map { it.id })
        h.vm.onIntent(EditorIntent.Undo)
        assertEquals(ids, h.c1.fx.effects.map { it.id })
    }

    @Test
    fun `blend mode and mask set, show live and clear`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")

        h.vm.onIntent(EditorIntent.SetBlendMode(BlendMode.MULTIPLY))
        h.vm.onIntent(EditorIntent.UpdateMask(ClipMask(MaskShape.ELLIPSE)))
        assertEquals(MaskShape.ELLIPSE, h.shownC1.fx.mask?.shape)
        assertNull(h.c1.fx.mask)

        h.vm.onIntent(EditorIntent.EndFxEdit(commit = true))
        assertNotNull(h.c1.fx.mask)
        assertEquals(BlendMode.MULTIPLY, h.c1.fx.blendMode)

        h.vm.onIntent(EditorIntent.UpdateMask(ClipMask(width = 0.0)))
        assertTrue(h.messages().last().contains("not allowed"))
        h.vm.onIntent(EditorIntent.EndFxEdit(commit = true))
        assertEquals(ClipMask(MaskShape.ELLIPSE), h.c1.fx.mask)

        h.vm.onIntent(EditorIntent.ClearFx)
        assertTrue(h.c1.fx.isNeutral)
    }

    @Test
    fun `fx are saved with the project and survive a reload`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")
        h.vm.onIntent(EditorIntent.AddEffect(EffectType.CHROMA_KEY))
        h.vm.onIntent(EditorIntent.SetBlendMode(BlendMode.ADD))
        h.vm.onIntent(EditorIntent.Flush)
        advanceUntilIdle()

        val saved = checkNotNull(h.store.project).tracks.first().clips.first()
        assertEquals(listOf("chroma_key"), saved.effects.map { it.type })
        assertEquals("add", saved.blendMode)

        val reloaded = harnessFor(h.store)
        assertEquals(h.c1.fx, reloaded.c1.fx)
    }

    private fun TestScope.harnessFor(store: FakeStore): Harness {
        val vm = EditorViewModel("p1", store, NoImporter(), idGenerator = { "r" })
        val effects = mutableListOf<EditorEffect>()
        advanceUntilIdle()
        return Harness(vm, store, effects)
    }
}
