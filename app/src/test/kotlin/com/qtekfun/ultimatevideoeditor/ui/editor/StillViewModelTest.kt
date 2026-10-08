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
import com.qtekfun.ultimatevideoeditor.domain.StillKind
import com.qtekfun.ultimatevideoeditor.domain.TrackType
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

@OptIn(ExperimentalCoroutinesApi::class)
class StillViewModelTest {

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

    private class FakeImporter(val media: Map<String, ProbedMedia>) : MediaImporter {
        override suspend fun import(uri: String): ProbedMedia = media[uri] ?: throw MediaImportException("Unsupported file")
    }

    private val settings = ProjectSettingsDto(1920, 1080, 30, 1, "Rec709-SDR")
    private val video = MediaAssetDto("a1", "content://m/a1", 300, 30, 1, "Rec709-SDR", hasVideo = true, hasAudio = false)
    private val photo = ProbedMedia(0, 30, 1, "Rec709-SDR", hasVideo = false, hasAudio = false, isImage = true)

    private fun baseOnly() = ProjectDto(
        id = "p1", name = "T", settings = settings, mediaLibrary = listOf(video),
        tracks = listOf(
            TrackDto("v1", "video", 0, listOf(ClipDto("c1", "a1", 0, 0, 100), ClipDto("c2", "a1", 100, 0, 100))),
            TrackDto("a1", "audio", 1),
        ),
    )

    private fun withOverlay() = baseOnly().copy(
        tracks = listOf(TrackDto("v2", "video", 0), TrackDto("v1", "video", 1, baseOnly().tracks[0].clips), TrackDto("a1", "audio", 2)),
    )

    private class Harness(val vm: EditorViewModel, val effects: MutableList<EditorEffect>) {
        val state get() = vm.state.value
        fun clips(trackId: String): List<Clip> = state.timeline.track(trackId)?.clips.orEmpty()
    }

