package com.qtekfun.ultimatevideoeditor.ui.export

import com.qtekfun.ultimatevideoeditor.engine.export.ExportCodec
import com.qtekfun.ultimatevideoeditor.engine.export.ExportHandle
import com.qtekfun.ultimatevideoeditor.engine.export.ExportListener
import com.qtekfun.ultimatevideoeditor.engine.export.ExportRequest
import com.qtekfun.ultimatevideoeditor.engine.export.ExportRunner
import com.qtekfun.ultimatevideoeditor.engine.export.ExportSettings
import com.qtekfun.ultimatevideoeditor.engine.verify.Finding
import com.qtekfun.ultimatevideoeditor.engine.verify.VerificationOutcome
import com.qtekfun.ultimatevideoeditor.engine.verify.VerifiedFacts
import com.qtekfun.ultimatevideoeditor.engine.verify.VerifyCheck
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.qtekfun.ultimatevideoeditor.ui.text.english
import com.qtekfun.ultimatevideoeditor.ui.text.UiText

/** The post-export verification as the dialog, the notification and the project list see it. */
@OptIn(ExperimentalCoroutinesApi::class)
class ExportVerificationFlowTest {
    private val dispatcher = UnconfinedTestDispatcher()

    private class Io : ExportIO {
        val deleted = mutableListOf<String>()
        override fun openAsset(uri: String) = 1
        override fun openOutput(uri: String) = 2
        override fun close(fd: Int) = Unit
        override fun deleteOutput(uri: String): Boolean {
            deleted += uri
            return true
        }
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

    private val io = Io()
    private val runner = Runner()
    private val verified = VerificationOutcome.Verified(VerifiedFacts(300, 10_000_000, 12, 150, 800))
    private val warning = VerificationOutcome.Warning(listOf(Finding(VerifyCheck.DECODE, "the decoder stopped", tailFrames = 20)), 300, 10_000_000)

    private fun request(audio: Boolean = true) = ExportRequest(
        settings = ExportSettings(1280, 720, 30, 1, ExportCodec.H264, 8_000_000),
        projectFpsNum = 30,
        projectFpsDen = 1,
        canvasWidth = 1280,
        canvasHeight = 720,
        totalFrames = 300,
        assetFds = emptyMap(),
        videoClips = emptyList(),
        audioSnapshot = if (audio) java.nio.ByteBuffer.allocateDirect(8) else null,
        outputFd = 2,
    )

    private fun executor(verifier: ExportVerifier?) =
        ExportExecutor(io, runner, CoroutineScope(SupervisorJob() + dispatcher), dispatcher, { 1_000L }, {}, verifier)

    private fun job() = ExportJob("p1", "Holiday", "content://out/h.mp4") { request() }

    @Test
    fun `the file is verified after the engine finishes and the state says so while it runs`() {
        val seen = mutableListOf<ExportJobState>()
        lateinit var executor: ExportExecutor
        executor = executor { target, _, progress ->
            seen += executor.state.value
            progress(500)
            seen += executor.state.value
            assertEquals(300L, target.expectation.totalFrames)
            assertEquals(30, target.expectation.fpsNum)
            assertTrue(target.expectation.hasAudio)
            assertEquals("content://out/h.mp4", target.uri)
            verified
        }
        executor.start(job())
        runner.listener!!.onProgress(1000)
        runner.listener!!.onFinished(null)

        val first = seen[0] as ExportJobState.Running
        assertTrue(first.verifying)
        assertEquals(0, first.progressPermille)
        assertEquals(500, (seen[1] as ExportJobState.Running).progressPermille)
        val done = executor.state.value as ExportJobState.Done
        assertEquals(verified, done.verification)
        assertTrue(io.deleted.isEmpty())
    }

    @Test
    fun `a second export is refused while the file is being verified`() {
        var refused: Boolean? = null
        lateinit var executor: ExportExecutor
        executor = executor { _, _, _ ->
            refused = !executor.start(job())
            verified
        }
        executor.start(job())
        runner.listener!!.onFinished(null)

        assertEquals(true, refused)
    }

