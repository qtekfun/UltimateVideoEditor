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

/** The preview holds audio focus exactly while it plays (DECISIONS "Audio focus and screen on while playing"). */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackAudioFocusViewModelTest {
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

    private class FakeFocus : PlaybackAudioFocus {
        val calls = mutableListOf<String>()
        var lost: (() -> Unit)? = null

        override fun request(onLost: () -> Unit) {
            calls += "request"
            lost = onLost
        }

        override fun abandon() {
            calls += "abandon"
        }
    }

    private val settings = ProjectSettingsDto(1920, 1080, 30, 1, "Rec709-SDR")

    private fun project() = ProjectDto(
        id = "p1",
        name = "T",
        settings = settings,
        mediaLibrary = listOf(MediaAssetDto("a1", "content://m/a1", durationFrames = 300, nativeFpsNum = 30, nativeFpsDen = 1, colorSpace = "Rec709-SDR")),
        tracks = listOf(TrackDto("v1", "video", 0, listOf(ClipDto("c1", "a1", 0, 0, 300)))),
    )

    private fun TestScope.harness(focus: FakeFocus): EditorViewModel {
        val vm = EditorViewModel("p1", FakeStore(project()), NoImporter, audioFocus = focus, nanoClock = { testScheduler.currentTime * 1_000_000L })
        advanceUntilIdle()
        return vm
    }

    @Test
    fun `focus is requested when playback starts and given up when it is paused`() = runTest(dispatcher) {
        val focus = FakeFocus()
        val vm = harness(focus)
        vm.onIntent(EditorIntent.TogglePlay)
        assertTrue(vm.state.value.isPlaying)
        assertEquals(listOf("request"), focus.calls)

        vm.onIntent(EditorIntent.TogglePlay)
        assertFalse(vm.state.value.isPlaying)
        assertEquals("abandon", focus.calls.last())
    }

    @Test
    fun `focus is given up when playback reaches the end`() = runTest(dispatcher) {
        val focus = FakeFocus()
        val vm = harness(focus)
        vm.onIntent(EditorIntent.SetPlayhead(290))
        vm.onIntent(EditorIntent.TogglePlay)
        advanceTimeBy(2_000)
        assertFalse(vm.state.value.isPlaying)
        assertEquals("abandon", focus.calls.last())
    }

    @Test
    fun `losing focus to another app pauses playback`() = runTest(dispatcher) {
        val focus = FakeFocus()
        val vm = harness(focus)
        vm.onIntent(EditorIntent.TogglePlay)
        focus.lost?.invoke()
        assertFalse(vm.state.value.isPlaying)
        assertEquals("abandon", focus.calls.last())
    }

    @Test
    fun `a scrub that restarts playback asks for focus again`() = runTest(dispatcher) {
        val focus = FakeFocus()
        val vm = harness(focus)
        vm.onIntent(EditorIntent.TogglePlay)
        vm.onIntent(EditorIntent.SetPlayhead(30))
        assertEquals(2, focus.calls.count { it == "request" })
        assertTrue(focus.calls.indexOf("abandon") in 1 until focus.calls.lastIndexOf("request"))
    }

    @Test
    fun `an empty timeline asks for no focus`() = runTest(dispatcher) {
        val focus = FakeFocus()
        val vm = EditorViewModel("p1", FakeStore(ProjectDto(id = "p1", name = "T", settings = settings)), NoImporter, audioFocus = focus)
        advanceUntilIdle()
        vm.onIntent(EditorIntent.TogglePlay)
        assertFalse(vm.state.value.isPlaying)
        assertEquals(emptyList<String>(), focus.calls)
    }
}