    private fun TestScope.harness(project: ProjectDto, importer: MediaImporter = FakeImporter(emptyMap())): Harness {
        var counter = 0
        val vm = EditorViewModel("p1", FakeStore(project), importer, idGenerator = { "n${counter++}" })
        val effects = mutableListOf<EditorEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { effects += it } }
        advanceUntilIdle()
        return Harness(vm, effects)
    }

    @Test
    fun `importing a picture puts a five second photo on the base at the playhead`() = runTest(dispatcher) {
        val h = harness(baseOnly(), FakeImporter(mapOf("content://pic" to photo)))

        h.vm.onIntent(EditorIntent.ImportMedia(listOf("content://pic")))
        advanceUntilIdle()

        val asset = h.state.assets.single { it.isImage }
        assertEquals("content://pic", asset.uri)
        assertFalse(asset.hasVideo || asset.hasAudio)
        assertEquals(150L, asset.durationFrames)
        val clip = h.clips("v1").first { it.still != null }
        assertEquals(StillKind.PHOTO, clip.still)
        assertEquals(asset.id, clip.assetId)
        assertEquals(150L, clip.durationFrames)
        assertEquals(0L, clip.sourceIn.value)
        assertEquals(h.state.timeline.invariantViolations(), emptyList<String>())
        // The base is gap free: the photo went in at a cut and the rest rippled.
        assertEquals(h.clips("v1").first().timelineEnd, h.clips("v1")[1].timelineStart)

        h.vm.onIntent(EditorIntent.Undo)
        assertTrue(h.clips("v1").none { it.still != null })
    }

    @Test
    fun `importing a picture twice reuses its library entry`() = runTest(dispatcher) {
        val h = harness(baseOnly(), FakeImporter(mapOf("content://pic" to photo)))

        h.vm.onIntent(EditorIntent.ImportMedia(listOf("content://pic")))
        advanceUntilIdle()
        h.vm.onIntent(EditorIntent.ImportMedia(listOf("content://pic")))
        advanceUntilIdle()

        assertEquals(1, h.state.assets.count { it.isImage })
        assertEquals(2, h.clips("v1").count { it.still == StillKind.PHOTO })
    }

    @Test
    fun `a photo's canvas block carries its asset key for the thumbnail, a sticker has none`() = runTest(dispatcher) {
        val h = harness(baseOnly(), FakeImporter(mapOf("content://pic" to photo)))
        h.vm.onIntent(EditorIntent.ImportMedia(listOf("content://pic")))
        advanceUntilIdle()

        val snapshot = h.vm.snapshotOf(h.state)
        val ordered = h.state.visibleTimeline.tracks.flatMap { it.clips }
        val index = ordered.indexOfFirst { it.still != null }
        assertEquals(StillKind.PHOTO, ordered[index].still)
        assertTrue(snapshot.clips[index].assetKey != -1L)
        assertTrue(snapshot.clips.filterIndexed { i, _ -> i != index && ordered[i].hasMedia }.all { it.assetKey != -1L })

        h.vm.onIntent(EditorIntent.AddSticker("shape:heart"))
        advanceUntilIdle()
        val withSticker = h.vm.snapshotOf(h.state)
        val all = h.state.visibleTimeline.tracks.flatMap { it.clips }
        val stickerIndex = all.indexOfFirst { it.still == StillKind.STICKER }
        assertEquals(-1L, withSticker.clips[stickerIndex].assetKey)
    }

    @Test
    fun `a sticker with only a base gets a new lane above it`() = runTest(dispatcher) {
        val h = harness(baseOnly())

        h.vm.onIntent(EditorIntent.AddSticker("shape:heart"))
        advanceUntilIdle()

        assertEquals(listOf(TrackType.VIDEO, TrackType.VIDEO, TrackType.AUDIO), h.state.timeline.tracks.map { it.type })
        val lane = h.state.timeline.tracks[0]
        val sticker = lane.clips.single()
        assertEquals(StillKind.STICKER, sticker.still)
        assertEquals("shape:heart", sticker.assetId)
        assertEquals(90L, sticker.durationFrames)
        assertEquals(sticker.id, h.state.selectedClipId)
        assertEquals(lane.id, h.state.selectedTrackId)
        assertEquals(h.state.timeline.invariantViolations(), emptyList<String>())
        // The base is untouched.
        assertEquals(listOf("c1", "c2"), h.clips("v1").map { it.id })
    }

    @Test
    fun `a sticker goes on the existing overlay lane at the playhead and undoes in one step`() = runTest(dispatcher) {
        val h = harness(withOverlay())
        h.vm.onIntent(EditorIntent.SetPlayhead(40))

        h.vm.onIntent(EditorIntent.AddSticker("emoji:🔥"))
        advanceUntilIdle()

        val sticker = h.clips("v2").single()
        assertEquals(40L, sticker.timelineStart.value)
        assertEquals(StillKind.STICKER, sticker.still)
        assertEquals(3, h.state.timeline.tracks.size)

        h.vm.onIntent(EditorIntent.Undo)
        assertTrue(h.clips("v2").isEmpty())
    }

    @Test
    fun `an unknown sticker is refused with a message`() = runTest(dispatcher) {
        val h = harness(baseOnly())

        h.vm.onIntent(EditorIntent.AddSticker("shape:does-not-exist"))
        advanceUntilIdle()

        assertTrue(h.effects.any { it is EditorEffect.ShowMessage })
        assertEquals(2, h.state.timeline.tracks.size)
        assertFalse(h.state.canUndo)
    }

    @Test
    fun `a still on the base can be stretched freely and keeps no source limit`() = runTest(dispatcher) {
        val h = harness(baseOnly(), FakeImporter(mapOf("content://pic" to photo)))
        h.vm.onIntent(EditorIntent.ImportMedia(listOf("content://pic")))
        advanceUntilIdle()
        val photoClip = h.clips("v1").first { it.still != null }

        val result = com.qtekfun.ultimatevideoeditor.domain.EditCommand.TrimClip(
            photoClip.id, com.qtekfun.ultimatevideoeditor.domain.TrimEdge.END,
            photoClip.timelineEnd + 900, sourceLength = null,
        ).apply(h.state.timeline)

        val ok = result as com.qtekfun.ultimatevideoeditor.domain.EditResult.Success
        assertEquals(1050L, ok.value.track("v1")!!.clip(photoClip.id)!!.durationFrames)
        assertNull(ok.value.track("v1")!!.clip(photoClip.id)!!.retimedFrames)
    }
}
