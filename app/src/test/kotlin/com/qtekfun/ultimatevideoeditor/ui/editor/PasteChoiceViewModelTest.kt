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
import com.qtekfun.ultimatevideoeditor.domain.FrameIndex
import com.qtekfun.ultimatevideoeditor.engine.timeline.HitKind
import com.qtekfun.ultimatevideoeditor.engine.timeline.TimelineHit
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import com.qtekfun.ultimatevideoeditor.ui.text.english

/** Paste asks content or style when another clip is selected; with nothing else selected it pastes the content at once. */
@OptIn(ExperimentalCoroutinesApi::class)
class PasteChoiceViewModelTest {
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

    private object NoImporter : MediaImporter {
        override suspend fun import(uri: String): ProbedMedia = throw MediaImportException("Unsupported file")
    }

    private class FakeOutput : PlaybackOutput {
        val calls = mutableListOf<String>()
        var heard: Long? = null

        override fun play(fromFrame: Long) {
            calls += "play($fromFrame)"
        }

        override fun pause() {
            calls += "pause"
        }

        override fun seek(frame: Long) {
            calls += "seek($frame)"
        }

        override fun heardFrame(): Long? = heard
    }

    private val settings = ProjectSettingsDto(1920, 1080, 30, 1, "Rec709-SDR")
    private val asset = MediaAssetDto("a1", "content://m/a1", durationFrames = 600, nativeFpsNum = 30, nativeFpsDen = 1, colorSpace = "Rec709-SDR")

    private fun clipDto(id: String, start: Long, len: Long = 100) =
        ClipDto(id, "a1", timelineStartFrame = start, sourceInFrame = 50, sourceOutFrame = 50 + len)

    /** Overlay v2 (x 0..100, y 100..200, z 300..400) over the base v1 (c1 0..100, c2 100..200, c3 200..300). */
    private fun project() = ProjectDto(
        id = "p1",
        name = "Test",
        settings = settings,
        mediaLibrary = listOf(asset),
        tracks = listOf(
            TrackDto("v2", "video", 0, listOf(clipDto("x", 0), clipDto("y", 100), clipDto("z", 300))),
            TrackDto("v1", "video", 1, listOf(clipDto("c1", 0).copy(gainDb = -6.0), clipDto("c2", 100), clipDto("c3", 200))),
            TrackDto("a1", "audio", 2),
        ),
    )

    private class Harness(val vm: EditorViewModel, val out: FakeOutput, val effects: MutableList<EditorEffect>) {
        val state get() = vm.state.value
        fun clips(trackId: String): List<Clip> = state.timeline.track(trackId)?.clips.orEmpty()
        fun messages(): List<String> = effects.filterIsInstance<EditorEffect.ShowMessage>().map { it.text.english() }

        // Keys are assigned lazily by snapshotOf, so touch every clip once before looking one up.
        private fun keyOf(clipId: String): Long {
            val ordered = state.visibleTimeline.tracks.flatMap { it.clips }.map { it.id }
            return vm.snapshotOf(state).clips[ordered.indexOf(clipId)].clipKey
        }

        private fun hit(clipId: String) = TimelineHit(HitKind.CLIP, 0, keyOf(clipId), 5)
        fun select(vararg ids: String) {
            vm.onIntent(EditorIntent.TapTimeline(hit(ids.first())))
            ids.drop(1).forEach { vm.onIntent(SelectionIntent.LongPress(hit(it))) }
        }
    }

