package com.qtekfun.ultimatevideoeditor.ui.editor

import com.qtekfun.ultimatevideoeditor.data.MediaImportException
import com.qtekfun.ultimatevideoeditor.data.MediaImporter
import com.qtekfun.ultimatevideoeditor.data.ProbedMedia
import com.qtekfun.ultimatevideoeditor.data.ProjectError
import com.qtekfun.ultimatevideoeditor.data.ProjectStore
import com.qtekfun.ultimatevideoeditor.data.model.ClipDto
import com.qtekfun.ultimatevideoeditor.data.model.KeyframeDto
import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto
import com.qtekfun.ultimatevideoeditor.data.model.ProjectDto
import com.qtekfun.ultimatevideoeditor.data.model.ProjectSettingsDto
import com.qtekfun.ultimatevideoeditor.data.model.TrackDto
import com.qtekfun.ultimatevideoeditor.data.model.TransformDto
import com.qtekfun.ultimatevideoeditor.domain.Clip
import com.qtekfun.ultimatevideoeditor.domain.ClipTransform
import com.qtekfun.ultimatevideoeditor.domain.Interpolation
import com.qtekfun.ultimatevideoeditor.domain.Keyframes
import com.qtekfun.ultimatevideoeditor.domain.ProjectColorSpace
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
import com.qtekfun.ultimatevideoeditor.ui.text.english

@OptIn(ExperimentalCoroutinesApi::class)
class KeyframeViewModelTest {

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
        val saved = mutableListOf<ProjectDto>()

        override suspend fun load(id: String): ProjectDto = project ?: throw ProjectError.NotFound(id)

