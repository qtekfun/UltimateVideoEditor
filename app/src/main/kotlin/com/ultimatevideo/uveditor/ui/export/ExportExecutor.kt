package com.ultimatevideo.uveditor.ui.export

import com.ultimatevideo.uveditor.engine.export.ExportErrorCode
import com.ultimatevideo.uveditor.engine.export.ExportException
import com.ultimatevideo.uveditor.engine.export.ExportHandle
import com.ultimatevideo.uveditor.engine.export.ExportListener
import com.ultimatevideo.uveditor.engine.export.ExportRunner
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
) : ExportJobHost {
    private val mutableState = MutableStateFlow<ExportJobState>(ExportJobState.Idle)
    override val state: StateFlow<ExportJobState> = mutableState.asStateFlow()

    private val lock = Any()
    private var handle: ExportHandle? = null
    private var estimator: ExportEstimator? = null
    private var job: ExportJob? = null
    private var cancelRequested = false
    private var completed = false

    /** Starts [next]; false when an export is already running (nothing is touched then). */
    fun start(next: ExportJob): Boolean {
        val running = ExportJobState.Running(next.projectId, next.projectName, 0, clock())
        synchronized(lock) {
            if (mutableState.value.isRunning) return false
            job = next
            cancelRequested = false
            completed = false
            estimator = null
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

    /** Releases the engine (joins its thread), removes the output unless it succeeded, then publishes the outcome. */
    private fun finish(error: ExportException?) {
        val finished: ExportHandle?
        val source: ExportJob
        synchronized(lock) {
            if (completed) return
            completed = true
            estimator = null
            finished = handle
            handle = null
            source = job ?: return
            job = null
        }
        finished?.close()
        if (error == null) {
            val name = io.displayName(source.outputUri) ?: suggestedFileName(source.projectName)
            mutableState.value = ExportJobState.Done(source.projectId, source.projectName, source.outputUri, name)
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
