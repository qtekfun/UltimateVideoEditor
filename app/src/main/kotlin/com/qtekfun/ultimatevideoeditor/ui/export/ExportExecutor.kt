package com.qtekfun.ultimatevideoeditor.ui.export

import com.qtekfun.ultimatevideoeditor.engine.export.ExportErrorCode
import com.qtekfun.ultimatevideoeditor.engine.export.ExportException
import com.qtekfun.ultimatevideoeditor.engine.export.ExportHandle
import com.qtekfun.ultimatevideoeditor.engine.export.ExportListener
import com.qtekfun.ultimatevideoeditor.engine.export.ExportRunner
import com.qtekfun.ultimatevideoeditor.engine.verify.FrameSignature
import com.qtekfun.ultimatevideoeditor.engine.verify.VerificationOutcome
import com.qtekfun.ultimatevideoeditor.engine.verify.VerifyExpectation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Runs at most one export and publishes it as [state]. It lives as long as the process (see [ExportCenter]), not as long
 * as a screen: leaving the editor, rotating, or the activity being destroyed does not touch a running export, and a
 * dialog that comes back simply observes [state] again. The foreground service only keeps the process alive and shows
 * the progress; it holds no export logic.
 *
 * Android-free so the state transitions are unit tested. [onStarted] runs once when a job begins (the Android side
 * starts the service there).
 */
