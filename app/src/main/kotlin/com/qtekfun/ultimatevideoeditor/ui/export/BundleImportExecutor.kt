package com.qtekfun.ultimatevideoeditor.ui.export

import com.qtekfun.ultimatevideoeditor.R
import com.qtekfun.ultimatevideoeditor.ui.text.UiText
import com.qtekfun.ultimatevideoeditor.data.ImportReport
import com.qtekfun.ultimatevideoeditor.data.ProjectError
import com.qtekfun.ultimatevideoeditor.data.interchange.BundleItemKind
import com.qtekfun.ultimatevideoeditor.data.interchange.BundleWriteObserver
import com.qtekfun.ultimatevideoeditor.data.interchange.ImportCancelled
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Runs at most one project import (a `.uvbundle`, a LumaFusion package or a plain project file) and publishes it as [state].
 * The mirror of [BundleExportExecutor]: it lives as long as the process (see [ExportCenter]), so leaving the screen, rotating
 * or the screen turning off does not touch it, the foreground service only keeps the process alive and mirrors the state into
 * a notification, and [start] returns at once: the picker's result handler never waits for the file.
 *
 * Progress comes from the importer's observer, throttled by the same [BundleProgressTracker] as the backup (about four updates
 * a second, smoothed time left). Cancel is a flag the importer reads between two 256 KB chunks; it removes whatever it had
 * made, so the state afterwards is [ImportJobState.Cancelled] and no half project exists.
 *
 * Nothing is shown for the first [revealAfterMs]: a small project file is imported before a dialog could be read, so the dialog,
 * the bar and the service wait ([ImportJobState.Running.revealed]); an import that ends before that is
 * [ImportJobState.Done.quick] and the project list answers with a message.
 *
 * One long job at a time: [otherJobBusy] says what else is running (a movie export, a backup), and then [start] refuses with
 * words, as the backup does; the other jobs refuse the same way while an import runs.
 */
class BundleImportExecutor(
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Once per job, when it is revealed: the Android side starts the foreground service here. */
    private val onStarted: () -> Unit = {},
    /** The picked file's name, asked from the provider on the IO dispatcher; null when it does not say. */
    private val displayName: (String) -> String? = { null },
    /** A description of the other long job when one is running ("Exporting Holiday"), else null. */
    private val otherJobBusy: () -> UiText? = { null },
    private val publishEveryMs: Long = BundleProgressTracker.DEFAULT_PUBLISH_MS,
    private val revealAfterMs: Long = REVEAL_AFTER_MS,
) : ImportJobHost {
    private val mutableState = MutableStateFlow<ImportJobState>(ImportJobState.Idle)
    override val state: StateFlow<ImportJobState> = mutableState.asStateFlow()

    private val mutableDetails = MutableStateFlow(false)
    override val detailsOpen: StateFlow<Boolean> = mutableDetails.asStateFlow()

    private val lock = Any()
    private var cancelRequested = false
    private var startedAt = 0L
    private var revealJob: Job? = null

    override fun start(next: ImportJob): BundleStart {
        val tracker = BundleProgressTracker(clock, publishEveryMs)
        synchronized(lock) {
            (mutableState.value as? ImportJobState.Running)?.let { return BundleStart.Refused(UiText.res(R.string.refused_import_running, ImportJobText.sourceLabel(it.sourceName))) }
            otherJobBusy()?.let { return BundleStart.Refused(UiText.res(R.string.refused_other_job_import, it)) }
            cancelRequested = false
            startedAt = clock()
            mutableState.value = ImportJobState.Running(next.uri, UNKNOWN_NAME, BundleProgress(), startedAt)
            mutableDetails.value = false
            revealJob = scope.launch {
                delay(revealAfterMs)
                reveal(next.uri)
            }
        }
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
        mutableState.update { if (it.isRunning) it else ImportJobState.Idle }
        if (!mutableState.value.isRunning) mutableDetails.value = false
    }

    override fun showDetails() {
        if (mutableState.value.let { it is ImportJobState.Running && it.revealed || it is ImportJobState.Done || it is ImportJobState.Failed }) mutableDetails.value = true
    }

    override fun hideDetails() {
        mutableDetails.value = false
    }

    private fun cancelled(): Boolean = synchronized(lock) { cancelRequested }

    /** The job has run long enough to be worth showing: bar, dialog and the service start now. */
    private fun reveal(uri: String) {
        val revealed = synchronized(lock) {
            val current = mutableState.value
            if (current is ImportJobState.Running && current.uri == uri && !current.revealed) {
                mutableState.value = current.copy(revealed = true)
                mutableDetails.value = true
                true
            } else {
                false
            }
        }
        if (revealed) onStarted()
    }

    private fun publish(progress: BundleProgress) {
        mutableState.update { if (it is ImportJobState.Running) it.copy(progress = progress) else it }
    }

    private suspend fun run(job: ImportJob, tracker: BundleProgressTracker) {
        val name = displayName(job.uri)?.takeIf { it.isNotBlank() } ?: UNKNOWN_NAME
        mutableState.update { if (it is ImportJobState.Running) it.copy(sourceName = name) else it }
        tracker.begin(0, 0)
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
        val report = try {
            job.run(observer)
        } catch (e: ImportCancelled) {
            return stopped(job.uri, name)
        } catch (e: ProjectError.MediaFolderRequired) {
            return needsFolder(job.uri)
        } catch (e: CancellationException) {
            // The scope itself was cancelled (the process is going away): nothing to publish to.
            stopped(job.uri, name)
            throw e
        } catch (e: Exception) {
            return if (cancelled()) stopped(job.uri, name) else failed(job.uri, name, e)
        }
        finish(job.uri, name, report, tracker.snapshot().doneBytes)
    }

    private fun endReveal() {
        revealJob?.cancel()
        revealJob = null
    }

    // Each end state is set under the lock, so a reveal that fires at the same moment cannot open a dialog for a job that is over.
    private fun stopped(uri: String, name: String) = synchronized(lock) {
        endReveal()
        mutableState.value = ImportJobState.Cancelled(uri, name)
        mutableDetails.value = false
    }

    private fun needsFolder(uri: String) = synchronized(lock) {
        endReveal()
        mutableState.value = ImportJobState.NeedsMediaFolder(uri)
        mutableDetails.value = false
    }

    private fun failed(uri: String, name: String, error: Exception) = synchronized(lock) {
        val wasShown = (mutableState.value as? ImportJobState.Running)?.revealed == true
        endReveal()
        mutableState.value = ImportJobState.Failed(uri, name, ImportJobText.failure(error))
        // A failure is never lost in a snackbar: it stays in the bar, and the dialog stays if the user was looking at it.
        if (!wasShown) mutableDetails.value = false
    }

    private fun finish(uri: String, name: String, report: ImportReport, bytes: Long) = synchronized(lock) {
        val quick = (mutableState.value as? ImportJobState.Running)?.revealed != true
        endReveal()
        val took = (clock() - startedAt).coerceAtLeast(0)
        mutableState.value = ImportJobState.Done(uri, name, report, bytes, took, quick)
        if (quick) mutableDetails.value = false
    }

    companion object {
        /** A project file of a few kilobytes is imported in well under this; nothing flashes for it. */
        const val REVEAL_AFTER_MS = 400L
        const val UNKNOWN_NAME = "the file"
    }
}
