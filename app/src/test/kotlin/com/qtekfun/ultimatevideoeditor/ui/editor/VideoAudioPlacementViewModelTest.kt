package com.qtekfun.ultimatevideoeditor.ui.editor

import com.qtekfun.ultimatevideoeditor.data.MediaImportException
import com.qtekfun.ultimatevideoeditor.data.MediaImporter
import com.qtekfun.ultimatevideoeditor.data.ProbedMedia
import com.qtekfun.ultimatevideoeditor.data.ProjectError
import com.qtekfun.ultimatevideoeditor.data.ProjectStore
import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto
import com.qtekfun.ultimatevideoeditor.data.model.ProjectDto
import com.qtekfun.ultimatevideoeditor.data.model.ProjectSettingsDto
import com.qtekfun.ultimatevideoeditor.data.model.TrackDto
import com.qtekfun.ultimatevideoeditor.domain.Clip
import com.qtekfun.ultimatevideoeditor.ui.editor.layout.VideoAudioPlacementStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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

/** The "Put video audio on an audio track" setting (SPECS 5.38) on the ways a clip gets onto the timeline. */
@OptIn(ExperimentalCoroutinesApi::class)
class VideoAudioPlacementViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeStore(var project: ProjectDto) : ProjectStore {
        override suspend fun load(id: String): ProjectDto = project

        override suspend fun save(project: ProjectDto) {
            this.project = project
        }
    }

    private class FakeImporter(val media: Map<String, ProbedMedia>) : MediaImporter {
        override suspend fun import(uri: String): ProbedMedia = media[uri] ?: throw MediaImportException("Unsupported file")
    }

    private class Switch(var enabled: Boolean) : VideoAudioPlacementStore {
        override fun isOn() = enabled

        override fun setOn(on: Boolean) {
            this.enabled = on
        }
    }

    private val settings = ProjectSettingsDto(1920, 1080, 30, 1, "Rec709-SDR")
    private fun asset(id: String, video: Boolean, audio: Boolean) =
        MediaAssetDto(id, "content://m/$id", 300, 30, 1, "Rec709-SDR", hasVideo = video, hasAudio = audio)

    private val project = ProjectDto(
        id = "p1",
        name = "Test",
        settings = settings,
        mediaLibrary = listOf(asset("vid", video = true, audio = true), asset("mute", video = true, audio = false), asset("snd", video = false, audio = true)),
        tracks = listOf(TrackDto("v1", "video", 0), TrackDto("a1", "audio", 1)),
    )

    private val video = ProbedMedia(5_000_000, 30, 1, "Rec709-SDR", hasVideo = true, hasAudio = true)

    // The library's files must be readable, or placing them is refused as missing media.
    private val library = mapOf(
        "content://m/vid" to video,
        "content://m/mute" to video.copy(hasAudio = false),
        "content://m/snd" to ProbedMedia(5_000_000, 30, 1, "Rec709-SDR", hasVideo = false, hasAudio = true),
    )

    private fun TestScope.harness(on: Boolean, importer: MediaImporter = FakeImporter(library)): Pair<EditorViewModel, Switch> {
        var counter = 0
        val switch = Switch(on)
        val vm = EditorViewModel("p1", FakeStore(project), importer, idGenerator = { "n${counter++}" }, videoAudioPlacement = switch)
        advanceUntilIdle()
        return vm to switch
    }

    private fun EditorViewModel.clips(trackId: String): List<Clip> = state.value.timeline.track(trackId)?.clips.orEmpty()

    @Test
    fun `off by default, a tapped video clip keeps its own sound`() = runTest(dispatcher) {
        val (vm, _) = harness(on = false)
        vm.onIntent(EditorIntent.AddAsset("vid"))
        val clip = vm.clips("v1").single()
        assertFalse(clip.audioDetached)
        assertTrue(vm.clips("a1").isEmpty())
    }

    @Test
    fun `on, a tapped video clip comes in detached and linked on the audio lane, in one undo step`() = runTest(dispatcher) {
        val (vm, _) = harness(on = true)
        vm.onIntent(EditorIntent.AddAsset("vid"))
        val clip = vm.clips("v1").single()
        val audio = vm.clips("a1").single()
        assertTrue(clip.audioDetached)
        assertNotNull(clip.linkId)
        assertEquals(clip.linkId, audio.linkId)
        assertEquals(clip.timelineStart, audio.timelineStart)
        assertEquals(clip.durationFrames, audio.durationFrames)

        vm.onIntent(EditorIntent.Undo)
        assertTrue(vm.clips("v1").isEmpty())
        assertTrue(vm.clips("a1").isEmpty())
    }

    @Test
    fun `the switch is read at each placement`() = runTest(dispatcher) {
        val (vm, switch) = harness(on = false)
        vm.onIntent(EditorIntent.AddAsset("vid"))
        switch.enabled = true
        vm.onIntent(EditorIntent.SetPlayhead(300))
        vm.onIntent(EditorIntent.AddAsset("vid"))
        val clips = vm.clips("v1")
        assertEquals(listOf(false, true), clips.map { it.audioDetached })
        assertEquals(1, vm.clips("a1").size)
    }

    @Test
    fun `silent video and audio-only media are placed as they are`() = runTest(dispatcher) {
        val (vm, _) = harness(on = true)
        vm.onIntent(EditorIntent.AddAsset("mute"))
        vm.onIntent(EditorIntent.AddAsset("snd"))
        assertFalse(vm.clips("v1").single().audioDetached)
        assertNull(vm.clips("v1").single().linkId)
        assertEquals(1, vm.clips("a1").size)
        assertNull(vm.clips("a1").single().linkId)
    }

    @Test
    fun `imported files are placed back to back, each with its sound on the audio lane`() = runTest(dispatcher) {
        val (vm, _) = harness(on = true, importer = FakeImporter(library + mapOf("content://one" to video, "content://two" to video)))
        vm.onIntent(EditorIntent.ImportMedia(listOf("content://one", "content://two")))
        advanceUntilIdle()
        assertEquals(listOf(0L, 150L), vm.clips("v1").map { it.timelineStart.value })
        assertEquals(listOf(0L, 150L), vm.clips("a1").map { it.timelineStart.value })
        assertTrue(vm.clips("v1").all { it.audioDetached })
        assertEquals(0, vm.state.value.timeline.invariantViolations().size)
    }

    @Test
    fun `a clip dragged from the tray is detached too`() = runTest(dispatcher) {
        val (vm, _) = harness(on = true)
        vm.onIntent(EditorIntent.TrayDragStart("vid"))
        vm.onIntent(EditorIntent.TrayDragMove(0, 0))
        vm.onIntent(EditorIntent.TrayDragEnd(commit = true))
        assertTrue(vm.clips("v1").single().audioDetached)
        assertEquals(1, vm.clips("a1").size)
    }
}
