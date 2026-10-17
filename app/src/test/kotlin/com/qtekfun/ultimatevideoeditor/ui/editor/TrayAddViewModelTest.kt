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

/** Adding media from the tray: the playhead continues after what was added, and several assets go in, in order, as one step. */
@OptIn(ExperimentalCoroutinesApi::class)
class TrayAddViewModelTest {

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

    private fun asset(id: String, frames: Long) = MediaAssetDto(id, "content://m/$id", frames, 30, 1, "Rec709-SDR", hasVideo = true, hasAudio = false)

    private val project = ProjectDto(
        id = "p1",
        name = "Test",
        settings = ProjectSettingsDto(1920, 1080, 30, 1, "Rec709-SDR"),
        mediaLibrary = listOf(asset("one", 100), asset("two", 60), asset("three", 40)),
        tracks = listOf(TrackDto("v1", "video", 0)),
    )

    private val clipMedia = ProbedMedia(5_000_000, 30, 1, "Rec709-SDR", hasVideo = true, hasAudio = false)
    private val library = listOf("one", "two", "three").associate { "content://m/$it" to clipMedia }

    private fun TestScope.harness(): EditorViewModel {
        var counter = 0
        val vm = EditorViewModel("p1", FakeStore(project), FakeImporter(library), idGenerator = { "n${counter++}" }, videoAudioPlacement = Switch(false))
        advanceUntilIdle()
        return vm
    }

    private fun EditorViewModel.clips(): List<Clip> = state.value.timeline.track("v1")?.clips.orEmpty()

    private fun EditorViewModel.assertBackToBack() {
        val clips = clips()
        assertEquals(0L, clips.first().timelineStart.value)
        for ((left, right) in clips.zipWithNext()) assertEquals(left.timelineEnd, right.timelineStart)
    }

    @Test
    fun `a tapped asset leaves the playhead where the clip ends, so the next one follows`() = runTest(dispatcher) {
        val vm = harness()
        vm.onIntent(EditorIntent.AddAsset("one"))
        assertEquals(vm.clips().last().timelineEnd, vm.state.value.playhead)
        vm.onIntent(EditorIntent.AddAsset("two"))
        assertEquals(listOf("one", "two"), vm.clips().map { it.assetId })
        vm.assertBackToBack()
        assertEquals(vm.clips().last().timelineEnd, vm.state.value.playhead)
    }

    @Test
    fun `several assets are added in the given order, back to back, in one undo step`() = runTest(dispatcher) {
        val vm = harness()
        vm.onIntent(EditorIntent.AddAssets(listOf("three", "one", "two")))
        assertEquals(listOf("three", "one", "two"), vm.clips().map { it.assetId })
        vm.assertBackToBack()
        assertEquals(vm.clips().last().timelineEnd, vm.state.value.playhead)

        vm.onIntent(EditorIntent.Undo)
        assertTrue(vm.clips().isEmpty())
    }

    @Test
    fun `several assets go in at the playhead and push the later clips right`() = runTest(dispatcher) {
        val vm = harness()
        vm.onIntent(EditorIntent.AddAsset("one"))
        vm.onIntent(EditorIntent.AddAsset("one"))
        val cut = vm.clips().first().timelineEnd
        vm.onIntent(EditorIntent.SetPlayhead(cut.value))
        vm.onIntent(EditorIntent.AddAssets(listOf("two", "three")))
        assertEquals(listOf("one", "two", "three", "one"), vm.clips().map { it.assetId })
        vm.assertBackToBack()
        assertEquals(vm.clips()[2].timelineEnd, vm.state.value.playhead)
    }

    @Test
    fun `an unknown asset in the list is skipped and the rest still go in`() = runTest(dispatcher) {
        val vm = harness()
        vm.onIntent(EditorIntent.AddAssets(listOf("nope", "two")))
        assertEquals(listOf("two"), vm.clips().map { it.assetId })
    }
}