    @Test
    fun `the signatures of the engine reach the verifier`() {
        var count = -1
        val executor = executor { target, _, _ ->
            count = target.signatures.size
            verified
        }
        executor.start(job())
        runner.listener!!.onSignatures(listOf(com.qtekfun.ultimatevideoeditor.engine.verify.FrameSignature(0, 0, IntArray(576), IntArray(576), IntArray(576))))
        runner.listener!!.onFinished(null)

        assertEquals(1, count)
    }

    @Test
    fun `a failed verification keeps the file`() {
        val executor = executor { _, _, _ -> warning }
        executor.start(job())
        runner.listener!!.onFinished(null)

        val done = executor.state.value as ExportJobState.Done
        assertEquals(warning, done.verification)
        assertTrue("the file must not be deleted", io.deleted.isEmpty())
    }

    @Test
    fun `cancel while verifying skips the check and says so`() {
        lateinit var executor: ExportExecutor
        executor = executor { _, cancel, _ ->
            executor.cancel()
            assertTrue(cancel())
            VerificationOutcome.Skipped
        }
        executor.start(job())
        runner.listener!!.onFinished(null)

        val done = executor.state.value as ExportJobState.Done
        assertEquals(VerificationOutcome.Skipped, done.verification)
        assertTrue(io.deleted.isEmpty())
    }

    @Test
    fun `a cancel that arrives as the engine finishes does not run the check at all`() {
        var ran = false
        val executor = executor { _, _, _ ->
            ran = true
            verified
        }
        executor.start(job())
        executor.cancel()
        runner.listener!!.onFinished(null)

        assertFalse(ran)
        assertEquals(VerificationOutcome.Skipped, (executor.state.value as ExportJobState.Done).verification)
    }

    @Test
    fun `a verifier that throws is could not verify, never verified`() {
        val executor = executor { _, _, _ -> throw IllegalStateException("boom") }
        executor.start(job())
        runner.listener!!.onFinished(null)

        val outcome = (executor.state.value as ExportJobState.Done).verification
        assertTrue(outcome.toString(), outcome is VerificationOutcome.CouldNotVerify)
    }

    @Test
    fun `no verifier installed leaves the outcome empty and changes nothing else`() {
        val executor = executor(null)
        executor.start(job())
        runner.listener!!.onFinished(null)

        assertNull((executor.state.value as ExportJobState.Done).verification)
    }

    @Test
    fun `a failed export is not verified`() {
        var ran = false
        val executor = executor { _, _, _ ->
            ran = true
            verified
        }
        executor.start(job())
        runner.listener!!.onFinished(com.qtekfun.ultimatevideoeditor.engine.export.ExportException(com.qtekfun.ultimatevideoeditor.engine.export.ExportErrorCode.CODEC_ERROR, "x"))

        assertFalse(ran)
        assertTrue(executor.state.value is ExportJobState.Failed)
    }

    // ---- what the user reads ----

    private fun done(note: String = "", v: VerificationOutcome?) = ExportJobState.Done("p1", "Holiday", "content://out/h.mp4", "h.mp4", note, v)

    @Test
    fun `verified says the video is complete with its frames and length`() {
        val text = exportResultText("", verified)

        assertEquals(ResultSeverity.OK, text.severity)
        assertEquals("Checked: the video is complete (300 frames, 00:10)", text.headline.english())
        assertEquals("300 frames, 10.0 s", text.detail.english())
        assertFalse(text.offersExportAgain)
    }

    @Test
    fun `a warning names the damage and offers to export again`() {
        val text = exportResultText("", warning)

        assertEquals(ResultSeverity.WARNING, text.severity)
        assertEquals("WARNING: the last 20 frames look damaged", text.headline.english())
        assertTrue(text.detail.english(), text.detail.english().contains("decoding: the decoder stopped"))
        assertTrue(text.offersExportAgain)
    }

