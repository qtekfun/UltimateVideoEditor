package com.ultimatevideo.uveditor.ui.export

import com.ultimatevideo.uveditor.data.model.MediaAssetDto
import com.ultimatevideo.uveditor.domain.ClipTransform
import com.ultimatevideo.uveditor.domain.FrameRate
import com.ultimatevideo.uveditor.domain.clip
import com.ultimatevideo.uveditor.domain.timeline
import com.ultimatevideo.uveditor.domain.track
import com.ultimatevideo.uveditor.engine.export.ExportHandle
import com.ultimatevideo.uveditor.engine.export.ExportListener
import com.ultimatevideo.uveditor.engine.export.ExportRequest
import com.ultimatevideo.uveditor.engine.export.ExportRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/** The "Faster export" option through the view model: default off, which descriptors open, and the fall back to originals. */
@OptIn(ExperimentalCoroutinesApi::class)
class ExportFasterExportTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class Io : ExportIO {
        var nextFd = 100
        val opened = mutableListOf<String>()
        val closed = mutableListOf<Int>()
        var failOn: String? = null

        override fun openAsset(uri: String): Int = open(uri)
        override fun openOutput(uri: String): Int = open(uri)

        private fun open(uri: String): Int {
            if (uri == failOn) throw IOException("cannot open $uri")
            opened += uri
            return nextFd++
        }

        override fun close(fd: Int) {
            closed += fd
        }

        override fun deleteOutput(uri: String) = true
    }

    private class Runner : ExportRunner {
        var request: ExportRequest? = null
        override fun start(request: ExportRequest, listener: ExportListener): ExportHandle {
            this.request = request
            return object : ExportHandle {
                override fun cancel() = Unit
                override fun close() = Unit
            }
        }
    }

    private val io = Io()
    private val runner = Runner()
    private val proxy = ExportProxy("file:///proxies/a.mp4", 1280, 720)

    // A 4K project of one video clip shown at a third of the canvas, so its proxy is exactly enough.
    private fun input(proxies: Map<String, ExportProxy>) = ExportInput(
        projectId = "p1",
        projectName = "Movie",
        projectWidth = 3840,
        projectHeight = 2160,
        fps = FrameRate(30, 1),
        timeline = timeline(
            track("v1", clip("c1", 0, 90, asset = "a").copy(transform = ClipTransform(scaleX = 1.0 / 3.0, scaleY = 1.0 / 3.0))),
        ),
        assets = listOf(MediaAssetDto("a", "content://a", 600, 30, 1, "Rec709-SDR")),
        proxies = proxies,
    )

    private fun viewModel() = ExportViewModel(io, runner, dispatcher, projectId = "p1")

    @Test
    fun `the option is off by default and counts what the dialog says`() {
        val vm = viewModel()
        vm.onIntent(ExportIntent.Open(input(mapOf("a" to proxy))))

        assertFalse(vm.state.value.fasterExport)
        assertEquals(1, vm.state.value.proxiesReady)
        assertEquals(1, vm.state.value.videoAssets)
    }

    @Test
    fun `an export without the option reads only originals`() {
        val vm = viewModel()
        vm.onIntent(ExportIntent.Open(input(mapOf("a" to proxy))))
        vm.onIntent(ExportIntent.LocationChosen("content://out.mp4"))

        assertEquals(listOf("content://a", "content://out.mp4"), io.opened)
        assertEquals(1, runner.request!!.assetFds.size)
    }

    @Test
    fun `with the option the small layer reads its proxy and the original stays for the sound`() {
        val vm = viewModel()
        vm.onIntent(ExportIntent.Open(input(mapOf("a" to proxy))))
        vm.onIntent(ExportIntent.SelectFasterExport(true))
        vm.onIntent(ExportIntent.LocationChosen("content://out.mp4"))

        val request = runner.request!!
        assertEquals(listOf("file:///proxies/a.mp4", "content://a", "content://out.mp4"), io.opened)
        assertEquals(2, request.assetFds.size)
        val videoKey = request.videoClips.single().assetKey
        assertEquals(100, request.assetFds.getValue(videoKey))
    }

    @Test
    fun `a proxy file that cannot be opened falls back to the originals`() {
        io.failOn = "file:///proxies/a.mp4"
        val vm = viewModel()
        vm.onIntent(ExportIntent.Open(input(mapOf("a" to proxy))))
        vm.onIntent(ExportIntent.SelectFasterExport(true))
        vm.onIntent(ExportIntent.LocationChosen("content://out.mp4"))

        val request = runner.request!!
        assertEquals(listOf("content://a", "content://out.mp4"), io.opened)
        assertEquals(1, request.assetFds.size)
        assertEquals(request.assetFds.keys.single(), request.videoClips.single().assetKey)
    }

    @Test
    fun `without a ready proxy the option cannot be turned on`() {
        val vm = viewModel()
        vm.onIntent(ExportIntent.Open(input(emptyMap())))
        vm.onIntent(ExportIntent.SelectFasterExport(true))

        assertFalse(vm.state.value.fasterExport)
        assertEquals(0, vm.state.value.proxiesReady)
        assertTrue(vm.state.value.videoAssets > 0)
    }
}
