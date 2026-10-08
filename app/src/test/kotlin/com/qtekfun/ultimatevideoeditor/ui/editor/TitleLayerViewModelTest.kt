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
import com.qtekfun.ultimatevideoeditor.domain.MotionPreset
import com.qtekfun.ultimatevideoeditor.domain.ShapeKind
import com.qtekfun.ultimatevideoeditor.domain.TextLayer
import com.qtekfun.ultimatevideoeditor.domain.TitleContent
import com.qtekfun.ultimatevideoeditor.domain.TitleLayerEdit
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
class TitleLayerViewModelTest {
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

    private class NoProbe : MediaImporter {
        override suspend fun import(uri: String): ProbedMedia = throw MediaImportException("not probed")
    }

    private val asset = MediaAssetDto("a1", "content://m/a1", durationFrames = 300, nativeFpsNum = 30, nativeFpsDen = 1, colorSpace = "Rec709-SDR")

    private val project = ProjectDto(
        id = "p1",
        name = "Test",
        settings = ProjectSettingsDto(1920, 1080, 30, 1, "Rec709-SDR"),
        mediaLibrary = listOf(asset),
        tracks = listOf(TrackDto("v1", "video", 0, listOf(ClipDto("c1", "a1", 0, 0, 200))), TrackDto("a1", "audio", 1)),
    )

    private class Harness(val vm: EditorViewModel, val effects: MutableList<EditorEffect>) {
        val state get() = vm.state.value

        fun messages() = effects.filterIsInstance<EditorEffect.ShowMessage>().map { it.text }

        val title get() = state.timeline.tracks.flatMap { it.clips }.first { it.title != null }
    }