    @Test
    fun `could not check never says checked`() {
        val text = exportResultText("", VerificationOutcome.CouldNotVerify(UiText.Raw("this device has no decoder to read the file back")))

        assertEquals(ResultSeverity.UNVERIFIED, text.severity)
        assertEquals("Could not check the file", text.headline.english())
        assertFalse(text.headline.english().contains("Checked"))
        assertFalse(text.offersExportAgain)
    }

    @Test
    fun `a cancelled check says it was skipped`() {
        val text = exportResultText("", VerificationOutcome.Skipped)

        assertEquals(ResultSeverity.UNVERIFIED, text.severity)
        assertEquals("Verification skipped (cancelled)", text.headline.english())
    }

    @Test
    fun `the exporter's repeated frames note is combined with the verification, never replaced`() {
        val note = "3 frames could not be decoded and were repeated"

        val ok = exportResultText(note, verified)
        assertEquals("Checked: the video is complete (300 frames, 00:10)", ok.headline.english())
        assertTrue(ok.detail.english(), ok.detail.english().contains("Note: $note."))

        val bad = exportResultText(note, warning)
        assertTrue(bad.headline.english().startsWith("WARNING"))
        assertTrue(bad.detail.english(), bad.detail.english().contains(note) && bad.detail.english().contains("the decoder stopped"))

        val unverified = exportResultText(note, VerificationOutcome.Skipped)
        assertTrue(unverified.detail.english().contains(note))
    }

    @Test
    fun `the notification carries the verdict`() {
        val ok = checkNotNull(exportNotificationFor(done(v = verified)))
        assertEquals("Export finished", ok.title.english())
        assertEquals("h.mp4 is saved · Checked: the video is complete (300 frames, 00:10)", ok.text.english())

        val bad = checkNotNull(exportNotificationFor(done(v = warning)))
        assertEquals("Export saved: check the file", bad.title.english())
        assertTrue(bad.text.english(), bad.text.english().contains("WARNING: the last 20 frames look damaged"))

        val unknown = checkNotNull(exportNotificationFor(done(v = VerificationOutcome.CouldNotVerify(UiText.Raw("no decoder")))))
        assertTrue(unknown.text.english(), unknown.text.english().endsWith("Could not check the file"))
    }

    @Test
    fun `the notification while verifying is cancellable and says verifying`() {
        val model = checkNotNull(exportNotificationFor(ExportJobState.Running("p1", "Holiday", 420, 0, verifying = true)))

        assertEquals("Verifying Holiday", model.title.english())
        assertEquals("Checking the saved file · 42%", model.text.english())
        assertTrue(model.ongoing)
        assertTrue(model.showCancel)
    }

    @Test
    fun `the project list bar shows verifying and then the verdict`() {
        val verifying = exportBarFor(ExportJobState.Running("p1", "Holiday", 420, 0, verifying = true)) as ExportBar.Running
        assertEquals("Verifying the saved file · 42%", verifying.detail.english())

        val finished = exportBarFor(done(v = warning)) as ExportBar.Finished
        assertEquals("WARNING: the last 20 frames look damaged", finished.result.headline.english())
        assertEquals(ResultSeverity.WARNING, finished.result.severity)
    }

    @Test
    fun `the QA result line is parseable`() {
        assertTrue(com.qtekfun.ultimatevideoeditor.engine.verify.VerificationText.resultLine(verified).startsWith("verification=verified frames=300 "))
        assertEquals("verification=warning tail_frames=20 checks=decode", com.qtekfun.ultimatevideoeditor.engine.verify.VerificationText.resultLine(warning))
        assertEquals("verification=skipped", com.qtekfun.ultimatevideoeditor.engine.verify.VerificationText.resultLine(VerificationOutcome.Skipped))
        assertTrue(com.qtekfun.ultimatevideoeditor.engine.verify.VerificationText.resultLine(VerificationOutcome.CouldNotVerify(UiText.Raw("no decoder"))).startsWith("verification=could_not_verify"))
    }
}