        override suspend fun save(project: ProjectDto) {
            saved += project
            this.project = project
        }
    }

    private class NoImporter : MediaImporter {
        override suspend fun import(uri: String): ProbedMedia = throw MediaImportException("not used")
    }

    private val asset = MediaAssetDto("a1", "content://m/a1", durationFrames = 300, nativeFpsNum = 30, nativeFpsDen = 1, colorSpace = "Rec709-SDR")

    private fun project(keyframes: List<KeyframeDto> = emptyList(), fixed: TransformDto = TransformDto()) = ProjectDto(
        id = "p1",
        name = "Test",
        settings = ProjectSettingsDto(1920, 1080, 30, 1, "Rec709-SDR"),
        mediaLibrary = listOf(asset),
        tracks = listOf(
            TrackDto(
                "v1", "video", 0,
                listOf(
                    ClipDto("c1", "a1", 0, 0, 100, transform = fixed, keyframes = keyframes),
                    ClipDto("c2", "a1", 100, 0, 100),
                ),
            ),
            TrackDto("a1", "audio", 1),
        ),
    )

    private class Harness(val vm: EditorViewModel, val store: FakeStore, val effects: MutableList<EditorEffect>) {
        val state get() = vm.state.value
        val c1: Clip get() = checkNotNull(state.timeline.track("v1")!!.clip("c1"))

        fun select(clipId: String) {
            val ordered = state.visibleTimeline.tracks.flatMap { it.clips }.map { it.id }
            val key = vm.snapshotOf(state).clips[ordered.indexOf(clipId)].clipKey
            vm.onIntent(EditorIntent.TapTimeline(TimelineHit(HitKind.CLIP, 0, key, 0)))
        }

        fun at(frame: Long) = vm.onIntent(EditorIntent.SetPlayhead(frame))

        fun messages(): List<String> = effects.filterIsInstance<EditorEffect.ShowMessage>().map { it.text.english() }
    }

    private fun TestScope.harness(project: ProjectDto = project()): Harness {
        var counter = 0
        val store = FakeStore(project)
        val vm = EditorViewModel("p1", store, NoImporter(), idGenerator = { "n${counter++}" })
        val effects = mutableListOf<EditorEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { effects += it } }
        advanceUntilIdle()
        return Harness(vm, store, effects)
    }

    private val animated = listOf(
        KeyframeDto(0, TransformDto(position = listOf(0.0, 0.0))),
        KeyframeDto(80, TransformDto(position = listOf(800.0, 0.0))),
    )

    @Test
    fun `the diamond adds a keyframe at the playhead holding the pose shown there`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")
        h.at(30)

        h.vm.onIntent(EditorIntent.ToggleKeyframe)

        assertEquals(listOf(30L), h.c1.keyframes.map { it.frame })
        assertEquals(h.c1.transform, h.c1.keyframes.single().transform)
        assertEquals(30L, h.state.keyframeAtPlayhead?.frame)
        assertTrue(h.state.canUndo)
    }

    @Test
    fun `the diamond on a keyframe removes it`() = runTest(dispatcher) {
        val h = harness(project(animated))
        h.select("c1")
        h.at(80)
        assertEquals(80L, h.state.keyframeAtPlayhead?.frame)

        h.vm.onIntent(EditorIntent.ToggleKeyframe)

        assertEquals(listOf(0L), h.c1.keyframes.map { it.frame })
        assertNull(h.state.keyframeAtPlayhead)
    }

    @Test
    fun `a keyframe needs the playhead inside the clip`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")
        h.at(150)

        h.vm.onIntent(EditorIntent.ToggleKeyframe)

        assertTrue(h.c1.keyframes.isEmpty())
        assertTrue(h.messages().any { it.contains("playhead inside the clip") })
    }

    @Test
    fun `without a selection the diamond asks to select a clip`() = runTest(dispatcher) {
        val h = harness()

        h.vm.onIntent(EditorIntent.ToggleKeyframe)

        assertTrue(h.messages().any { it.contains("Select a clip") })
    }

    @Test
    fun `the inspector shows the animated pose at the playhead`() = runTest(dispatcher) {
        val h = harness(project(animated))
        h.select("c1")
        h.at(40)

        assertEquals(400.0, h.state.selectedPose!!.positionX, 1e-9)
        h.at(80)
        assertEquals(800.0, h.state.selectedPose!!.positionX, 1e-9)
    }

    @Test
    fun `editing an animated clip writes a keyframe at the playhead as one undo step`() = runTest(dispatcher) {
        val h = harness(project(animated))
        h.select("c1")
        h.at(40)

        h.vm.onIntent(EditorIntent.UpdateTransform(h.state.selectedPose!!.copy(positionY = 123.0)))
        h.vm.onIntent(EditorIntent.UpdateTransform(h.state.selectedPose!!.copy(positionY = 150.0)))
        h.vm.onIntent(EditorIntent.EndAppearanceEdit(commit = true))

        assertEquals(listOf(0L, 40L, 80L), h.c1.keyframes.map { it.frame })
        val key = Keyframes.at(h.c1.keyframes, 40)!!
        assertEquals(150.0, key.transform.positionY, 0.0)
        assertEquals(400.0, key.transform.positionX, 1e-9)  // the rest of the pose is what was shown
        h.vm.onIntent(EditorIntent.Undo)
        assertEquals(listOf(0L, 80L), h.c1.keyframes.map { it.frame })
    }

    @Test
    fun `a gain edit on an animated clip adds no keyframe`() = runTest(dispatcher) {
        val h = harness(project(animated))
        h.select("c1")
        h.at(40)

        h.vm.onIntent(EditorIntent.UpdateGain(-6.0))
        h.vm.onIntent(EditorIntent.EndAppearanceEdit(commit = true))

        assertEquals(listOf(0L, 80L), h.c1.keyframes.map { it.frame })
        assertEquals(-6.0, h.c1.gainDb, 0.0)
    }

    @Test
    fun `editing an animated clip outside it is refused with a hint`() = runTest(dispatcher) {
        val h = harness(project(animated))
        h.select("c1")
        h.at(150)

        h.vm.onIntent(EditorIntent.UpdateTransform(ClipTransform(positionX = 5.0)))

        assertEquals(listOf(0L, 80L), h.c1.keyframes.map { it.frame })
        assertTrue(h.messages().any { it.contains("inside the clip") })
    }

    @Test
    fun `editing a fixed clip still changes its transform`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")
        h.at(10)

        h.vm.onIntent(EditorIntent.UpdateTransform(ClipTransform(positionX = 9.0)))
        h.vm.onIntent(EditorIntent.EndAppearanceEdit(commit = true))

        assertTrue(h.c1.keyframes.isEmpty())
        assertEquals(9.0, h.c1.transform.positionX, 0.0)
    }

    @Test
    fun `jumping goes to the previous and next keyframe of the clip`() = runTest(dispatcher) {
        val h = harness(project(animated))
        h.select("c1")
        h.at(10)

        h.vm.onIntent(EditorIntent.JumpToKeyframe(forward = true))
        assertEquals(80L, h.state.playhead.value)
        h.vm.onIntent(EditorIntent.JumpToKeyframe(forward = true))
        assertTrue(h.messages().any { it.contains("later keyframe") })
        h.vm.onIntent(EditorIntent.JumpToKeyframe(forward = false))
        assertEquals(0L, h.state.playhead.value)
        h.vm.onIntent(EditorIntent.JumpToKeyframe(forward = false))
        assertTrue(h.messages().any { it.contains("earlier keyframe") })
    }

    @Test
    fun `interpolation is set on the keyframe under the playhead`() = runTest(dispatcher) {
        val h = harness(project(animated))
        h.select("c1")
        h.at(0)

        h.vm.onIntent(EditorIntent.SetKeyframeInterpolation(Interpolation.EASE))
        assertEquals(Interpolation.EASE, Keyframes.at(h.c1.keyframes, 0)!!.interpolation)

        h.at(40)
        h.vm.onIntent(EditorIntent.SetKeyframeInterpolation(Interpolation.HOLD))
        assertTrue(h.messages().any { it.contains("keyframe") })
        assertEquals(Interpolation.EASE, Keyframes.at(h.c1.keyframes, 0)!!.interpolation)
    }

    @Test
    fun `clearing keeps the pose at the playhead`() = runTest(dispatcher) {
        val h = harness(project(animated))
        h.select("c1")
        h.at(40)

        h.vm.onIntent(EditorIntent.ClearKeyframes)

        assertTrue(h.c1.keyframes.isEmpty())
        assertEquals(400.0, h.c1.transform.positionX, 1e-9)
        h.vm.onIntent(EditorIntent.Undo)
        assertEquals(2, h.c1.keyframes.size)
    }

    @Test
    fun `reset removes the animation and the placement in one step`() = runTest(dispatcher) {
        val h = harness(project(animated, fixed = TransformDto(position = listOf(5.0, 5.0))))
        h.select("c1")

        h.vm.onIntent(EditorIntent.ResetAppearance)

        assertTrue(h.c1.keyframes.isEmpty())
        assertTrue(h.c1.transform.isIdentity)
        h.vm.onIntent(EditorIntent.Undo)
        assertEquals(2, h.c1.keyframes.size)
        assertEquals(5.0, h.c1.transform.positionX, 0.0)
    }

    @Test
    fun `splitting an animated clip keeps every pose`() = runTest(dispatcher) {
        val h = harness(project(animated))
        h.select("c1")
        h.at(50)

        h.vm.onIntent(EditorIntent.SplitAtPlayhead)

        val right = h.state.timeline.track("v1")!!.clips.first { it.timelineStart.value == 50L }
        assertEquals(500.0, h.c1.transformAt(49).positionX, 10.0 + 1e-9)
        assertEquals(500.0, right.transformAt(0).positionX, 1e-9)
        assertEquals(800.0, right.transformAt(30).positionX, 1e-9)
    }

    @Test
    fun `the timeline canvas shows the keyframes of every clip`() = runTest(dispatcher) {
        val h = harness(project(animated))

        val snapshot = h.vm.snapshotOf(h.state)

        assertEquals(listOf(0L, 80L), snapshot.keyframes.map { it.frame })
        assertEquals(1, snapshot.keyframes.map { it.clipKey }.toSet().size)
        assertEquals(snapshot.clips.first().clipKey, snapshot.keyframes.first().clipKey)
    }

    @Test
    fun `keyframes are saved with the project`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")
        h.at(20)
        h.vm.onIntent(EditorIntent.ToggleKeyframe)

        h.vm.onIntent(EditorIntent.Flush)
        advanceUntilIdle()

        val saved = h.store.saved.last().tracks.first { it.id == "v1" }.clips.first { it.id == "c1" }
        assertEquals(listOf(20L), saved.keyframes.map { it.frame })
    }

    @Test
    fun `safe zones and the canvas dialog are toggled by intents`() = runTest(dispatcher) {
        val h = harness()

        h.vm.onIntent(EditorIntent.SetSafeZone(SafeZonePlatform.TIKTOK))
        assertEquals(SafeZonePlatform.TIKTOK, h.state.safeZone)
        h.vm.onIntent(EditorIntent.SetSafeZone(null))
        assertNull(h.state.safeZone)

        h.vm.onIntent(EditorIntent.ShowCanvasDialog)
        assertTrue(h.state.canvasDialogOpen)
        h.vm.onIntent(EditorIntent.DismissCanvasDialog)
        assertFalse(h.state.canvasDialogOpen)
    }

    @Test
    fun `changing the canvas rescales positions saves the size and restarts the history`() = runTest(dispatcher) {
        val h = harness(project(animated, fixed = TransformDto(position = listOf(192.0, 108.0))))
        h.select("c1")
        h.vm.onIntent(EditorIntent.UpdateGain(-3.0))
        h.vm.onIntent(EditorIntent.EndAppearanceEdit(commit = true))
        assertTrue(h.state.canUndo)

        h.vm.onIntent(EditorIntent.ShowCanvasDialog)
        h.vm.onIntent(EditorIntent.ChangeCanvas(1080, 1920))
        h.vm.onIntent(EditorIntent.Flush)
        advanceUntilIdle()

        assertEquals(1080 to 1920, h.state.canvasWidth to h.state.canvasHeight)
        assertFalse(h.state.canvasDialogOpen)
        assertFalse(h.state.canUndo)
        assertEquals(192.0 * 1080 / 1920, h.c1.transform.positionX, 1e-9)
        assertEquals(108.0 * 1920 / 1080, h.c1.transform.positionY, 1e-9)
        assertEquals(800.0 * 1080 / 1920, Keyframes.at(h.c1.keyframes, 80)!!.transform.positionX, 1e-9)
        assertEquals(-3.0, h.c1.gainDb, 0.0)
        val saved = h.store.saved.last()
        assertEquals(1080 to 1920, saved.settings.width to saved.settings.height)
    }

    @Test
    fun `choosing the current canvas changes nothing`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")
        h.vm.onIntent(EditorIntent.UpdateGain(-3.0))
        h.vm.onIntent(EditorIntent.EndAppearanceEdit(commit = true))

        h.vm.onIntent(EditorIntent.ChangeCanvas(1920, 1080))

        assertTrue(h.state.canUndo)
        assertEquals(1920 to 1080, h.state.canvasWidth to h.state.canvasHeight)
    }

    @Test
    fun `an invalid canvas is ignored`() = runTest(dispatcher) {
        val h = harness()

        h.vm.onIntent(EditorIntent.ChangeCanvas(0, 1080))

        assertEquals(1920 to 1080, h.state.canvasWidth to h.state.canvasHeight)
    }

    @Test
    fun `an SDR project loads as SDR and switching to HDR is saved without touching the history`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")
        h.vm.onIntent(EditorIntent.UpdateGain(-3.0))
        h.vm.onIntent(EditorIntent.EndAppearanceEdit(commit = true))
        assertEquals(ProjectColorSpace.REC709_SDR, h.state.colorSpace)

        h.vm.onIntent(EditorIntent.ShowCanvasDialog)
        h.vm.onIntent(EditorIntent.ChangeColorSpace(ProjectColorSpace.REC2020_HLG))
        h.vm.onIntent(EditorIntent.Flush)
        advanceUntilIdle()

        assertEquals(ProjectColorSpace.REC2020_HLG, h.state.colorSpace)
        assertFalse(h.state.canvasDialogOpen)
        assertTrue(h.state.canUndo)
        assertEquals("Rec2020-HLG", h.store.saved.last().settings.colorSpace)
        assertEquals(1920 to 1080, h.state.canvasWidth to h.state.canvasHeight)
    }

    @Test
    fun `choosing the current colour space saves nothing`() = runTest(dispatcher) {
        val h = harness()

        h.vm.onIntent(EditorIntent.ChangeColorSpace(ProjectColorSpace.REC709_SDR))
        h.vm.onIntent(EditorIntent.Flush)
        advanceUntilIdle()

        assertTrue(h.store.saved.isEmpty())
    }
}