    private fun TestScope.harness(): Harness {
        var counter = 0
        val vm = EditorViewModel("p1", FakeStore(project), NoProbe(), idGenerator = { "n${counter++}" })
        val effects = mutableListOf<EditorEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { effects += it } }
        advanceUntilIdle()
        return Harness(vm, effects)
    }

    /** A selected multilayer title (a bar and a text) lasting from frame 0, with the playhead inside it. */
    private fun Harness.layeredTitle() {
        vm.onIntent(EditorIntent.AddTitle)
        val layered = TitleLayerEdit.add(TitleLayerEdit.toLayered(title.title!!), TitleLayerEdit.newShape(ShapeKind.RECT))
        vm.onIntent(EditorIntent.UpdateTitle(layered))
        vm.onIntent(EditorIntent.EndTitleEdit(commit = true))
    }

    @Test
    fun `converting a title to layers and adding a layer are separate undo steps that undo cleanly`() = runTest(dispatcher) {
        val h = harness()
        h.vm.onIntent(EditorIntent.AddTitle)
        val plain = h.title.title!!
        val layered = TitleLayerEdit.toLayered(plain)
        h.vm.onIntent(EditorIntent.UpdateTitle(layered))
        h.vm.onIntent(EditorIntent.EndTitleEdit(commit = true))
        assertTrue(h.title.title!!.isLayered)
        h.vm.onIntent(EditorIntent.UpdateTitle(TitleLayerEdit.add(layered, TitleLayerEdit.newShape(ShapeKind.ELLIPSE))))
        h.vm.onIntent(EditorIntent.EndTitleEdit(commit = true))
        assertEquals(2, h.title.title!!.layers.size)

        h.vm.onIntent(EditorIntent.Undo)
        assertEquals(1, h.title.title!!.layers.size)
        h.vm.onIntent(EditorIntent.Undo)
        assertFalse(h.title.title!!.isLayered)
        assertEquals(plain, h.title.title)
    }

    @Test
    fun `selecting a layer is tied to the selected clip and ignores a stale index`() = runTest(dispatcher) {
        val h = harness()
        h.layeredTitle()
        assertNull(h.state.selectedTitleLayer)
        h.vm.onIntent(EditorIntent.SelectTitleLayer(1))
        assertEquals(1, h.state.selectedTitleLayer)
        // Removing layers leaves the index out of range: nothing is selected rather than a wrong layer.
        h.vm.onIntent(EditorIntent.UpdateTitle(TitleLayerEdit.remove(h.title.title!!, 1)))
        h.vm.onIntent(EditorIntent.EndTitleEdit(commit = true))
        assertNull(h.state.selectedTitleLayer)
        h.vm.onIntent(EditorIntent.SelectTitleLayer(-1))
        assertNull(h.state.selectedTitleLayer)
    }

    @Test
    fun `a preview gesture moves scales and turns only the selected layer as one undo step`() = runTest(dispatcher) {
        val h = harness()
        h.layeredTitle()
        h.vm.onIntent(EditorIntent.SelectTitleLayer(1))
        val before = h.title.title!!

        h.vm.onIntent(EditorIntent.LayerGesture(panX = 192.0, panY = -108.0, zoom = 2.0, rotationDegrees = 15.0))
        h.vm.onIntent(EditorIntent.LayerGesture(panX = 0.0, panY = 0.0, zoom = 1.0, rotationDegrees = 5.0))
        // Provisional until the gesture ends: the committed timeline is unchanged.
        assertEquals(before, h.state.timeline.tracks.flatMap { it.clips }.first { it.title != null }.title)
        h.vm.onIntent(EditorIntent.EndAppearanceEdit(commit = true))

        val after = h.title.title!!
        assertEquals(before.layers[0], after.layers[0])
        val moved = after.layers[1].placement
        assertEquals(0.1, moved.offsetX, 1e-9)
        assertEquals(-0.1, moved.offsetY, 1e-9)
        assertEquals(2.0, moved.scale, 1e-9)
        assertEquals(20.0, moved.rotationDegrees, 1e-9)
        h.vm.onIntent(EditorIntent.Undo)
        assertEquals(before, h.title.title)
    }

    @Test
    fun `gestures clamp offsets and scale and do nothing without a selected layer`() = runTest(dispatcher) {
        val h = harness()
        h.layeredTitle()
        val before = h.title.title!!
        h.vm.onIntent(EditorIntent.LayerGesture(10.0, 10.0, 2.0, 0.0))
        h.vm.onIntent(EditorIntent.EndAppearanceEdit(commit = true))
        assertEquals(before, h.title.title)

        h.vm.onIntent(EditorIntent.SelectTitleLayer(0))
        h.vm.onIntent(EditorIntent.LayerGesture(1_000_000.0, 1_000_000.0, 1_000.0, 0.0))
        h.vm.onIntent(EditorIntent.EndAppearanceEdit(commit = true))
        val placement = h.title.title!!.layers[0].placement
        assertEquals(2.0, placement.offsetX, 0.0)
        assertEquals(2.0, placement.offsetY, 0.0)
        assertEquals(20.0, placement.scale, 0.0)
    }

    @Test
    fun `an in and out animation becomes keyframes in one undo step and needs a title`() = runTest(dispatcher) {
        val h = harness()
        h.layeredTitle()
        h.vm.onIntent(EditorIntent.ApplyTitleMotion(MotionPreset.SLIDE_LEFT, MotionPreset.FADE))
        val keys = h.title.keyframes
        assertEquals(0L, keys.first().frame)
        assertEquals(0.0, keys.first().transform.opacity, 0.0)
        assertTrue(keys.size >= 3)
        h.vm.onIntent(EditorIntent.Undo)
        assertTrue(h.title.keyframes.isEmpty())
    }

    @Test
    fun `an animation needs a selected title`() = runTest(dispatcher) {
        val h = harness()
        h.vm.onIntent(EditorIntent.ApplyTitleMotion(MotionPreset.FADE, MotionPreset.FADE))
        assertTrue(h.messages().last().contains("Select"))
    }

    @Test
    fun `fonts the project names but the device lacks are reported until they are imported`() = runTest(dispatcher) {
        val h = harness()
        h.vm.onIntent(EditorIntent.AddTitle)
        val withFont = TitleLayerEdit.of(listOf(TextLayer("Hi", fontId = "0123456789abcdef"), TextLayer("There")))
        h.vm.onIntent(EditorIntent.UpdateTitle(withFont))
        h.vm.onIntent(EditorIntent.EndTitleEdit(commit = true))
        assertEquals(setOf("0123456789abcdef"), h.state.missingFonts)
        h.vm.onIntent(EditorIntent.FontsChanged(setOf("0123456789abcdef")))
        assertTrue(h.state.missingFonts.isEmpty())
        h.vm.onIntent(EditorIntent.FontsChanged(emptySet()))
        assertEquals(1, h.state.missingFonts.size)
        assertNotNull(h.title.title)
        assertEquals(TitleContent("Hi", layers = withFont.layers).layers, h.title.title!!.layers)
    }
}
