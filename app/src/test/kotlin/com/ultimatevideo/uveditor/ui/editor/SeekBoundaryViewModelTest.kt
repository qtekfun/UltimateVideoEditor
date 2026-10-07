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
import com.ultimatevideo.uveditor.domain.FrameIndex
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

/** The previous / next clip boundary buttons while the project plays (DECISIONS "Transport buttons stop playback"). */
@OptIn(ExperimentalCoroutinesApi::class)
class SeekBoundaryViewModelTest {
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

    /** Three 100 frame clips on the base: boundaries at 0, 100, 200 and 300. */
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
    }

    private fun TestScope.harness(): Harness {
        val vm = EditorViewModel("p1", FakeStore(project()), NoImporter, nanoClock = { testScheduler.currentTime * 1_000_000 })
        val out = FakeOutput().also { vm.playbackOutput = it }
        advanceUntilIdle()
        return Harness(vm, out)
    }

    @Test
    fun `next boundary while playing stops playback and lands exactly on the boundary`() = runTest(dispatcher) {
        val h = harness()
        h.vm.onIntent(EditorIntent.TogglePlay)
        advanceTimeBy(1_500) // about frame 45
        assertTrue(h.state.isPlaying)
        h.out.calls.clear()

        h.vm.onIntent(EditorIntent.SeekNext)

        assertFalse(h.state.isPlaying)
        assertEquals(FrameIndex(100), h.state.playhead)
        assertEquals(listOf("pause", "seek(100)"), h.out.calls)
        advanceTimeBy(5_000)
        assertEquals(FrameIndex(100), h.state.playhead)
        assertFalse(h.state.isPlaying)
    }

    @Test
    fun `previous boundary while playing stops playback and lands exactly on the boundary`() = runTest(dispatcher) {
        val h = harness()
        h.vm.onIntent(EditorIntent.SetPlayhead(150))
        h.vm.onIntent(EditorIntent.TogglePlay)
        advanceTimeBy(500)
        assertTrue(h.state.isPlaying)
        h.out.calls.clear()

        h.vm.onIntent(EditorIntent.SeekPrevious)

        assertFalse(h.state.isPlaying)
        assertEquals(FrameIndex(100), h.state.playhead)
        assertEquals(listOf("pause", "seek(100)"), h.out.calls)
        advanceTimeBy(5_000)
        assertEquals(FrameIndex(100), h.state.playhead)
    }

    @Test
    fun `the boundary is chosen from where the playhead is, not from the audio clock`() = runTest(dispatcher) {
        val h = harness()
        h.vm.onIntent(EditorIntent.TogglePlay)
        h.out.heard = 120
        advanceTimeBy(20)
        assertEquals(FrameIndex(120), h.state.playhead)

        h.vm.onIntent(EditorIntent.SeekNext)

        assertEquals(FrameIndex(200), h.state.playhead)
        h.out.heard = 260 // the stopped output must not drag the playhead along any more
        advanceTimeBy(1_000)
        assertEquals(FrameIndex(200), h.state.playhead)
    }

    @Test
    fun `with no boundary left in that direction playback still stops and the playhead stays`() = runTest(dispatcher) {
        val h = harness()
        h.vm.onIntent(EditorIntent.TogglePlay)
        h.out.heard = 299
        advanceTimeBy(20)
        h.vm.onIntent(EditorIntent.SeekNext) // 300 is the last boundary
        assertEquals(FrameIndex(300), h.state.playhead)

        h.vm.onIntent(EditorIntent.SetPlayhead(10))
        h.vm.onIntent(EditorIntent.TogglePlay)
        h.vm.onIntent(EditorIntent.SeekPrevious) // the start of the timeline is a boundary too
        assertFalse(h.state.isPlaying)
        assertEquals(FrameIndex(0), h.state.playhead)

        h.vm.onIntent(EditorIntent.TogglePlay)
        h.vm.onIntent(EditorIntent.SeekPrevious) // already at the start: nothing before it
        assertFalse(h.state.isPlaying)
        assertEquals(FrameIndex(0), h.state.playhead)
    }

    @Test
    fun `the buttons keep working when paused`() = runTest(dispatcher) {
        val h = harness()

        h.vm.onIntent(EditorIntent.SeekNext)
        assertEquals(FrameIndex(100), h.state.playhead)
        h.vm.onIntent(EditorIntent.SeekNext)
        assertEquals(FrameIndex(200), h.state.playhead)
        h.vm.onIntent(EditorIntent.SeekPrevious)
        assertEquals(FrameIndex(100), h.state.playhead)
        assertFalse(h.state.isPlaying)
        assertEquals(listOf("seek(100)", "seek(200)", "seek(100)"), h.out.calls)
    }
}