class ExportExecutor(
    private val io: ExportIO,
    private val runner: ExportRunner,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onStarted: () -> Unit = {},
    /** Checks the finished file (first and last frames, structure); null skips the check, which only tests do. */
    private val verifier: ExportVerifier? = null,
    /** A description of the other long job when one is running (a project backup: "Backing up Holiday"), else null. */
    private val otherJobBusy: () -> String? = { null },
) : ExportJobHost {
    private val mutableState = MutableStateFlow<ExportJobState>(ExportJobState.Idle)
    override val state: StateFlow<ExportJobState> = mutableState.asStateFlow()

    private val lock = Any()
    private var handle: ExportHandle? = null
    private var estimator: ExportEstimator? = null
    private var job: ExportJob? = null
    private var cancelRequested = false
    private var finishNote = "" // set by the engine just before it finishes: frames that had to be repeated
    private var completed = false
    private var startedAt = 0L // clock() when the running job started, for the time shown in the summary
    private var expectation: VerifyExpectation? = null // what the running job promised about its file
    private var signatures: List<FrameSignature> = emptyList() // taken by the engine while exporting

    /** Why a new export cannot start right now (a project backup is running), in words for the user; null when it can. */
    fun refusalReason(): String? =
        otherJobBusy()?.let { "Another long job is running ($it). Start the export when it has finished, or cancel that one first." }

    /** Starts [next]; false when an export or a project backup is already running (nothing is touched then). */
    fun start(next: ExportJob): Boolean {
        val running = ExportJobState.Running(next.projectId, next.projectName, 0, clock())
        synchronized(lock) {
            if (mutableState.value.isRunning) return false
            if (otherJobBusy() != null) return false
            job = next
            cancelRequested = false
            completed = false
            startedAt = running.startedAtMs
            estimator = null
            expectation = null
            signatures = emptyList()
            mutableState.value = running
        }
        onStarted()
        scope.launch(ioDispatcher) { launchJob(next, running.startedAtMs) }
        return true
    }

    /** Asks the running export to stop. Also works while the files are still being opened: it stops right after. */
    override fun cancel() {
        val running = synchronized(lock) {
            if (!mutableState.value.isRunning) return
            cancelRequested = true
            handle
        }
        running?.cancel()
    }

    /**
     * The user has seen a finished, failed or cancelled export: go back to [ExportJobState.Idle]. Never touches a running
     * one. With [onlyProject] it only clears the result of that project, so an editor that is left does not wipe the
     * result another project's export is showing in the project list.
     */
    override fun acknowledge(onlyProject: String?) {
        mutableState.update { if (it.isRunning || (onlyProject != null && it.projectId != onlyProject)) it else ExportJobState.Idle }
    }

    private fun launchJob(next: ExportJob, startedAtMs: Long) {
        val started = try {
            val request = next.prepare()
            synchronized(lock) {
                expectation = VerifyExpectation(
                    request.totalFrames, request.settings.fpsNum, request.settings.fpsDen,
                    hasAudio = request.audioSnapshot != null, hdr = request.settings.hdr,
                )
                estimator = ExportEstimator(
                    totalFrames = request.totalFrames,
                    movieSeconds = request.totalFrames * request.settings.fpsDen.toDouble() / request.settings.fpsNum,
                    startedAtMs = startedAtMs,
                )
            }
            runner.start(
                request,
                object : ExportListener {
                    override fun onProgress(permille: Int) = publishProgress(permille)

                    override fun onNote(note: String) {
                        synchronized(lock) { finishNote = note }
                    }

                    override fun onSignatures(signatures: List<FrameSignature>) {
                        synchronized(lock) { this@ExportExecutor.signatures = signatures }
                    }

                    override fun onFinished(error: ExportException?) {
                        scope.launch(ioDispatcher) { finish(error) }
                    }
                },
            )
        } catch (e: ExportException) {
            finish(e)
            return
        } catch (e: IllegalArgumentException) {
            finish(ExportException(ExportErrorCode.INVALID_ARGUMENT, "Invalid export settings: ${e.message}"))
            return
        }
        val stopNow: Boolean
        val lateClose: Boolean
        synchronized(lock) {
            lateClose = completed // the engine finished before we got its handle
            if (!lateClose) handle = started
            stopNow = cancelRequested
        }
        if (lateClose) started.close() else if (stopNow) started.cancel()
    }

    private fun publishProgress(permille: Int) {
        val now = clock()
        val estimate = synchronized(lock) { estimator?.onProgress(permille, now) } ?: ExportEstimate()
        mutableState.update { current ->
            if (current is ExportJobState.Running) current.copy(progressPermille = permille, estimate = estimate) else current
        }
    }

    /**
     * Looks at the closed file: the state stays [ExportJobState.Running] (now `verifying`) so the dialog, the notification and
     * the service go on showing it, and Cancel skips the check. Never throws and never returns "fine" when it could not look.
     */
    private fun verify(source: ExportJob, promised: VerifyExpectation?, probes: List<FrameSignature>): VerificationOutcome? {
        val check = verifier ?: return null
        val cancelled = { synchronized(lock) { cancelRequested } }
        if (cancelled()) return VerificationOutcome.Skipped
        if (promised == null) return VerificationOutcome.CouldNotVerify("the export's settings were not recorded")
        mutableState.update { if (it is ExportJobState.Running) it.copy(progressPermille = 0, verifying = true, estimate = ExportEstimate()) else it }
        return try {
            check.verify(VerifyTarget(source.outputUri, promised, probes), cancelled) { permille ->
                mutableState.update { if (it is ExportJobState.Running && it.verifying) it.copy(progressPermille = permille) else it }
            }
        } catch (e: RuntimeException) {
            VerificationOutcome.CouldNotVerify("the check failed (${e.javaClass.simpleName}: ${e.message})")
        }
    }

    /** Releases the engine (joins its thread), removes the output unless it succeeded, then publishes the outcome. */
    private fun finish(error: ExportException?) {
        val finished: ExportHandle?
        val source: ExportJob
        val note: String
        val promised: VerifyExpectation?
        val probes: List<FrameSignature>
        synchronized(lock) {
            if (completed) return
            completed = true
            promised = expectation
            probes = signatures
            signatures = emptyList()
            note = finishNote
            finishNote = ""
            estimator = null
            finished = handle
            handle = null
            source = job ?: return
            job = null
        }
        finished?.close()
        val exportMs = (clock() - startedAt).coerceAtLeast(0)
        if (error == null) {
            val name = io.displayName(source.outputUri) ?: suggestedFileName(source.projectName)
            val verifyStart = clock()
            val verification = verify(source, promised, probes)
            val verifyMs = if (verification == null) 0 else (clock() - verifyStart).coerceAtLeast(0)
            mutableState.value = ExportJobState.Done(source.projectId, source.projectName, source.outputUri, name, note, verification, exportMs, verifyMs)
            return
        }
        val removed = io.deleteOutput(source.outputUri)
        // A failed export never leaves a half-written file unmentioned: when the provider refuses to delete it, say so.
        val leftover = if (removed) "" else
            " A partly written file could not be removed: ${io.displayName(source.outputUri) ?: "the chosen file"}. It is incomplete; delete it."
        mutableState.value =
            if (error.code == ExportErrorCode.CANCELLED) ExportJobState.Cancelled(source.projectId, source.projectName)
            else ExportJobState.Failed(source.projectId, source.projectName, error, leftover)
    }
}
