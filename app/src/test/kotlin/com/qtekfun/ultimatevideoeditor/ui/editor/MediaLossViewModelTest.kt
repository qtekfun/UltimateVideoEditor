package com.qtekfun.ultimatevideoeditor.ui.editor

import com.qtekfun.ultimatevideoeditor.data.MediaCaches
import com.qtekfun.ultimatevideoeditor.data.MediaImportException
import com.qtekfun.ultimatevideoeditor.data.MediaImporter
import com.qtekfun.ultimatevideoeditor.data.MediaProblem
import com.qtekfun.ultimatevideoeditor.data.ProbedMedia
import com.qtekfun.ultimatevideoeditor.data.ProjectStore
import com.qtekfun.ultimatevideoeditor.data.model.ClipDto
import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto
import com.qtekfun.ultimatevideoeditor.data.model.ProjectDto
import com.qtekfun.ultimatevideoeditor.data.model.ProjectSettingsDto
import com.qtekfun.ultimatevideoeditor.data.model.TrackDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import com.qtekfun.ultimatevideoeditor.ui.text.english

/**
 * A drive pulled while the project is open (SPECS 5.40): a decoder or the mixer fails mid-way, the editor finds out whether the file
 * is really gone, marks it missing at once with one message, and "Check again" brings it back without reopening the project.
 * The importer is a fake whose files can disappear and reappear, like a USB volume.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MediaLossViewModelTest {

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
        val saved = mutableListOf<ProjectDto>()
        val statuses = mutableListOf<Int>()

        override suspend fun load(id: String): ProjectDto = project

        override suspend fun save(project: ProjectDto) {
            saved += project
            this.project = project
        }

        override suspend fun saveMediaStatus(id: String, missingMedia: Int) {
            statuses += missingMedia
        }
    }

    /** The files that are plugged in; anything else fails like a vanished volume. */
    private class Drive(val present: MutableSet<String>) : MediaImporter {
        var verifies = 0

        override suspend fun import(uri: String): ProbedMedia {
            verifies++
            if (uri !in present) throw MediaImportException("cannot open $uri", problem = MediaProblem.UNREADABLE)
            return ProbedMedia(30_000_000, 30, 1, "Rec709-SDR", hasVideo = true, hasAudio = true, displayName = null)
        }
    }

    private class RecordingCaches : MediaCaches {
        val invalidated = mutableListOf<String>()

        override fun invalidate(assetId: String) {
            invalidated += assetId
        }
    }

    private fun asset(id: String) =
        MediaAssetDto(id, "content://usb/$id.mp4", durationFrames = 300, nativeFpsNum = 30, nativeFpsDen = 1, colorSpace = "Rec709-SDR", displayName = "$id.mp4")

    private val project = ProjectDto(
        id = "p1",
        name = "On the drive",
        settings = ProjectSettingsDto(1920, 1080, 30, 1, "Rec709-SDR"),
        mediaLibrary = listOf(asset("a1"), asset("a2")),
        tracks = listOf(
            TrackDto("v1", "video", 0, listOf(ClipDto("c1", "a1", 0, 0, 100), ClipDto("c2", "a2", 100, 0, 100))),
            TrackDto("a1", "audio", 1),
        ),
    )

    private class Harness(
        val vm: EditorViewModel,
        val store: FakeStore,
        val drive: Drive,
        val caches: RecordingCaches,
        val effects: MutableList<EditorEffect>,
    ) {
        val state get() = vm.state.value
        val messages get() = effects.filterIsInstance<EditorEffect.ShowMessage>().map { it.text.english() }
        val unavailable get() = effects.filterIsInstance<EditorEffect.AssetUnavailable>().map { it.assetKey }
    }

    private fun TestScope.harness(present: Set<String> = setOf("content://usb/a1.mp4", "content://usb/a2.mp4")): Harness {
        val store = FakeStore(project)
        val drive = Drive(present.toMutableSet())
        val caches = RecordingCaches()
        val vm = EditorViewModel("p1", store, drive, idGenerator = { "n1" }, mediaCaches = caches)
        val effects = mutableListOf<EditorEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { effects += it } }
        advanceUntilIdle()
        return Harness(vm, store, drive, caches, effects)
    }

    private fun Harness.pull(vararg ids: String) = ids.forEach { drive.present -= "content://usb/$it.mp4" }

    private fun Harness.plugIn(vararg ids: String) = ids.forEach { drive.present += "content://usb/$it.mp4" }

    @Test
    fun `a decode failure on a file that went away marks it missing at once with one message`() = runTest(dispatcher) {
        val h = harness()
        assertTrue(h.state.missingMedia.isEmpty())
        h.pull("a1")

        h.vm.onIntent(EditorIntent.MediaFailureReported(h.vm.assetKey("a1"), "Preview error: seek failed"))
        advanceUntilIdle()

        assertEquals(mapOf("a1" to MediaProblem.UNREADABLE), h.state.missingMedia)
        assertEquals(listOf("a2"), h.state.playableAssets.map { it.id })
        assertEquals(listOf(h.vm.assetKey("a1")), h.state.unreadableAssets.map { h.vm.assetKey(it.id) })
        assertEquals(
            listOf("Media for a1.mp4 is no longer available: reconnect the drive and use Relink or reopen the project"),
            h.messages,
        )
        assertEquals(listOf(h.vm.assetKey("a1")), h.unavailable)
    }

    @Test
    fun `a burst of failures for one loss gives one message and one check`() = runTest(dispatcher) {
        val h = harness()
        h.pull("a1")
        val before = h.drive.verifies

        repeat(20) { h.vm.onIntent(EditorIntent.MediaFailureReported(h.vm.assetKey("a1"), "Preview error: decode failed")) }
        advanceUntilIdle()
        // And more, after the file is already known to be missing.
        repeat(5) { h.vm.onIntent(EditorIntent.MediaFailureReported(h.vm.assetKey("a1"), "Preview error: decode failed")) }
        advanceUntilIdle()

        assertEquals(1, h.messages.size)
        assertTrue(h.messages.single().startsWith("Media for a1.mp4"))
        assertEquals(1, h.drive.verifies - before)
        assertEquals(1, h.unavailable.size)
    }

    @Test
    fun `the second layer of the same file is recognised by its lane offset`() = runTest(dispatcher) {
        val h = harness()
        h.pull("a1")

        h.vm.onIntent(EditorIntent.MediaFailureReported(h.vm.assetKey("a1") + 1L * LANE_STRIDE, "Preview error: decode failed"))
        advanceUntilIdle()

        assertTrue("a1" in h.state.missingMedia)
    }

    @Test
    fun `a failure of a file that is still readable is an ordinary error`() = runTest(dispatcher) {
        val h = harness()

        h.vm.onIntent(EditorIntent.MediaFailureReported(h.vm.assetKey("a1"), "Preview error: corrupt frame"))
        advanceUntilIdle()

        assertTrue(h.state.missingMedia.isEmpty())
        assertEquals(listOf("Preview error: corrupt frame"), h.messages)
        assertTrue(h.unavailable.isEmpty())
    }

    @Test
    fun `a failure that names no known file is shown as it is and marks nothing`() = runTest(dispatcher) {
        val h = harness()

        h.vm.onIntent(EditorIntent.MediaFailureReported(-1, "Preview error: GL"))
        h.vm.onIntent(EditorIntent.MediaFailureReported(999, "Preview error: unknown"))
        advanceUntilIdle()

        assertTrue(h.state.missingMedia.isEmpty())
        assertEquals(listOf("Preview error: GL", "Preview error: unknown"), h.messages)
    }

    @Test
    fun `playback stops cleanly when the drive goes away`() = runTest(dispatcher) {
        val h = harness()
        h.vm.onIntent(EditorIntent.TogglePlay)
        runCurrent()
        assertTrue(h.state.isPlaying)
        h.pull("a1", "a2")

        h.vm.onIntent(EditorIntent.MediaFailureReported(h.vm.assetKey("a1"), "Preview error: x"))
        h.vm.onIntent(EditorIntent.MediaFailureReported(h.vm.assetKey("a2"), "Preview error: x"))
        runCurrent()

        assertFalse(h.state.isPlaying)
        assertEquals(setOf("a1", "a2"), h.state.missingMedia.keys)
        // Two files, two messages: once per asset per loss.
        assertEquals(2, h.messages.size)
    }

    @Test
    fun `the loss reaches the project list as a count without rewriting the project`() = runTest(dispatcher) {
        val h = harness()
        h.pull("a1")

        h.vm.onIntent(EditorIntent.MediaFailureReported(h.vm.assetKey("a1"), "x"))
        advanceUntilIdle()

        assertTrue("project.json must not be rewritten by checking media", h.store.saved.isEmpty())
        assertEquals(listOf(0, 1), h.store.statuses)
    }

    @Test
    fun `opening a project with missing files publishes the count and still saves nothing`() = runTest(dispatcher) {
        val h = harness(present = setOf("content://usb/a2.mp4"))

        assertEquals(setOf("a1"), h.state.missingMedia.keys)
        assertTrue(h.store.saved.isEmpty())
        assertEquals(listOf(1), h.store.statuses)
    }

    @Test
    fun `check again clears the flag of files that are back and gives them new keys`() = runTest(dispatcher) {
        val h = harness(present = emptySet())
        assertEquals(setOf("a1", "a2"), h.state.missingMedia.keys)
        val oldKey = h.vm.assetKey("a1")
        h.vm.onIntent(EditorIntent.ShowRelink)
        h.plugIn("a1")

        h.vm.onIntent(EditorIntent.RecheckMissingMedia)
        advanceUntilIdle()

        assertEquals(setOf("a2"), h.state.missingMedia.keys)
        assertEquals(listOf("a1"), h.state.playableAssets.map { it.id })
        assertEquals(listOf("a1"), h.caches.invalidated)
        assertNotEquals(oldKey, h.vm.assetKey("a1"))
        assertTrue(h.state.relinkOpen)
        assertEquals("1 of 2 files can be read again; 1 still missing", h.messages.last())
        assertEquals(1, h.store.statuses.last())
        assertTrue(h.store.saved.isEmpty())
    }

    @Test
    fun `check again closes the dialog and says so when everything is readable`() = runTest(dispatcher) {
        val h = harness(present = emptySet())
        h.vm.onIntent(EditorIntent.ShowRelink)
        h.plugIn("a1", "a2")

        h.vm.onIntent(EditorIntent.RecheckMissingMedia)
        advanceUntilIdle()

        assertTrue(h.state.missingMedia.isEmpty())
        assertFalse(h.state.relinkOpen)
        assertEquals("All media can be read again", h.messages.last())
        assertEquals(0, h.store.statuses.last())
        assertEquals(2, h.state.playableAssets.size)
    }

    @Test
    fun `check again with the drive still out changes nothing and says so`() = runTest(dispatcher) {
        val h = harness(present = emptySet())
        val statuses = h.store.statuses.toList()

        h.vm.onIntent(EditorIntent.RecheckMissingMedia)
        advanceUntilIdle()

        assertEquals(setOf("a1", "a2"), h.state.missingMedia.keys)
        assertTrue(h.caches.invalidated.isEmpty())
        assertEquals("Still missing: connect the drive and check again, or use Relink", h.messages.last())
        assertEquals(statuses + 2, h.store.statuses)
    }

    @Test
    fun `a file lost twice is reported once per loss`() = runTest(dispatcher) {
        val h = harness()
        h.pull("a1")
        h.vm.onIntent(EditorIntent.MediaFailureReported(h.vm.assetKey("a1"), "x"))
        advanceUntilIdle()
        h.plugIn("a1")
        h.vm.onIntent(EditorIntent.RecheckMissingMedia)
        advanceUntilIdle()
        assertTrue(h.state.missingMedia.isEmpty())

        h.pull("a1")
        h.vm.onIntent(EditorIntent.MediaFailureReported(h.vm.assetKey("a1"), "x"))
        advanceUntilIdle()

        assertEquals(2, h.messages.count { it.startsWith("Media for a1.mp4") })
        assertEquals(setOf("a1"), h.state.missingMedia.keys)
    }

    @Test
    fun `check again with nothing missing says so`() = runTest(dispatcher) {
        val h = harness()

        h.vm.onIntent(EditorIntent.RecheckMissingMedia)
        advanceUntilIdle()

        assertEquals(listOf("No media is missing"), h.messages)
    }
}
