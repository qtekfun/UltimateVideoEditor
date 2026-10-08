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
import com.qtekfun.ultimatevideoeditor.domain.SpeedKey
import com.qtekfun.ultimatevideoeditor.domain.SpeedRamps
import com.qtekfun.ultimatevideoeditor.domain.isFreeze
import com.qtekfun.ultimatevideoeditor.domain.isRetimed
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
class SpeedViewModelTest {

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

    private val asset = MediaAssetDto("a1", "content://m/a1", durationFrames = 600, nativeFpsNum = 30, nativeFpsDen = 1, colorSpace = "Rec709-SDR")

    private val project = ProjectDto(
        id = "p1",
        name = "Test",
        settings = ProjectSettingsDto(1920, 1080, 30, 1, "Rec709-SDR"),
        mediaLibrary = listOf(asset),
        tracks = listOf(
            TrackDto(
                "v1", "video", 0,
                listOf(ClipDto("c1", "a1", 0, 0, 100), ClipDto("c2", "a1", 100, 0, 100)),
            ),
            TrackDto("a1", "audio", 1),
        ),
    )

    private class Harness(val vm: EditorViewModel, val store: FakeStore, val effects: MutableList<EditorEffect>) {
        val state get() = vm.state.value

        fun clip(id: String): Clip? = state.timeline.trackOfClip(id)?.clip(id)

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
    fun `speeding a clip up shortens it and pulls the next clip with it`() = runTest {
        val h = harness()
        h.select("c1")

        h.vm.onIntent(EditorIntent.SetSpeed(200, 100))

        assertEquals(50L, h.clip("c1")!!.durationFrames)
        assertEquals(50L, h.clip("c2")!!.timelineStart.value)
        assertTrue(h.state.canUndo)
    }

    @Test
    fun `slowing a clip down pushes the next clip later instead of failing`() = runTest {
        val h = harness()
        h.select("c1")

        h.vm.onIntent(EditorIntent.SetSpeed(50, 100))

        assertEquals(200L, h.clip("c1")!!.durationFrames)
        assertEquals(200L, h.clip("c2")!!.timelineStart.value)
        assertTrue(h.messages().isEmpty())
    }

    @Test
    fun `a speed outside the range is reported and changes nothing`() = runTest {
        val h = harness()
        h.select("c1")

        h.vm.onIntent(EditorIntent.SetSpeed(10100, 100))

        assertEquals(100L, h.clip("c1")!!.durationFrames)
        assertTrue(h.messages().single().startsWith("That speed is not possible"))
    }

    @Test
    fun `speed edits undo in one step`() = runTest {
        val h = harness()
        h.select("c1")
        h.vm.onIntent(EditorIntent.SetSpeed(200, 100))

        h.vm.onIntent(EditorIntent.Undo)

        assertEquals(100L, h.clip("c1")!!.durationFrames)
        assertEquals(100L, h.clip("c2")!!.timelineStart.value)
        assertFalse(h.clip("c1")!!.isRetimed)
    }

    @Test
    fun `reverse toggles on and off`() = runTest {
        val h = harness()
        h.select("c1")

        h.vm.onIntent(EditorIntent.ToggleReverse)
        assertTrue(h.clip("c1")!!.reverse)
        h.vm.onIntent(EditorIntent.ToggleReverse)
        assertFalse(h.clip("c1")!!.reverse)
    }

    @Test
    fun `a ramp preset shapes the clip and none removes it`() = runTest {
        val h = harness()
        h.select("c1")

        h.vm.onIntent(EditorIntent.SetSpeedRamp(SpeedRampShape.BELL))
        assertEquals(SpeedRamps.bell(100), h.clip("c1")!!.speedRamp)
        h.vm.onIntent(EditorIntent.SetSpeedRamp(SpeedRampShape.NONE))
        assertTrue(h.clip("c1")!!.speedRamp.isEmpty())
    }

    @Test
    fun `eased presets and the montage hero and bullet curves apply as one undo step`() = runTest {
        val h = harness()
        h.select("c1")
        val expected = mapOf(
            SpeedRampShape.MONTAGE to SpeedRamps.montage(100),
            SpeedRampShape.HERO to SpeedRamps.hero(100),
            SpeedRampShape.BULLET to SpeedRamps.bullet(100),
            SpeedRampShape.EASE_IN_SMOOTH to SpeedRamps.easeInSmooth(100),
            SpeedRampShape.EASE_OUT_SMOOTH to SpeedRamps.easeOutSmooth(100),
        )
        for ((shape, ramp) in expected) {
            h.vm.onIntent(EditorIntent.SetSpeedRamp(shape))
            assertEquals(shape.name, ramp, h.clip("c1")!!.speedRamp)
            assertTrue(h.clip("c1")!!.speedRamp.dropLast(1).all { it.smooth })
        }
        // The last applied shape (ease out, round) goes back to the one before it (ease in, round) with one undo.
        h.vm.onIntent(EditorIntent.Undo)
        assertEquals(SpeedRamps.easeInSmooth(100), h.clip("c1")!!.speedRamp)
    }

