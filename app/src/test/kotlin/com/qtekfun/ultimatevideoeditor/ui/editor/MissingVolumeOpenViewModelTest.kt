package com.qtekfun.ultimatevideoeditor.ui.editor

import com.qtekfun.ultimatevideoeditor.data.MediaCaches
import com.qtekfun.ultimatevideoeditor.data.MediaImporter
import com.qtekfun.ultimatevideoeditor.data.MediaProblem
import com.qtekfun.ultimatevideoeditor.data.ProbedMedia
import com.qtekfun.ultimatevideoeditor.data.ProjectStore
import com.qtekfun.ultimatevideoeditor.data.guardMedia
import com.qtekfun.ultimatevideoeditor.data.model.ClipDto
import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto
import com.qtekfun.ultimatevideoeditor.data.model.ProjectDto
import com.qtekfun.ultimatevideoeditor.data.model.ProjectSettingsDto
import com.qtekfun.ultimatevideoeditor.data.model.TrackDto
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Opening a project whose footage is on a removable drive that is not connected must never crash: the files are reported as
 * missing and nothing tries to open them (the owner's crash was an uncaught IllegalArgumentException from the provider).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MissingVolumeOpenViewModelTest {

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

    /** Behaves like the Android importer on an absent volume: the provider throws [failure] and the I/O boundary maps it. */
    private class AbsentVolumeImporter(private val failure: () -> Exception) : MediaImporter {
        var probes = 0

        override suspend fun import(uri: String): ProbedMedia = guardMedia(uri) {
            probes++
            throw failure()
        }
    }

    private object NoCaches : MediaCaches {
        override fun invalidate(assetId: String) = Unit
    }

    private fun asset(id: String) =
        MediaAssetDto(id, "content://com.android.externalstorage.documents/tree/4450-56F6%3A/document/4450-56F6%3A$id.mp4", durationFrames = 300, nativeFpsNum = 30, nativeFpsDen = 1, colorSpace = "Rec709-SDR")

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

    private val failures: List<Pair<String, () -> Exception>> = listOf(
        "unmounted volume" to { IllegalArgumentException("Failed to determine if 4450-56F6:Movies/VID20260930191900.mp4 is child of 4450-56F6:: java.io.FileNotFoundException: Missing root") },
        "file not found" to { FileNotFoundException("No content provider") },
        "io error" to { IOException("EIO (I/O error)") },
        "illegal state" to { IllegalStateException("provider died") },
        "unsupported operation" to { UnsupportedOperationException("query") },
        "revoked grant" to { SecurityException("Permission Denial") },
    )

    @Test
    fun `every kind of provider failure opens the project with the media flagged missing`() = runTest(dispatcher) {
        for ((name, failure) in failures) {
            val importer = AbsentVolumeImporter(failure)
            val vm = EditorViewModel("p1", FakeStore(project), importer, idGenerator = { "n1" }, mediaCaches = NoCaches)
            advanceUntilIdle()

            val state = vm.state.value
            assertNull(name, state.loadError)
            assertFalse(name, state.isLoading)
            assertTrue(name, state.mediaChecked)
            val expected = if (failure() is SecurityException) MediaProblem.PERMISSION_LOST else MediaProblem.UNREADABLE
            assertEquals(name, mapOf("a1" to expected, "a2" to expected), state.missingMedia)
            assertTrue(name, state.playableAssets.isEmpty())
            assertEquals(name, 2, state.missingAssets.size)
        }
    }

    @Test
    fun `nothing is playable until the files have been checked`() = runTest(dispatcher) {
        val importer = AbsentVolumeImporter { IllegalArgumentException("Failed to determine if x is child of y") }
        val vm = EditorViewModel("p1", FakeStore(project), importer, idGenerator = { "n1" }, mediaCaches = NoCaches)

        runCurrent()
        // Either still loading or loaded but unchecked: the preview, mixer and filmstrip workers must see no file.
        assertTrue(vm.state.value.playableAssets.isEmpty())

        advanceUntilIdle()
        assertTrue(vm.state.value.mediaChecked)
        assertEquals(2, importer.probes)
    }
}
