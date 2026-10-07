package com.ultimatevideo.uveditor.ui.export

import com.ultimatevideo.uveditor.engine.export.ExportCodec
import com.ultimatevideo.uveditor.engine.export.ExportHandle
import com.ultimatevideo.uveditor.engine.export.ExportListener
import com.ultimatevideo.uveditor.engine.export.ExportRequest
import com.ultimatevideo.uveditor.engine.export.ExportRunner
import com.ultimatevideo.uveditor.engine.export.ExportSettings
import com.ultimatevideo.uveditor.engine.verify.VerificationOutcome
import com.ultimatevideo.uveditor.engine.verify.VerifiedFacts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The time the export and its check took, as the summary says it. */
@OptIn(ExperimentalCoroutinesApi::class)
class ExportTimingTest {
    @Test
    fun `durations read as seconds, minutes and hours`() {
        assertEquals("0 s", formatTook(0))
        assertEquals("4 s", formatTook(3_600))
        assertEquals("59 s", formatTook(59_400))
        assertEquals("1:00", formatTook(60_000))
        assertEquals("3:12", formatTook(192_000))
        assertEquals("28:11", formatTook(1_691_000))
        assertEquals("1:05:03", formatTook(3_903_000))
    }

    @Test
    fun `the timing line names the export and, when it ran, the check`() {
        assertEquals("Exported in 3:12, checked in 4 s", exportTimingLine(192_000, 3_600))
        assertEquals("Exported in 3:12", exportTimingLine(192_000, 0))
        assertEquals("", exportTimingLine(0, 3_600))
    }

    @Test
    fun `the result text carries the timing next to the verdict`() {
        val verified = VerificationOutcome.Verified(VerifiedFacts(300, 10_000_000, 12, 150, 800))
        val text = exportResultText("", verified, 192_000, 3_600)
        assertEquals("Exported in 3:12, checked in 4 s", text.timing)
        assertEquals(ResultSeverity.OK, text.severity)
        assertEquals("", exportResultText("", verified).timing)
    }

    private class Io : ExportIO {
        override fun openAsset(uri: String) = 1
        override fun openOutput(uri: String) = 2
        override fun close(fd: Int) = Unit
        override fun deleteOutput(uri: String) = true
    }

    private class Handle : ExportHandle {
        override fun cancel() = Unit
        override fun close() = Unit
    }

    private class Runner : ExportRunner {
        var listener: ExportListener? = null
        override fun start(request: ExportRequest, listener: ExportListener): ExportHandle {
            this.listener = listener
            return Handle()
        }
    }

    @Test
    fun `the executor measures the export and the check separately`() {
        val dispatcher = UnconfinedTestDispatcher()
        var now = 10_000L
        val runner = Runner()
        val verified = VerificationOutcome.Verified(VerifiedFacts(300, 10_000_000, 12, 150, 800))
        val executor = ExportExecutor(
            Io(), runner, CoroutineScope(SupervisorJob() + dispatcher), dispatcher, { now }, {},
            ExportVerifier { _, _, _ ->
                now += 4_000 // the check takes 4 s
                verified
            },
        )
        executor.start(
            ExportJob("p1", "Holiday", "content://out/h.mp4") {
                ExportRequest(
                    settings = ExportSettings(1280, 720, 30, 1, ExportCodec.H264, 8_000_000),
                    projectFpsNum = 30, projectFpsDen = 1, canvasWidth = 1280, canvasHeight = 720, totalFrames = 300,
                    assetFds = emptyMap(), videoClips = emptyList(), audioSnapshot = null, outputFd = 2,
                )
            },
        )
        now += 192_000 // the movie takes 3:12
        runner.listener!!.onProgress(1000)
        runner.listener!!.onFinished(null)

        val done = executor.state.value as ExportJobState.Done
        assertEquals(192_000L, done.exportMs)
        assertEquals(4_000L, done.verifyMs)
        assertTrue(exportResultText(done.note, done.verification, done.exportMs, done.verifyMs).timing.startsWith("Exported in 3:12"))
    }
}
