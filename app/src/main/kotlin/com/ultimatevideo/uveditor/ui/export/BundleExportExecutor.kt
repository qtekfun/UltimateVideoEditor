package com.ultimatevideo.uveditor.ui.export

import com.ultimatevideo.uveditor.data.interchange.BundleItemKind
import com.ultimatevideo.uveditor.data.interchange.BundleVerification
import com.ultimatevideo.uveditor.data.interchange.BundleWriteCancelled
import com.ultimatevideo.uveditor.data.interchange.BundleWriteObserver
import com.ultimatevideo.uveditor.data.interchange.BundleWriteResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Looks at the saved bundle after it was written; the device version reads it back with the importer's zip reader. */
fun interface BundleVerifier {
    /** Never throws and never returns "fine" when it could not look. [cancelled] turns true when the user stops the check. */
    fun verify(uri: String, written: BundleWriteResult, cancelled: () -> Boolean): BundleVerification
}

/**
 * Runs at most one project backup (`.uvbundle`) and publishes it as [state]. Like [ExportExecutor] it lives as long as the
 * process (see [ExportCenter]), so leaving the editor, rotating or the screen turning off does not touch it; the foreground
 * service only keeps the process alive and mirrors the state into a notification.
 *
 * One long job at a time: [otherJobBusy] says what else is running (a movie export), and then [start] refuses with words for the
 * user instead of queueing, because a queued multi-gigabyte copy that starts minutes later, unattended, is a surprise. The movie
 * export refuses the same way while a backup runs.
 *
 * Android-free so the transitions are unit tested. [onStarted] runs once per job (the Android side starts the service there).
 */
class BundleExportExecutor(
    private val io: ExportIO,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onStarted: () -> Unit = {},
    private val verifier: BundleVerifier? = null,
    /** A description of the other long job when one is running ("Exporting Holiday"), else null. */
    private val otherJobBusy: () -> String? = { null },
    private val publishEveryMs: Long = BundleProgressTracker.DEFAULT_PUBLISH_MS,
) : BundleJobHost {
    private val mutableState = MutableStateFlow<BundleJobState>(BundleJobState.Idle)
    override val state: StateFlow<BundleJobState> = mutableState.asStateFlow()

    private val mutableDetails = MutableStateFlow(false)
    override val detailsOpen: StateFlow<Boolean> = mutableDetails.asStateFlow()

    private val lock = Any()
    private var cancelRequested = false
    private var startedAt = 0L

    override fun start(next: BundleJob): BundleStart {
        val tracker = BundleProgressTracker(clock, publishEveryMs)
        synchronized(lock) {
            (mutableState.value as? BundleJobState.Running)?.let { return BundleStart.Refused("A backup is already running: ${it.projectName}. Wait for it to finish or cancel it first.") }
            otherJobBusy()?.let { return BundleStart.Refused("Another long job is running ($it). Start the backup when it has finished, or cancel that one first.") }
            cancelRequested = false
            startedAt = clock()
            mutableState.value = BundleJobState.Running(next.projectId, next.projectName, BundleProgress(), startedAt)
            mutableDetails.value = true
        }
        onStarted()
        scope.launch(ioDispatcher) { run(next, tracker) }
        return BundleStart.Started
    }

    override fun cancel() {
        synchronized(lock) {
            if (!mutableState.value.isRunning) return
            cancelRequested = true
        }
    }

    override fun acknowledge() {
        mutableState.update { if (it.isRunning) it else BundleJobState.Idle }
        if (!mutableState.value.isRunning) mutableDetails.value = false
    }

    override fun showDetails() {
        if (mutableState.value !is BundleJobState.Idle) mutableDetails.value = true
    }

    override fun hideDetails() {
        mutableDetails.value = false
    }

    private fun cancelled(): Boolean = synchronized(lock) { cancelRequested }

    private fun publish(progress: BundleProgress) {
        mutableState.update { if (it is BundleJobState.Running && !it.verifying) it.copy(progress = progress) else it }
    }

    private suspend fun run(job: BundleJob, tracker: BundleProgressTracker) {
        val observer = object : BundleWriteObserver {
            override fun onStart(totalBytes: Long, mediaCount: Int) {
                tracker.begin(totalBytes, mediaCount)
                publish(tracker.snapshot())
            }

            override fun onItem(kind: BundleItemKind, name: String, mediaIndex: Int) = publish(tracker.item(kind, name, mediaIndex))

            override fun onBytes(count: Long) {
                tracker.bytes(count)?.let(::publish)
            }

            override fun isCancelled(): Boolean = cancelled()
        }
        val result = try {
            job.run(observer)
        } catch (e: BundleWriteCancelled) {
            return stopped(job)
        } catch (e: CancellationException) {
            stopped(job)
            throw e
        } catch (e: Exception) {
            // Whatever the error, the partial file goes: a half-written backup must not look like a good one.
            return if (cancelled()) stopped(job) else failed(job, e)
        }
        // A Cancel that arrived just as the last byte went in still means "I do not want this file".
        if (cancelled()) return stopped(job)
        finish(job, result)
    }

    private fun stopped(job: BundleJob) {
        val removed = io.deleteOutput(job.outputUri)
        val leftover = if (removed) "" else leftoverNote(job)
        mutableState.value = BundleJobState.Cancelled(job.projectId, job.projectName, leftover)
        if (leftover.isEmpty()) mutableDetails.value = false
    }

    private fun failed(job: BundleJob, error: Exception) {
        val removed = io.deleteOutput(job.outputUri)
        mutableState.value = BundleJobState.Failed(job.projectId, job.projectName, BundleJobText.failure(error), if (removed) "" else leftoverNote(job))
    }

    /** A failed or cancelled backup never leaves a half-written file unmentioned: when the provider refuses to delete it, say so. */
    private fun leftoverNote(job: BundleJob) =
        " A partly written file could not be removed: ${io.displayName(job.outputUri) ?: "the chosen file"}. It is incomplete; delete it."

    private fun finish(job: BundleJob, result: BundleWriteResult) {
        val name = io.displayName(job.outputUri) ?: suggestedBundleName(job.projectName)
        val check = verifier
        var verification: BundleVerification? = null
        if (check != null) {
            // The state stays Running (now `verifying`) so the dialog, bar and notification go on showing it; Cancel skips the check.
            mutableState.update { if (it is BundleJobState.Running) it.copy(verifying = true) else it }
            verification = if (cancelled()) BundleVerification.Skipped else try {
                check.verify(job.outputUri, result, ::cancelled)
            } catch (e: RuntimeException) {
                BundleVerification.CouldNotVerify("the check failed (${e.javaClass.simpleName}: ${e.message})")
            }
        }
        val took = (clock() - startedAt).coerceAtLeast(0)
        mutableState.value = BundleJobState.Done(job.projectId, job.projectName, job.outputUri, name, result, verification, took)
    }
}

/** The file name a bundle gets when the provider cannot say what the user called it. */
fun suggestedBundleName(projectName: String): String =
    projectName.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').ifEmpty { "project" } + ".uvbundle"