    @Test
    fun `the curve editor sets exact keys in one step and an empty list removes the curve`() = runTest {
        val h = harness()
        h.select("c1")
        val keys = listOf(SpeedKey(0, 400, true), SpeedKey(50, 2000, true), SpeedKey(99, 600))

        h.vm.onIntent(EditorIntent.SetSpeedKeys(keys))
        assertEquals(keys, h.clip("c1")!!.speedRamp)
        h.vm.onIntent(EditorIntent.SetSpeedKeys(emptyList()))
        assertTrue(h.clip("c1")!!.speedRamp.isEmpty())
        h.vm.onIntent(EditorIntent.Undo)
        assertEquals(keys, h.clip("c1")!!.speedRamp)
    }

    @Test
    fun `smooth slow motion toggles on a slowed clip and undoes`() = runTest {
        val h = harness()
        h.select("c1")
        h.vm.onIntent(EditorIntent.SetSpeed(50, 100))
        assertFalse(h.clip("c1")!!.smoothSlowMo)

        h.vm.onIntent(EditorIntent.ToggleSmoothSlowMo)
        assertTrue(h.clip("c1")!!.smoothSlowMo)
        h.vm.onIntent(EditorIntent.Undo)
        assertFalse(h.clip("c1")!!.smoothSlowMo)
        h.vm.onIntent(EditorIntent.Redo)
        assertTrue(h.clip("c1")!!.smoothSlowMo)
    }

    @Test
    fun `a hundred times speed is accepted`() = runTest {
        val h = harness()
        h.select("c1")

        h.vm.onIntent(EditorIntent.SetSpeed(10000, 100))

        assertEquals(1L, h.clip("c1")!!.durationFrames)
        assertTrue(h.messages().isEmpty())
    }

    @Test
    fun `freezing a frame splits the clip, holds the frame and selects it`() = runTest {
        val h = harness()
        h.select("c1")
        h.vm.onIntent(EditorIntent.SetPlayhead(40))

        h.vm.onIntent(EditorIntent.FreezeFrame)

        val still = checkNotNull(h.clip(h.state.selectedClipId!!))
        assertTrue(still.isFreeze)
        assertEquals(40L, still.timelineStart.value)
        assertEquals(60L, still.durationFrames) // two seconds at 30 fps
        assertEquals(40L, still.sourceIn.value)
        // The rest of the clip and the next one moved later by the freeze's length.
        assertEquals(160L, h.clip("c2")!!.timelineStart.value)
    }

    @Test
    fun `freezing outside the clip asks to move the playhead`() = runTest {
        val h = harness()
        h.select("c1")
        h.vm.onIntent(EditorIntent.SetPlayhead(150))

        h.vm.onIntent(EditorIntent.FreezeFrame)

        assertTrue(h.messages().single().startsWith("Move the playhead"))
        assertEquals(100L, h.clip("c1")!!.durationFrames)
    }

    @Test
    fun `the canvas snapshot tells the native side about retimed clips`() = runTest {
        val h = harness()
        h.select("c1")
        h.vm.onIntent(EditorIntent.SetSpeed(200, 100))
        h.vm.onIntent(EditorIntent.ToggleReverse)

        val snapshot = h.vm.snapshotOf(h.state)

        val retime = snapshot.retimes.single()
        assertEquals(100L, retime.sourceSpanFrames)
        assertTrue(retime.reverse)
        assertFalse(retime.freeze)
        assertEquals(snapshot.clips.first().clipKey, retime.clipKey)
    }

    @Test
    fun `speed changes are saved with the project`() = runTest {
        val h = harness()
        h.select("c1")
        h.vm.onIntent(EditorIntent.SetSpeed(200, 100))
        h.vm.onIntent(EditorIntent.ToggleReverse)
        h.vm.onIntent(EditorIntent.Flush)
        advanceUntilIdle()

        val saved = h.store.saved.last().tracks.first { it.id == "v1" }.clips.first { it.id == "c1" }
        assertEquals(50L, saved.timelineFrames)
        assertTrue(saved.reverse)
        assertEquals(100L, saved.sourceOutFrame - saved.sourceInFrame)
    }
}
