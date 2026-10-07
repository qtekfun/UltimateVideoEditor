package com.ultimatevideo.uveditor.ui.editor

import com.ultimatevideo.uveditor.data.MediaImportException
import com.ultimatevideo.uveditor.data.MediaImporter
import com.ultimatevideo.uveditor.data.ProbedMedia
import com.ultimatevideo.uveditor.data.ProjectError
import com.ultimatevideo.uveditor.data.ProjectStore
import com.ultimatevideo.uveditor.data.model.ClipDto
import com.ultimatevideo.uveditor.data.model.MediaAssetDto
import com.ultimatevideo.uveditor.data.model.ProjectDto
import com.ultimatevideo.uveditor.data.model.ProjectSettingsDto
import com.ultimatevideo.uveditor.data.model.TrackDto
import com.ultimatevideo.uveditor.domain.Clip
import com.ultimatevideo.uveditor.domain.FrameIndex
import com.ultimatevideo.uveditor.engine.timeline.HitKind
import com.ultimatevideo.uveditor.engine.timeline.TimelineHit
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

/** Where the playhead, the output, the preview source and the selection are after a split (SPECS 5.3 / DECISIONS "Split lands on the cut"). */
@OptIn(ExperimentalCoroutinesApi::class)
class SplitPlayheadViewModelTest {
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
            TrackDto("v1", "video", 1, listOf(clipDto("c1", 0), clipDto("c2", 100), clipDto("c3", 200))),
            TrackDto("a1", "audio", 2),
        ),
    )

    private class Harness(val vm: EditorViewModel, val out: FakeOutput, val effects: MutableList<EditorEffect>) {
        val state get() = vm.state.value
        fun clips(trackId: String): List<Clip> = state.timeline.track(trackId)?.clips.orEmpty()
        fun messages(): List<String> = effects.filterIsInstance<EditorEffect.ShowMessage>().map { it.text }

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

    private fun TestScope.harness(): Harness {
        var counter = 0
        val vm = EditorViewModel(
            "p1",
            FakeStore(project()),
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

    @Test
    fun `after a split the playhead is on the first frame of the right part and the output is moved there`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")
        h.vm.onIntent(EditorIntent.SetPlayhead(40))
        h.out.calls.clear()

        h.vm.onIntent(EditorIntent.SplitAtPlayhead)

        assertEquals(FrameIndex(40), h.state.playhead)
        assertEquals(FrameIndex(40), h.clips("v1").first { it.id == "c1~n0" }.timelineStart)
        assertEquals("seek(40)", h.out.calls.last())
        assertFalse(h.state.isPlaying)
    }

    @Test
    fun `the selection moves to the right part so the next cut needs no new selection`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")
        h.vm.onIntent(EditorIntent.SetPlayhead(40))
        h.vm.onIntent(EditorIntent.SplitAtPlayhead)
        assertEquals("c1~n0", h.state.selectedClipId)

        h.vm.onIntent(EditorIntent.SetPlayhead(70))
        h.vm.onIntent(EditorIntent.SplitAtPlayhead)

        assertEquals(listOf("c1", "c1~n0", "c1~n0~n1", "c2", "c3").sorted(), h.clips("v1").map { it.id }.sorted())
        assertEquals(FrameIndex(70), h.state.playhead)
        assertEquals("c1~n0~n1", h.state.selectedClipId)
    }

    @Test
    fun `splitting while playing pauses exactly at the cut and playback stays stopped`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")
        h.vm.onIntent(EditorIntent.TogglePlay)
        advanceTimeBy(1_000)
        val cut = h.state.playhead
        assertTrue(h.state.isPlaying)
        assertTrue(cut.value in 1..99)

        h.vm.onIntent(EditorIntent.SplitAtPlayhead)
        advanceTimeBy(3_000)

        assertFalse(h.state.isPlaying)
        assertEquals(cut, h.state.playhead)
        assertEquals(cut, h.clips("v1").first { it.id == "c1~n0" }.timelineStart)
        assertEquals(listOf("play(0)", "pause", "seek(${cut.value})"), h.out.calls)
    }

    @Test
    fun `a failed split while playing does not stop playback`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")
        h.vm.onIntent(EditorIntent.TogglePlay) // the playhead is still on frame 0, the clip's first frame: no cut there
        h.vm.onIntent(EditorIntent.SplitAtPlayhead)
        advanceTimeBy(1)

        assertTrue(h.state.isPlaying)
        assertEquals(1, h.messages().size)
        h.vm.onIntent(EditorIntent.TogglePlay)
    }

    @Test
    fun `every selected clip under the playhead is cut in one undo step and the others are left alone`() = runTest(dispatcher) {
        val h = harness()
        h.select("x", "c1", "z")
        h.vm.onIntent(EditorIntent.SetPlayhead(30))

        h.vm.onIntent(EditorIntent.SplitAtPlayhead)

        assertEquals(listOf("x", "x~n0", "y", "z"), h.clips("v2").map { it.id })
        assertEquals(listOf("c1", "c1~n1", "c2", "c3"), h.clips("v1").map { it.id })
        assertEquals(FrameIndex(30), h.state.playhead)
        // The parts to the right of the cut are selected; z, which the playhead is not over, stays selected.
        assertEquals(setOf("x~n0", "c1~n1", "z"), h.state.selection)

        h.vm.onIntent(EditorIntent.Undo)
        assertEquals(listOf("x", "y", "z"), h.clips("v2").map { it.id })
        assertEquals(listOf("c1", "c2", "c3"), h.clips("v1").map { it.id })
        assertFalse(h.state.canUndo)
    }

    @Test
    fun `undo and redo of a split leave the playhead where it was and the redone cut is still there`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")
        h.vm.onIntent(EditorIntent.SetPlayhead(40))
        h.vm.onIntent(EditorIntent.SplitAtPlayhead)

        h.vm.onIntent(EditorIntent.Undo)
        assertEquals(FrameIndex(40), h.state.playhead)
        assertEquals(listOf("c1", "c2", "c3"), h.clips("v1").map { it.id })

        h.vm.onIntent(EditorIntent.Redo)
        assertEquals(FrameIndex(40), h.state.playhead)
        assertEquals(FrameIndex(40), h.clips("v1").first { it.id == "c1~n0" }.timelineStart)
    }

    @Test
    fun `without a selection the split asks for one and nothing moves`() = runTest(dispatcher) {
        val h = harness()
        h.vm.onIntent(EditorIntent.SetPlayhead(40))

        h.vm.onIntent(EditorIntent.SplitAtPlayhead)

        assertEquals(1, h.messages().size)
        assertEquals(FrameIndex(40), h.state.playhead)
        assertFalse(h.state.canUndo)
    }
}