    private fun TestScope.harness(dto: ProjectDto = project()): Harness {
        var counter = 0
        val vm = EditorViewModel(
            "p1",
            FakeStore(dto),
            NoImporter,
            idGenerator = { "n${counter++}" },
            nanoClock = { testScheduler.currentTime * 1_000_000 },
        )
        val out = FakeOutput().also { vm.playbackOutput = it }
        val effects = mutableListOf<EditorEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { effects += it } }
        advanceUntilIdle()
        return Harness(vm, out, effects)
    }

    private fun Harness.copyOf(id: String) {
        select(id)
        vm.onIntent(SelectionIntent.Copy)
    }

    @Test
    fun `paste with another clip selected asks first and changes nothing yet`() = runTest(dispatcher) {
        val h = harness()
        h.copyOf("c1")
        h.select("c2")
        val before = h.clips("v1").size

        h.vm.onIntent(SelectionIntent.Paste)

        assertEquals(listOf("c2"), h.state.pasteChoice?.destinations)
        assertEquals(1, h.state.pasteChoice?.copiedClips)
        assertEquals(before, h.clips("v1").size)
        assertEquals(0.0, h.clips("v1").first { it.id == "c2" }.gainDb, 0.0)
    }

    @Test
    fun `Content pastes a copy of the copied clips at the playhead and closes the question`() = runTest(dispatcher) {
        val h = harness()
        h.copyOf("c1")
        h.select("c2")
        h.vm.onIntent(SelectionIntent.Paste)
        val before = h.clips("v1").size

        h.vm.onIntent(SelectionIntent.PasteContent)

        assertEquals(null, h.state.pasteChoice)
        assertEquals(before + 1, h.clips("v1").size)
        assertEquals(2, h.clips("v1").count { it.gainDb == -6.0 }) // c1 and its copy: the content carries the clip's own settings
    }

    @Test
    fun `Style applies the look and sound of the copied clip to the selected clip and adds no clip`() = runTest(dispatcher) {
        val h = harness()
        h.copyOf("c1")
        h.select("c2")
        h.vm.onIntent(SelectionIntent.Paste)
        val before = h.clips("v1").map { it.id }

        h.vm.onIntent(SelectionIntent.PasteStyle)

        assertEquals(null, h.state.pasteChoice)
        assertEquals(before, h.clips("v1").map { it.id })
        assertEquals(-6.0, h.clips("v1").first { it.id == "c2" }.gainDb, 0.0)
        assertEquals(0.0, h.clips("v1").first { it.id == "c3" }.gainDb, 0.0) // only the selected clip
        h.vm.onIntent(EditorIntent.Undo)
        assertEquals(0.0, h.clips("v1").first { it.id == "c2" }.gainDb, 0.0) // one undo step
    }

    @Test
    fun `Style goes to every selected clip except the copied ones`() = runTest(dispatcher) {
        val h = harness()
        h.copyOf("c1")
        h.select("c1", "c2", "c3")
        h.vm.onIntent(SelectionIntent.Paste)

        assertEquals(setOf("c2", "c3"), h.state.pasteChoice?.destinations?.toSet())
        h.vm.onIntent(SelectionIntent.PasteStyle)

        assertEquals(-6.0, h.clips("v1").first { it.id == "c2" }.gainDb, 0.0)
        assertEquals(-6.0, h.clips("v1").first { it.id == "c3" }.gainDb, 0.0)
    }

    @Test
    fun `cancelling the question changes nothing`() = runTest(dispatcher) {
        val h = harness()
        h.copyOf("c1")
        h.select("c2")
        h.vm.onIntent(SelectionIntent.Paste)
        val before = h.clips("v1")

        h.vm.onIntent(SelectionIntent.DismissPasteChoice)

        assertEquals(null, h.state.pasteChoice)
        assertEquals(before, h.clips("v1"))
    }

    @Test
    fun `with nothing else selected paste is not a question and pastes the content at once`() = runTest(dispatcher) {
        val h = harness()
        h.copyOf("c1")
        val before = h.clips("v1").size
        // The copied clip itself is still the selection.
        h.vm.onIntent(SelectionIntent.Paste)
        assertEquals(null, h.state.pasteChoice)
        assertEquals(before + 1, h.clips("v1").size)

        // Nothing selected at all.
        h.vm.onIntent(SelectionIntent.ClearSelection)
        h.vm.onIntent(SelectionIntent.Paste)
        assertEquals(null, h.state.pasteChoice)
        assertEquals(before + 2, h.clips("v1").size)
    }

    @Test
    fun `paste with an empty clipboard says so and asks nothing`() = runTest(dispatcher) {
        val h = harness()
        h.select("c2")

        h.vm.onIntent(SelectionIntent.Paste)

        assertEquals(null, h.state.pasteChoice)
        assertTrue(h.messages().isNotEmpty())
    }
}
