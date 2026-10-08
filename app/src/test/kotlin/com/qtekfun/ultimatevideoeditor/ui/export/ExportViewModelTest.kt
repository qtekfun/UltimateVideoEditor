package com.qtekfun.ultimatevideoeditor.ui.export

import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto
import com.qtekfun.ultimatevideoeditor.domain.FrameRate
import com.qtekfun.ultimatevideoeditor.domain.ProjectColorSpace
import com.qtekfun.ultimatevideoeditor.domain.clip
import com.qtekfun.ultimatevideoeditor.domain.timeline
import com.qtekfun.ultimatevideoeditor.domain.track
import com.qtekfun.ultimatevideoeditor.engine.export.ExportCodec
import com.qtekfun.ultimatevideoeditor.engine.export.ExportErrorCode
import com.qtekfun.ultimatevideoeditor.engine.export.ExportException
import com.qtekfun.ultimatevideoeditor.engine.export.ExportHandle
import com.qtekfun.ultimatevideoeditor.engine.export.ExportListener
import com.qtekfun.ultimatevideoeditor.engine.export.ExportRequest
import com.qtekfun.ultimatevideoeditor.engine.export.ExportRunner
import com.qtekfun.ultimatevideoeditor.engine.export.HdrExportSupport
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ExportViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeIO : ExportIO {
        var nextFd = 100
        val opened = mutableListOf<String>()
        val closed = mutableListOf<Int>()
        val deleted = mutableListOf<String>()
        var failOn: String? = null
        val names = mutableMapOf<String, String>()
        var free: Long? = null

        override fun freeBytes(): Long? = free

        override fun displayName(uri: String): String? = names[uri]

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

        override fun deleteOutput(uri: String): Boolean {
            deleted += uri
            return true
        }
    }

    private class FakeHandle : ExportHandle {
        var cancelled = false
        var closed = false
        override fun cancel() {
            cancelled = true
        }

        override fun close() {
            closed = true
        }
    }

    private class FakeRunner : ExportRunner {
        var request: ExportRequest? = null
        var listener: ExportListener? = null
        val handle = FakeHandle()
        var failWith: ExportException? = null

        override fun start(request: ExportRequest, listener: ExportListener): ExportHandle {
            failWith?.let { throw it }
            this.request = request
            this.listener = listener
            return handle
        }
    }

    private val io = FakeIO()
    private val runner = FakeRunner()

    private fun viewModel() = ExportViewModel(io, runner, dispatcher, projectId = "p1")

    private fun asset(id: String) = MediaAssetDto(id, "content://$id", 600, 30, 1, "Rec709-SDR")

    private fun input(withClip: Boolean = true) = ExportInput(
        projectId = "p1",
        projectName = "My movie",
        projectWidth = 1920,
        projectHeight = 1080,
        fps = FrameRate(30, 1),
        timeline = timeline(track("v1", *(if (withClip) arrayOf(clip("c1", 0, 90, asset = "a")) else emptyArray()))),
        assets = listOf(asset("a")),
    )

    private fun sourceInput(mbps: Double?, width: Int = 3840, height: Int = 2160, codec: String = "avc", canvas: Pair<Int, Int> = 3840 to 2160) = ExportInput(
        projectId = "p1",
        projectName = "My movie",
        projectWidth = canvas.first,
        projectHeight = canvas.second,
        fps = FrameRate(30, 1),
        timeline = timeline(track("v1", clip("c1", 0, 300, asset = "a"))),
        assets = listOf(
            asset("a").copy(
                videoWidth = width, videoHeight = height, videoCodec = codec, hasAudio = true,
                videoBitrate = mbps?.let { (it * 1_000_000).toLong() },
            ),
        ),
    )

    @Test
    fun `the dialog starts on the bit rate that covers the best clip`() {
        val vm = viewModel()
        vm.onIntent(ExportIntent.Open(sourceInput(80.0)))
        assertEquals(80, vm.state.value.bitrateMbps)

        val vm52 = viewModel()
        vm52.onIntent(ExportIntent.Open(sourceInput(52.0, codec = "hevc")))
        assertEquals(80, vm52.state.value.bitrateMbps)
        assertEquals(ExportCodec.HEVC, vm52.state.value.codec)
        assertEquals(52.0, vm52.state.value.recommendation!!.sourceMbps!!, 0.001)
    }

    @Test
    fun `without a known bit rate the dialog keeps today's defaults`() {
        val vm = viewModel()
        vm.onIntent(ExportIntent.Open(sourceInput(null)))
        assertEquals(suggestedBitrateMbps(3840, 2160, FrameRate(30, 1), ExportCodec.H264), vm.state.value.bitrateMbps)
        assertEquals(ExportCodec.H264, vm.state.value.codec)
    }

    @Test
    fun `the user can change what the clips suggested`() {
        val vm = viewModel()
        vm.onIntent(ExportIntent.Open(sourceInput(80.0)))
        vm.onIntent(ExportIntent.SelectCodec(ExportCodec.H264))
        vm.onIntent(ExportIntent.SelectBitrate(20))
        assertEquals(20, vm.state.value.bitrateMbps)
        assertEquals(ExportCodec.H264, vm.state.value.codec)
    }

    @Test
    fun `choosing a smaller size recomputes the rate for it`() {
        val vm = viewModel()
        vm.onIntent(ExportIntent.Open(sourceInput(80.0)))
        val hd = vm.state.value.resolutions.first { it.shortSide == 1080 }
        vm.onIntent(ExportIntent.SelectResolution(hd))
        // A quarter of the pixels needs about a quarter of the rate.
        assertEquals(20, vm.state.value.bitrateMbps)
    }

    @Test
    fun `the size estimate follows the bit rate`() {
        val vm = viewModel()
        vm.onIntent(ExportIntent.Open(sourceInput(80.0)))
        // 300 frames at 30 fps is 10 s: (80 + 0.192) Mbit/s * 10 s / 8 * 1.01
        val big = vm.state.value.sizeEstimate!!.bytes
        assertEquals(101_242_400.0, big.toDouble(), 1_000.0)
        vm.onIntent(ExportIntent.SelectBitrate(8))
        val small = vm.state.value.sizeEstimate!!.bytes
        assertTrue(small < big / 5)
    }

    @Test
    fun `an empty timeline has no estimate and the free space is carried`() {
        io.free = 5_000_000_000
        val vm = viewModel()
        vm.onIntent(ExportIntent.Open(input(withClip = false)))
        assertNull(vm.state.value.sizeEstimate)
        assertEquals(5_000_000_000, vm.state.value.freeBytes)
    }

    private fun ExportViewModel.openAndStart(output: String = "content://out/movie.mp4") {
        onIntent(ExportIntent.Open(input()))
        onIntent(ExportIntent.LocationChosen(output))
    }

    @Test
    fun `opening offers the project size and rate`() {
        val vm = viewModel()

        vm.onIntent(ExportIntent.Open(input()))

        val state = vm.state.value
        assertTrue(state.visible)
        assertEquals(1080, state.resolution?.shortSide)
        assertEquals(FrameRate(30, 1), state.frameRate)
        assertEquals(ExportPhase.Configuring, state.phase)
    }

    @Test
    fun `choosing a location asks for a named mp4`() = runTest {
        val vm = viewModel()
        vm.onIntent(ExportIntent.Open(input()))

        vm.onIntent(ExportIntent.ChooseLocation)

        assertEquals(ExportEffect.LaunchCreateDocument("My movie.mp4"), vm.effects.first())
    }

    @Test
    fun `dismissing the picker starts nothing`() {
        val vm = viewModel()
        vm.onIntent(ExportIntent.Open(input()))

        vm.onIntent(ExportIntent.LocationChosen(null))

        assertNull(runner.request)
        assertEquals(ExportPhase.Configuring, vm.state.value.phase)
    }

    @Test
    fun `starting passes the settings and descriptors to the engine`() {
        val vm = viewModel()
        vm.onIntent(ExportIntent.Open(input()))
        vm.onIntent(ExportIntent.SelectCodec(ExportCodec.HEVC))
        vm.onIntent(ExportIntent.SelectBitrate(20))

        vm.onIntent(ExportIntent.LocationChosen("content://out/movie.mp4"))

        val request = runner.request!!
        assertEquals(1920 to 1080, request.settings.width to request.settings.height)
        assertEquals(ExportCodec.HEVC, request.settings.codec)
        assertEquals(20_000_000, request.settings.videoBitrate)
        assertEquals(30 to 1, request.projectFpsNum to request.projectFpsDen)
        assertEquals(1920 to 1080, request.canvasWidth to request.canvasHeight)
        assertEquals(90L, request.totalFrames)
        assertEquals(1, request.videoClips.size)
        assertNotNull(request.audioSnapshot)
        assertEquals(listOf("content://a", "content://out/movie.mp4"), io.opened)
        assertEquals(io.nextFd - 1, request.outputFd)
        assertEquals(setOf(100), request.assetFds.values.toSet())
        assertEquals(0, (vm.state.value.phase as ExportPhase.Running).progressPermille)
    }

    @Test
    fun `the LUTs a clip uses are loaded and handed to the engine, a missing one is left out`() {
        val grade = com.qtekfun.ultimatevideoeditor.domain.TimelineOps.addEffect(
            input().timeline, "c1", com.qtekfun.ultimatevideoeditor.domain.Effect("e1", com.qtekfun.ultimatevideoeditor.domain.EffectType.LUT, listOf(11.0, 1.0)),
        ).let { (it as com.qtekfun.ultimatevideoeditor.domain.EditResult.Success).value }
        val gradedAndMissing = com.qtekfun.ultimatevideoeditor.domain.TimelineOps.addEffect(
            grade, "c1", com.qtekfun.ultimatevideoeditor.domain.Effect("e2", com.qtekfun.ultimatevideoeditor.domain.EffectType.LUT, listOf(22.0, 1.0)),
        ).let { (it as com.qtekfun.ultimatevideoeditor.domain.EditResult.Success).value }
        val lut = com.qtekfun.ultimatevideoeditor.domain.CubeParser.parse(
            "LUT_3D_SIZE 2\n0 0 0\n1 0 0\n0 1 0\n1 1 0\n0 0 1\n1 0 1\n0 1 1\n1 1 1\n",
        )
        val vm = ExportViewModel(io, runner, dispatcher, lutLoader = { key -> lut.takeIf { key == 11 } })

        vm.onIntent(ExportIntent.Open(input().copy(timeline = gradedAndMissing)))
        vm.onIntent(ExportIntent.LocationChosen("content://out/movie.mp4"))

        val luts = runner.request!!.luts
        assertEquals(listOf(11), luts.map { it.key })
        assertEquals(2, luts.single().size)
        assertEquals(2 * 2 * 2 * 3 * 4, luts.single().rgb.remaining())
    }

    @Test
    fun `an export at a lower frame rate counts output frames at that rate`() {
        val vm = viewModel()
        vm.onIntent(ExportIntent.Open(input().copy(fps = FrameRate(60, 1))))
        vm.onIntent(ExportIntent.SelectFrameRate(FrameRate(30, 1)))

        vm.onIntent(ExportIntent.LocationChosen("content://out/a.mp4"))

        assertEquals(45L, runner.request!!.totalFrames)
        assertEquals(30 to 1, runner.request!!.settings.fpsNum to runner.request!!.settings.fpsDen)
        assertEquals(60 to 1, runner.request!!.projectFpsNum to runner.request!!.projectFpsDen)
    }

    @Test
    fun `the done phase names the file as saved when the user renamed it in the picker`() {
        io.names["content://out/movie.mp4"] = "Holiday cut.mp4"
        val vm = viewModel()
        vm.openAndStart()

        runner.listener!!.onFinished(null)

        val done = vm.state.value.phase as ExportPhase.Done
        assertEquals(ExportPhase.Done("content://out/movie.mp4", "Holiday cut.mp4", exportMs = done.exportMs), done)
    }

    @Test
    fun `progress and completion reach the state and the engine is released`() {
        val vm = viewModel()
        vm.openAndStart()

        runner.listener!!.onProgress(420)
        assertEquals(420, (vm.state.value.phase as ExportPhase.Running).progressPermille)

        runner.listener!!.onFinished(null)

        val done = vm.state.value.phase as ExportPhase.Done
        // The fake clock ticks once per call, so the export time is whatever it counted: the rest of the phase must be exact.
        assertEquals(ExportPhase.Done("content://out/movie.mp4", "My movie.mp4", exportMs = done.exportMs), done)
        assertTrue(done.exportMs >= 0)
        assertTrue(runner.handle.closed)
        assertTrue(io.deleted.isEmpty())
    }

    @Test
    fun `progress carries an elapsed start and a smoothed time left`() {
        var now = 10_000L
        val vm = ExportViewModel(io, runner, dispatcher, clock = { now })
        vm.openAndStart()
        assertEquals(10_000L, (vm.state.value.phase as ExportPhase.Running).startedAtMs)

        // 100 permille every 2 s: after 8 s, 400 permille done and 12 s left.
        for (step in 1..16) {
            now = 10_000L + step * 500L
            runner.listener!!.onProgress(step * 25)
        }
        val running = vm.state.value.phase as ExportPhase.Running
        val left = checkNotNull(running.estimate.remainingMs)
        assertTrue("left was $left", left in 11_000..13_000)
        assertTrue(checkNotNull(running.estimate.speedFactor) > 0.0)

        runner.listener!!.onFinished(null)
        assertTrue(vm.state.value.phase is ExportPhase.Done)
    }

    @Test
    fun `a failed export reports the error and removes the partial file`() {
        val vm = viewModel()
        vm.openAndStart()

        runner.listener!!.onFinished(ExportException(ExportErrorCode.UNSUPPORTED_FORMAT, "no hevc"))

        val phase = vm.state.value.phase
        assertTrue(phase is ExportPhase.Failed)
        assertTrue((phase as ExportPhase.Failed).message.contains("no hevc"))
        assertEquals(listOf("content://out/movie.mp4"), io.deleted)
        assertTrue(runner.handle.closed)
    }

    @Test
    fun `cancelling stops the engine, returns to settings and removes the partial file`() {
        val vm = viewModel()
        vm.openAndStart()

        vm.onIntent(ExportIntent.Cancel)
        assertTrue(runner.handle.cancelled)
        runner.listener!!.onFinished(ExportException(ExportErrorCode.CANCELLED, "export cancelled"))

        assertEquals(ExportPhase.Configuring, vm.state.value.phase)
        assertEquals(listOf("content://out/movie.mp4"), io.deleted)
    }

    @Test
    fun `an unreadable clip fails before the engine starts and closes what was opened`() {
        io.failOn = "content://out/movie.mp4"
        val vm = viewModel()

        vm.openAndStart()

        assertNull(runner.request)
        assertEquals(listOf(100), io.closed) // the asset descriptor opened before the output failed
        assertTrue(vm.state.value.phase is ExportPhase.Failed)
        assertEquals(listOf("content://out/movie.mp4"), io.deleted)
    }

    @Test
    fun `an engine that cannot start fails the export and removes the output`() {
        runner.failWith = ExportException(ExportErrorCode.NOT_INITIALIZED, "no native engine")
        val vm = viewModel()

        vm.openAndStart()

        assertTrue(vm.state.value.phase is ExportPhase.Failed)
        assertEquals(listOf("content://out/movie.mp4"), io.deleted)
    }

    @Test
    fun `an empty timeline is reported without opening any file`() {
        val vm = viewModel()
        vm.onIntent(ExportIntent.Open(input(withClip = false)))

        vm.onIntent(ExportIntent.LocationChosen("content://out/movie.mp4"))

        assertTrue(io.opened.isEmpty())
        assertTrue((vm.state.value.phase as ExportPhase.Failed).message.contains("nothing to export"))
    }

    @Test
    fun `settings cannot change while exporting and dismissing only hides the dialog`() {
        val vm = viewModel()
        vm.openAndStart()

        vm.onIntent(ExportIntent.SelectCodec(ExportCodec.HEVC))
        vm.onIntent(ExportIntent.Dismiss)

        assertEquals(ExportCodec.H264, vm.state.value.codec)
        assertFalse(vm.state.value.visible)
        assertTrue(vm.state.value.isRunning)
    }

    @Test
    fun `changing the codec suggests a new bitrate but an explicit bitrate sticks`() {
        val vm = viewModel()
        vm.onIntent(ExportIntent.Open(input()))
        assertEquals(8, vm.state.value.bitrateMbps)

        vm.onIntent(ExportIntent.SelectCodec(ExportCodec.HEVC))
        assertEquals(4, vm.state.value.bitrateMbps)

        vm.onIntent(ExportIntent.SelectBitrate(50))
        assertEquals(50, vm.state.value.bitrateMbps)
    }

    @Test
    fun `sharing is only possible after a successful export`() = runTest {
        val vm = viewModel()
        vm.openAndStart()

        vm.onIntent(ExportIntent.Share)
        runner.listener!!.onFinished(null)
        vm.onIntent(ExportIntent.Share)

        assertEquals(ExportEffect.ShareFile("content://out/movie.mp4"), vm.effects.first())
    }

    @Test
    fun `reopening after a failure resets the dialog`() {
        val vm = viewModel()
        vm.openAndStart()
        runner.listener!!.onFinished(ExportException(ExportErrorCode.CODEC_ERROR, "boom"))
        assertFalse(vm.state.value.phase is ExportPhase.Configuring)

        vm.onIntent(ExportIntent.Open(input()))

        assertEquals(ExportPhase.Configuring, vm.state.value.phase)
    }

    // region Outliving the dialog

    private fun sharedExecutor() = ExportExecutor(io, runner, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + dispatcher), dispatcher)

    @Test
    fun `clearing the view model does not stop a running export`() {
        val executor = sharedExecutor()
        val vm = ExportViewModel(io, runner, dispatcher, executor = executor)
        vm.openAndStart()

        vm.javaClass.getDeclaredMethod("onCleared").apply { isAccessible = true }.invoke(vm)

        assertFalse(runner.handle.cancelled)
        assertTrue(executor.state.value.isRunning)
    }

    @Test
    fun `a new view model reconnects to the export that is running and follows it to the end`() {
        val executor = sharedExecutor()
        ExportViewModel(io, runner, dispatcher, executor = executor).openAndStart()
        runner.listener!!.onProgress(600)

        val reborn = ExportViewModel(io, runner, dispatcher, executor = executor)

        assertTrue(reborn.state.value.visible)
        assertEquals("My movie", reborn.state.value.projectName)
        assertEquals(600, (reborn.state.value.phase as ExportPhase.Running).progressPermille)

        runner.listener!!.onFinished(null)
        assertTrue(reborn.state.value.phase is ExportPhase.Done)
    }

    @Test
    fun `cancel from the reconnected dialog stops the same export`() {
        val executor = sharedExecutor()
        val old = ExportViewModel(io, runner, dispatcher, executor = executor)
        old.openAndStart()
        old.viewModelScope.cancel() // the editor was left: its view model no longer observes
        val reborn = ExportViewModel(io, runner, dispatcher, executor = executor)

        reborn.onIntent(ExportIntent.Cancel)
        runner.listener!!.onFinished(ExportException(ExportErrorCode.CANCELLED, "export cancelled"))

        assertTrue(runner.handle.cancelled)
        assertEquals(listOf("content://out/movie.mp4"), io.deleted)
        assertFalse(reborn.state.value.visible) // nothing to go back to: this view model never showed the settings
        assertEquals(ExportJobState.Idle, executor.state.value)
    }

    @Test
    fun `cancel from outside the dialog (the notification) returns the open dialog to its settings`() {
        val executor = sharedExecutor()
        val vm = ExportViewModel(io, runner, dispatcher, executor = executor)
        vm.openAndStart()

        executor.cancel()
        runner.listener!!.onFinished(ExportException(ExportErrorCode.CANCELLED, "export cancelled"))

        assertEquals(ExportPhase.Configuring, vm.state.value.phase)
        assertTrue(vm.state.value.visible)
    }

    @Test
    fun `closing a finished result clears it so the next dialog starts clean`() {
        val executor = sharedExecutor()
        val vm = ExportViewModel(io, runner, dispatcher, executor = executor)
        vm.openAndStart()
        runner.listener!!.onFinished(null)

        vm.onIntent(ExportIntent.Dismiss)

        assertEquals(ExportJobState.Idle, executor.state.value)
        assertFalse(vm.state.value.visible)
        assertFalse(ExportViewModel(io, runner, dispatcher, executor = executor).state.value.visible)
    }

    @Test
    fun `an export of another project is not mirrored and the Export button is refused with its name`() {
        val executor = sharedExecutor()
        ExportViewModel(io, runner, dispatcher, executor = executor, projectId = "other").openAndStart()
        val vm = ExportViewModel(io, runner, dispatcher, executor = executor, projectId = "p1")

        assertFalse(vm.state.value.visible)
        assertEquals("My movie", vm.state.value.blockedBy)
        val messages = mutableListOf<ExportEffect>()
        val job = kotlinx.coroutines.CoroutineScope(dispatcher).launch { vm.effects.collect { messages += it } }
        vm.onIntent(ExportIntent.Open(input()))
        job.cancel()

        assertEquals(listOf<ExportEffect>(ExportEffect.Message("Another export is running: My movie")), messages)
        assertFalse(vm.state.value.visible)
        assertFalse(runner.handle.cancelled)
    }

    @Test
    fun `the button of the exporting project shows its progress, and hiding the dialog does not stop the export`() {
        val executor = sharedExecutor()
        val vm = ExportViewModel(io, runner, dispatcher, executor = executor, projectId = "p1")
        vm.openAndStart()

        vm.onIntent(ExportIntent.Dismiss)
        runner.listener!!.onProgress(300)
        assertFalse(vm.state.value.visible)
        assertTrue(executor.state.value.isRunning)

        vm.onIntent(ExportIntent.Open(input()))
        assertTrue(vm.state.value.visible)
        assertEquals(300, (vm.state.value.phase as ExportPhase.Running).progressPermille)
    }

    @Test
    fun `a hidden dialog comes back with the result when the export ends`() {
        val executor = sharedExecutor()
        val vm = ExportViewModel(io, runner, dispatcher, executor = executor, projectId = "p1")
        vm.openAndStart()
        vm.onIntent(ExportIntent.Dismiss)

        runner.listener!!.onFinished(null)

        assertTrue(vm.state.value.visible)
        assertTrue(vm.state.value.phase is ExportPhase.Done)
    }

    @Test
    fun `ShowProgress opens the dialog on this project's export and ignores another project's`() {
        val executor = sharedExecutor()
        ExportViewModel(io, runner, dispatcher, executor = executor, projectId = "p1").openAndStart()
        val own = ExportViewModel(io, runner, dispatcher, executor = executor, projectId = "p1")
        own.onIntent(ExportIntent.Dismiss)
        own.onIntent(ExportIntent.ShowProgress)
        assertTrue(own.state.value.visible)

        val foreign = ExportViewModel(io, runner, dispatcher, executor = executor, projectId = "p2")
        foreign.onIntent(ExportIntent.ShowProgress)
        assertFalse(foreign.state.value.visible)
    }

    @Test
    fun `leaving another project's editor does not clear the result shown in the project list`() {
        val executor = sharedExecutor()
        ExportViewModel(io, runner, dispatcher, executor = executor, projectId = "p1").openAndStart()
        runner.listener!!.onFinished(null)
        val other = ExportViewModel(io, runner, dispatcher, executor = executor, projectId = "p2")

        other.javaClass.getDeclaredMethod("onCleared").apply { isAccessible = true }.invoke(other)
        other.onIntent(ExportIntent.Open(input()))

        assertTrue(executor.state.value is ExportJobState.Done)
    }

    @Test
    fun `dismissing the result in the project list closes the dialog that was showing it`() {
        val executor = sharedExecutor()
        val vm = ExportViewModel(io, runner, dispatcher, executor = executor, projectId = "p1")
        vm.openAndStart()
        runner.listener!!.onFinished(null)
        assertTrue(vm.state.value.visible)

        executor.acknowledge()

        assertFalse(vm.state.value.visible)
        assertEquals(ExportPhase.Configuring, vm.state.value.phase)
    }

    // endregion

    // region HDR

    private fun hdrViewModel(supported: Boolean = true) =
        ExportViewModel(io, runner, dispatcher, hdrSupport = HdrExportSupport { _, _, _, _ -> supported })

    private fun hdrInput() = input().copy(colorSpace = ProjectColorSpace.REC2020_HLG)

    @Test
    fun `an HDR project on a capable device defaults to HDR HEVC`() {
        val vm = hdrViewModel()

        vm.onIntent(ExportIntent.Open(hdrInput()))

        val state = vm.state.value
        assertTrue(state.hdrAvailable)
        assertTrue(state.hdr)
        assertEquals(ExportCodec.HEVC, state.codec)
        assertFalse(state.hdrUnsupportedNotice)
    }

    @Test
    fun `an HDR project on a device without Main10 HLG exports as SDR with a notice`() {
        val vm = hdrViewModel(supported = false)

        vm.onIntent(ExportIntent.Open(hdrInput()))

        val state = vm.state.value
        assertFalse(state.hdrAvailable)
        assertFalse(state.hdr)
        assertTrue(state.hdrUnsupportedNotice)
    }

    @Test
    fun `an SDR project never offers HDR`() {
        val vm = hdrViewModel()

        vm.onIntent(ExportIntent.Open(input()))

        assertFalse(vm.state.value.hdrAvailable)
        assertFalse(vm.state.value.hdr)
        assertFalse(vm.state.value.hdrUnsupportedNotice)
    }

    @Test
    fun `starting an HDR export tells the engine`() {
        val vm = hdrViewModel()
        vm.onIntent(ExportIntent.Open(hdrInput()))

        vm.onIntent(ExportIntent.LocationChosen("content://out/movie.mp4"))

        val settings = runner.request!!.settings
        assertTrue(settings.hdr)
        assertEquals(ExportCodec.HEVC, settings.codec)
    }

    @Test
    fun `choosing H264 or SDR turns HDR off`() {
        val vm = hdrViewModel()
        vm.onIntent(ExportIntent.Open(hdrInput()))

        vm.onIntent(ExportIntent.SelectCodec(ExportCodec.H264))
        assertFalse(vm.state.value.hdr)

        vm.onIntent(ExportIntent.SelectHdr(true))
        assertTrue(vm.state.value.hdr)
        assertEquals(ExportCodec.HEVC, vm.state.value.codec)

        vm.onIntent(ExportIntent.SelectHdr(false))
        assertFalse(vm.state.value.hdr)
    }

    @Test
    fun `HDR cannot be switched on when it is not available`() {
        val vm = hdrViewModel(supported = false)
        vm.onIntent(ExportIntent.Open(hdrInput()))

        vm.onIntent(ExportIntent.SelectHdr(true))

        assertFalse(vm.state.value.hdr)
    }

    @Test
    fun `smart export is off by default, needs HEVC and an upload preset turns it off`() {
        val vm = hdrViewModel()
        vm.onIntent(ExportIntent.Open(hdrInput()))
        assertFalse(vm.state.value.smart)

        vm.onIntent(ExportIntent.SelectSmart(true))
        assertTrue(vm.state.value.smart)

        vm.onIntent(ExportIntent.SelectCodec(ExportCodec.H264))
        assertFalse(vm.state.value.smart)
        vm.onIntent(ExportIntent.SelectSmart(true))
        assertFalse(vm.state.value.smart)

        vm.onIntent(ExportIntent.SelectCodec(ExportCodec.HEVC))
        vm.onIntent(ExportIntent.SelectSmart(true))
        vm.onIntent(ExportIntent.SelectPreset(ExportPresets.all.first()))
        assertFalse(vm.state.value.smart)
    }

    @Test
    fun `an upload preset exports SDR`() {
        val vm = hdrViewModel()
        vm.onIntent(ExportIntent.Open(hdrInput()))

        vm.onIntent(ExportIntent.SelectPreset(ExportPresets.all.first()))

        assertFalse(vm.state.value.hdr)
    }

    @Test
    fun `a device that stops supporting the chosen size fails clearly instead of starting`() {
        var supported = true
        val vm = ExportViewModel(io, runner, dispatcher, hdrSupport = HdrExportSupport { _, _, _, _ -> supported })
        vm.onIntent(ExportIntent.Open(hdrInput()))
        supported = false

        vm.onIntent(ExportIntent.LocationChosen("content://out/movie.mp4"))

        assertNull(runner.request)
        assertTrue((vm.state.value.phase as ExportPhase.Failed).message.contains("HDR"))
    }

    @Test
    fun `a native refusal of HDR suggests exporting as SDR`() {
        val vm = hdrViewModel()
        vm.onIntent(ExportIntent.Open(hdrInput()))
        vm.onIntent(ExportIntent.LocationChosen("content://out/movie.mp4"))

        runner.listener!!.onFinished(ExportException(ExportErrorCode.UNSUPPORTED_FORMAT, "no ten-bit surface"))

        val message = (vm.state.value.phase as ExportPhase.Failed).message
        assertTrue(message.contains("no ten-bit surface"))
        assertTrue(message.contains("SDR"))
    }

    // endregion

    // region post-export verification

    private fun verifyingViewModel(outcome: com.qtekfun.ultimatevideoeditor.engine.verify.VerificationOutcome): ExportViewModel {
        val executor = ExportExecutor(
            io, runner, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + dispatcher), dispatcher, { 0L }, {},
            ExportVerifier { _, _, _ -> outcome },
        )
        return ExportViewModel(io, runner, dispatcher, executor = executor, projectId = "p1")
    }

    @Test
    fun `the dialog ends on the verdict of the check`() {
        val verified = com.qtekfun.ultimatevideoeditor.engine.verify.VerificationOutcome.Verified(
            com.qtekfun.ultimatevideoeditor.engine.verify.VerifiedFacts(45, 1_500_000, 10, 45, 100),
        )
        val vm = verifyingViewModel(verified)
        vm.openAndStart()

        runner.listener!!.onFinished(null)

        val done = vm.state.value.phase as ExportPhase.Done
        assertEquals(verified, done.verification)
    }

    @Test
    fun `export again after a warning keeps the file and returns to the settings`() {
        val warning = com.qtekfun.ultimatevideoeditor.engine.verify.VerificationOutcome.Warning(
            listOf(com.qtekfun.ultimatevideoeditor.engine.verify.Finding(com.qtekfun.ultimatevideoeditor.engine.verify.VerifyCheck.PICTURE, "x", 5)), 45, 1_500_000,
        )
        val vm = verifyingViewModel(warning)
        vm.openAndStart()
        runner.listener!!.onFinished(null)
        assertTrue(vm.state.value.phase is ExportPhase.Done)

        vm.onIntent(ExportIntent.ExportAgain)

        assertEquals(ExportPhase.Configuring, vm.state.value.phase)
        assertTrue(vm.state.value.visible)
        assertTrue("the damaged file is the user's to delete", io.deleted.isEmpty())
    }

    @Test
    fun `export again does nothing when there is no finished export`() {
        val vm = viewModel()
        vm.onIntent(ExportIntent.Open(input()))

        vm.onIntent(ExportIntent.ExportAgain)

        assertEquals(ExportPhase.Configuring, vm.state.value.phase)
    }

    // endregion
}
