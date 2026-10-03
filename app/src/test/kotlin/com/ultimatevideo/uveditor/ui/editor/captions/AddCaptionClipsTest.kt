package com.ultimatevideo.uveditor.ui.editor.captions

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
import com.ultimatevideo.uveditor.domain.captions.CaptionCue
import com.ultimatevideo.uveditor.domain.captions.CaptionStyle
import com.ultimatevideo.uveditor.domain.captions.clipsFor
import com.ultimatevideo.uveditor.domain.Clip
import com.ultimatevideo.uveditor.domain.FrameIndex
import com.ultimatevideo.uveditor.domain.TrackType
import com.ultimatevideo.uveditor.ui.editor.EditorEffect
import com.ultimatevideo.uveditor.ui.editor.EditorIntent
import com.ultimatevideo.uveditor.ui.editor.EditorViewModel
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
class AddCaptionClipsTest {

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

    private val project = ProjectDto(
        id = "p1",
        name = "Test",
        settings = ProjectSettingsDto(1080, 1920, 30, 1, "Rec709-SDR"),
        mediaLibrary = listOf(MediaAssetDto("a1", "content://m/a1", 300, 30, 1, "Rec709-SDR")),
        tracks = listOf(
            TrackDto("v1", "video", 0, listOf(ClipDto("c1", "a1", 0, 0, 300))),
            TrackDto("a1", "audio", 1),
        ),
    )

    private val cues = listOf(
        CaptionCue(FrameIndex(10), FrameIndex(50), "Hello there."),
        CaptionCue(FrameIndex(60), FrameIndex(100), "General Kenobi."),
    )

    private fun TestScope.viewModel(): EditorViewModel {
        var n = 0
        val vm = EditorViewModel("p1", FakeStore(project), NoProbe(), idGenerator = { "n${n++}" })
        advanceUntilIdle()
        return vm
    }

    private fun captionClips(): List<Clip> {
        var n = 0
        return CaptionStyle.BOLD.clipsFor(cues, 1920) { "k${n++}" }
    }

    @Test
    fun `captions land on a new title track on top, selected, and one undo removes them`() = runTest(dispatcher) {
        val vm = viewModel()
        val before = vm.state.value.timeline

        vm.onIntent(EditorIntent.AddCaptionClips(captionClips()))

        val state = vm.state.value
        val top = state.timeline.tracks.first()
        assertEquals(TrackType.TITLE, top.type)
        assertEquals(listOf("Hello there.", "General Kenobi."), top.clips.map { it.title?.text })
        assertEquals(top.id, state.selectedTrackId)
        assertEquals(top.clips.first().id, state.selectedClipId)
        assertTrue(state.timeline.invariantViolations().isEmpty())
        assertTrue(state.canUndo)

        vm.onIntent(EditorIntent.Undo)
        assertEquals(before, vm.state.value.timeline)
    }

    @Test
    fun `existing titles are left alone because captions get their own track`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onIntent(EditorIntent.AddTitle)
        val titleTrackBefore = vm.state.value.timeline.tracks.first { it.type == TrackType.TITLE }

        vm.onIntent(EditorIntent.AddCaptionClips(captionClips()))

        val titleTracks = vm.state.value.timeline.tracks.filter { it.type == TrackType.TITLE }
        assertEquals(2, titleTracks.size)
        assertTrue(titleTracks.any { it == titleTrackBefore })
        assertTrue(vm.state.value.timeline.invariantViolations().isEmpty())
    }

    @Test
    fun `no captions change nothing`() = runTest(dispatcher) {
        val vm = viewModel()
        val before = vm.state.value

        vm.onIntent(EditorIntent.AddCaptionClips(emptyList()))

        assertEquals(before.timeline, vm.state.value.timeline)
        assertFalse(vm.state.value.canUndo)
    }

    @Test
    fun `a clash is reported and nothing is added`() = runTest(dispatcher) {
        val vm = viewModel()
        val effects = mutableListOf<EditorEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { effects += it } }
        val before = vm.state.value.timeline
        val clips = captionClips()

        vm.onIntent(EditorIntent.AddCaptionClips(clips + clips.first())) // duplicate clip id

        assertEquals(before, vm.state.value.timeline)
        assertFalse(vm.state.value.canUndo)
        assertTrue(effects.any { it is EditorEffect.ShowMessage })
    }
}
