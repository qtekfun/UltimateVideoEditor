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
import com.qtekfun.ultimatevideoeditor.domain.FrameIndex
import com.qtekfun.ultimatevideoeditor.engine.timeline.HitKind
import com.qtekfun.ultimatevideoeditor.engine.timeline.TimelineHit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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

/**
 * A swipe on the timeline while the project plays stops playback (DECISIONS "Scrubbing takes control of playback"). The touch
 * stream is fed through the real [ScrubGate] into the same `TimelineEditing` callback the canvas uses, then the real view model.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScrubStopsPlaybackViewModelTest {
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

    private fun clipDto(id: String, start: Long) = ClipDto(id, "a1", timelineStartFrame = start, sourceInFrame = 0, sourceOutFrame = 100)

    private fun project() = ProjectDto(
        id = "p1",
        name = "Test",
        settings = settings,
        mediaLibrary = listOf(asset),
        tracks = listOf(
            TrackDto("v1", "video", 0, listOf(clipDto("c1", 0), clipDto("c2", 100), clipDto("c3", 200))),
            TrackDto("a1", "audio", 1),
        ),
    )

    private class Harness(val vm: EditorViewModel, val out: FakeOutput) {
        val state get() = vm.state.value

        /** What the canvas wires up: the gate in front of the editing callback that sends the intent. */
        private val gate = ScrubGate(slopPx = 16f)
        private val editing = object : TimelineEditing {
            override fun canDrag(hit: TimelineHit) = vm.canDrag(hit)
            override fun onDragStart(hit: TimelineHit) = vm.onIntent(EditorIntent.DragStart(hit))
            override fun onDragMove(hit: TimelineHit) = vm.onIntent(EditorIntent.DragMove(hit.frame, hit.trackIndex))
            override fun onDragEnd(commit: Boolean) = vm.onIntent(EditorIntent.DragEnd(commit))
            override fun onScrub() = vm.onIntent(EditorIntent.ScrubStarted)
        }

        fun down() = gate.begin()
        fun scroll(dx: Float, dy: Float = 0f) {
            if (gate.onScroll(dx, dy)) editing.onScrub()
        }

        fun fling(vx: Float, vy: Float = 0f) {
            if (gate.onFling(vx, vy)) editing.onScrub()
        }
    }

    private fun TestScope.harness(): Harness {
        val vm = EditorViewModel("p1", FakeStore(project()), NoImporter, nanoClock = { testScheduler.currentTime * 1_000_000 })
        val out = FakeOutput().also { vm.playbackOutput = it }
        advanceUntilIdle()
        return Harness(vm, out)
    }

    @Test
    fun `swiping the timeline while playing stops playback at the playhead and parks the output there`() = runTest(dispatcher) {
        val h = harness()
        h.vm.onIntent(EditorIntent.TogglePlay)
        advanceTimeBy(1_000)
        val at = h.state.playhead
        h.out.heard = at.value + 2 // the audio clock is ahead of the last tick: the playhead must not jump to it
        h.out.calls.clear()

        h.down()
        h.scroll(4f)
        assertTrue("below the slop playback goes on", h.state.isPlaying)
        h.scroll(30f)

        assertFalse(h.state.isPlaying)
        assertEquals(at, h.state.playhead)
        assertEquals(listOf("pause", "seek(${at.value})"), h.out.calls)
        h.scroll(30f)
        advanceTimeBy(5_000)
        assertEquals(at, h.state.playhead)
        assertFalse(h.state.isPlaying)
        assertEquals("the swipe sends no more than one pause", 2, h.out.calls.size)
    }

    @Test
    fun `a fling that starts while playing stops it too`() = runTest(dispatcher) {
        val h = harness()
        h.vm.onIntent(EditorIntent.TogglePlay)
        advanceTimeBy(500)

        h.down()
        h.fling(3000f)

        assertFalse(h.state.isPlaying)
    }

    @Test
    fun `a vertical swipe over the lanes does not stop playback`() = runTest(dispatcher) {
        val h = harness()
        h.vm.onIntent(EditorIntent.TogglePlay)
        advanceTimeBy(500)

        h.down()
        h.scroll(3f, 40f)
        h.scroll(2f, 40f)
        h.fling(100f, 2500f)

        assertTrue(h.state.isPlaying)
        h.vm.onIntent(EditorIntent.TogglePlay)
    }

    @Test
    fun `swiping while paused changes nothing and Play afterwards resumes from the scrubbed frame`() = runTest(dispatcher) {
        val h = harness()
        h.vm.onIntent(EditorIntent.SetPlayhead(120))
        h.out.calls.clear()

        h.down()
        h.scroll(40f)

        assertEquals(FrameIndex(120), h.state.playhead)
        assertTrue(h.out.calls.isEmpty())

        h.vm.onIntent(EditorIntent.TogglePlay)
        assertTrue(h.state.isPlaying)
        assertEquals("play(120)", h.out.calls.last())
        h.vm.onIntent(EditorIntent.TogglePlay)
    }

    @Test
    fun `after a swipe stopped playback the next swipe is ignored and Play can be pressed again`() = runTest(dispatcher) {
        val h = harness()
        h.vm.onIntent(EditorIntent.TogglePlay)
        advanceTimeBy(500)
        h.down()
        h.scroll(40f)
        assertFalse(h.state.isPlaying)

        h.vm.onIntent(EditorIntent.TogglePlay)
        assertTrue(h.state.isPlaying)
        h.down()
        h.scroll(40f)
        assertFalse(h.state.isPlaying)
    }

    @Test
    fun `dragging the playhead handle or the ruler while playing stops playback and follows the finger`() = runTest(dispatcher) {
        val h = harness()
        h.vm.onIntent(EditorIntent.TogglePlay)
        advanceTimeBy(500)
        assertTrue(h.vm.canDrag(TimelineHit(HitKind.PLAYHEAD, -1, -1, 15)))

        h.vm.onIntent(EditorIntent.DragStart(TimelineHit(HitKind.PLAYHEAD, -1, -1, 15)))
        assertFalse(h.state.isPlaying)
        h.vm.onIntent(EditorIntent.DragMove(frame = 80, trackIndex = -1))
        assertEquals(FrameIndex(80), h.state.playhead)
        assertEquals("seek(80)", h.out.calls.last())
        advanceTimeBy(2_000)
        assertEquals(FrameIndex(80), h.state.playhead)

        h.vm.onIntent(EditorIntent.DragEnd(commit = true))
        h.vm.onIntent(EditorIntent.TogglePlay)
        h.vm.onIntent(EditorIntent.DragStart(TimelineHit(HitKind.RULER, -1, -1, 150)))
        assertFalse(h.state.isPlaying)
        assertEquals(FrameIndex(150), h.state.playhead)
    }
}
