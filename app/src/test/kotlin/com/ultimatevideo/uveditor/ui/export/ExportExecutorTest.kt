package com.ultimatevideo.uveditor.ui.export

import com.ultimatevideo.uveditor.engine.export.ExportCodec
import com.ultimatevideo.uveditor.engine.export.ExportErrorCode
import com.ultimatevideo.uveditor.engine.export.ExportException
import com.ultimatevideo.uveditor.engine.export.ExportHandle
import com.ultimatevideo.uveditor.engine.export.ExportListener
import com.ultimatevideo.uveditor.engine.export.ExportRequest
import com.ultimatevideo.uveditor.engine.export.ExportRunner
import com.ultimatevideo.uveditor.engine.export.ExportSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExportExecutorTest {
    private val dispatcher = UnconfinedTestDispatcher()

    private class Io : ExportIO {
        val deleted = mutableListOf<String>()
        var name: String? = null
        override fun openAsset(uri: String) = 1
        override fun openOutput(uri: String) = 2
        override fun close(fd: Int) = Unit
        override fun displayName(uri: String): String? = name
        override fun deleteOutput(uri: String): Boolean {
            deleted += uri
            return true
        }
    }

    private class Handle : ExportHandle {
        var cancelled = false
        var closed = false
        override fun cancel() {
            cancelled = true
        }

        override fun close() {
            closed = true
        }
    }

    private class Runner : ExportRunner {
        val handle = Handle()
        var listener: ExportListener? = null
        var starts = 0
        override fun start(request: ExportRequest, listener: ExportListener): ExportHandle {
            starts++
            this.listener = listener
            return handle
        }
    }

    private val io = Io()
    private val runner = Runner()
    private var started = 0
    private var now = 1_000L
    private val executor = ExportExecutor(io, runner, CoroutineScope(SupervisorJob() + dispatcher), dispatcher, { now }, { started++ })

    private fun request() = ExportRequest(
        settings = ExportSettings(1280, 720, 30, 1, ExportCodec.H264, 8_000_000),
        projectFpsNum = 30,
        projectFpsDen = 1,
        canvasWidth = 1280,
        canvasHeight = 720,
        totalFrames = 300,
        assetFds = emptyMap(),
        videoClips = emptyList(),
        audioSnapshot = null,
        outputFd = 2,
    )

    private fun job(name: String = "Holiday", prepare: () -> ExportRequest = ::request) = ExportJob("p1", name, "content://out/$name.mp4", prepare)

    @Test
    fun `starting publishes a running state at zero and tells the service side once`() {
        assertTrue(executor.start(job()))

        val running = executor.state.value as ExportJobState.Running
        assertEquals("Holiday", running.projectName)
        assertEquals(0, running.progressPermille)
        assertEquals(1_000L, running.startedAtMs)
        assertEquals(1, started)
        assertEquals(1, runner.starts)
    }

    @Test
    fun `a second export is refused while one runs and the first is not disturbed`() {
        executor.start(job("One"))

        assertFalse(executor.start(job("Two")))

        assertEquals("One", (executor.state.value as ExportJobState.Running).projectName)
        assertEquals(1, runner.starts)
        assertEquals(1, started)
    }

    @Test
    fun `progress updates the running state and a finished export ends in Done with the saved name`() {
        io.name = "Renamed.mp4"
        executor.start(job())

        runner.listener!!.onProgress(420)
        assertEquals(420, (executor.state.value as ExportJobState.Running).progressPermille)
        runner.listener!!.onFinished(null)

        assertEquals(ExportJobState.Done("p1", "Holiday", "content://out/Holiday.mp4", "Renamed.mp4"), executor.state.value)
        assertTrue(runner.handle.closed)
        assertTrue(io.deleted.isEmpty())
    }

    @Test
    fun `the time left estimate is carried in the running state`() {
        executor.start(job())

        for (step in 1..16) {
            now = 1_000L + step * 500L
            runner.listener!!.onProgress(step * 25)
        }

        val left = checkNotNull((executor.state.value as ExportJobState.Running).estimate.remainingMs)
        assertTrue("left was $left", left in 11_000..13_000)
    }

    @Test
    fun `progress after the end changes nothing`() {
        executor.start(job())
        runner.listener!!.onFinished(null)

        runner.listener!!.onProgress(900)

        assertTrue(executor.state.value is ExportJobState.Done)
    }

    @Test
    fun `cancel stops the engine, then the cancelled outcome removes the partial file`() {
        executor.start(job())

        executor.cancel()
        assertTrue(runner.handle.cancelled)
        assertTrue(executor.state.value.isRunning) // still running until the engine confirms
        runner.listener!!.onFinished(ExportException(ExportErrorCode.CANCELLED, "export cancelled"))

        assertEquals(ExportJobState.Cancelled("p1", "Holiday"), executor.state.value)
        assertEquals(listOf("content://out/Holiday.mp4"), io.deleted)
        assertTrue(runner.handle.closed)
    }

    @Test
    fun `a failure keeps its error and removes the partial file`() {
        executor.start(job())

        runner.listener!!.onFinished(ExportException(ExportErrorCode.CODEC_ERROR, "boom"))

        val failed = executor.state.value as ExportJobState.Failed
        assertEquals(ExportErrorCode.CODEC_ERROR, failed.error?.code)
        assertEquals(listOf("content://out/Holiday.mp4"), io.deleted)
    }

    @Test
    fun `a request that cannot be built fails without starting the engine and removes the output`() {
        executor.start(job { throw ExportException(ExportErrorCode.IO_ERROR, "cannot open") })

        assertTrue(executor.state.value is ExportJobState.Failed)
        assertEquals(0, runner.starts)
        assertEquals(listOf("content://out/Holiday.mp4"), io.deleted)
    }

    @Test
    fun `invalid settings are mapped to an invalid-argument failure`() {
        executor.start(job { throw IllegalArgumentException("size must be even") })

        val failed = executor.state.value as ExportJobState.Failed
        assertEquals(ExportErrorCode.INVALID_ARGUMENT, failed.error?.code)
    }

    @Test
    fun `cancelling while the files are still being opened stops the engine as soon as it exists`() {
        // prepare() cancels from inside itself, as the user would while descriptors are opened.
        executor.start(job { request().also { executor.cancel() } })

        assertTrue(runner.handle.cancelled)
        runner.listener!!.onFinished(ExportException(ExportErrorCode.CANCELLED, "export cancelled"))
        assertEquals(ExportJobState.Cancelled("p1", "Holiday"), executor.state.value)
    }

    @Test
    fun `cancel with nothing running does nothing`() {
        executor.cancel()

        assertEquals(ExportJobState.Idle, executor.state.value)
        assertFalse(runner.handle.cancelled)
    }

    @Test
    fun `acknowledge clears a finished result but never a running export`() {
        executor.start(job())
        executor.acknowledge()
        assertTrue(executor.state.value.isRunning)

        runner.listener!!.onFinished(null)
        executor.acknowledge()

        assertEquals(ExportJobState.Idle, executor.state.value)
    }

    @Test
    fun `a new export can start after the previous one ended`() {
        executor.start(job("One"))
        runner.listener!!.onFinished(null)

        assertTrue(executor.start(job("Two")))

        assertEquals("Two", (executor.state.value as ExportJobState.Running).projectName)
        assertEquals(2, started)
    }

    @Test
    fun `an engine that finishes before its handle is returned is still released`() {
        val early = object : ExportRunner {
            val handle = Handle()
            override fun start(request: ExportRequest, listener: ExportListener): ExportHandle {
                listener.onFinished(null) // the finish is processed before start() returns
                return handle
            }
        }
        val ex = ExportExecutor(io, early, CoroutineScope(SupervisorJob() + dispatcher), dispatcher, { now })

        ex.start(job())

        assertTrue(ex.state.value is ExportJobState.Done)
        assertTrue(early.handle.closed)
    }

    @Test
    fun `a state observer that joins late sees the running export`() {
        executor.start(job())
        runner.listener!!.onProgress(300)

        // A new dialog (after the activity was recreated) just reads the current value.
        val late = executor.state.value as ExportJobState.Running

        assertEquals(300, late.progressPermille)
    }
}
