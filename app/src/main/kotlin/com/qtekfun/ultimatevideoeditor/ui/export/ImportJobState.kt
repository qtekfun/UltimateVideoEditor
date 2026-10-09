package com.qtekfun.ultimatevideoeditor.ui.export

import com.qtekfun.ultimatevideoeditor.ui.text.UiText
import com.qtekfun.ultimatevideoeditor.data.ImportReport
import com.qtekfun.ultimatevideoeditor.data.interchange.BundleWriteObserver
import kotlinx.coroutines.flow.StateFlow

/**
 * What the process-wide [BundleImportExecutor] is doing. The dialog, the bar of the project list and the notification of the
 * foreground service are all views of this one value, so they always agree.
 */
sealed interface ImportJobState {
    data object Idle : ImportJobState

    data class Running(
        val uri: String,
        /** The picked file's name; "the file" until the provider has said it. */
        val sourceName: String,
        val progress: BundleProgress,
        val startedAtMs: Long,
        /**
         * False for the first moments: an import of a small project file is over before anybody could read a dialog, so nothing
         * is shown until it has run for a little (see [BundleImportExecutor]). Bar, dialog and notification wait for this.
         */
        val revealed: Boolean = false,
    ) : ImportJobState

    data class Done(
        val uri: String,
        val sourceName: String,
        val report: ImportReport,
        /** Payload bytes unpacked or copied (0 for a plain project file). */
        val bytes: Long,
        val tookMs: Long,
        /** It ended before anything was shown: the project list says so in a message instead of a bar. */
        val quick: Boolean,
    ) : ImportJobState

    /** It failed; [message] names the cause and says nothing was added. Stays in the bar until dismissed. */
    data class Failed(val uri: String, val sourceName: String, val message: UiText) : ImportJobState

    /** The user cancelled; everything the import had made is removed. */
    data class Cancelled(val uri: String, val sourceName: String) : ImportJobState

    /** The package holds footage and no media folder is chosen: the project list asks for one and starts again. */
    data class NeedsMediaFolder(val uri: String) : ImportJobState

    val isRunning: Boolean get() = this is Running
}

/** One import to run. [run] reads the picked file (and may take minutes); it is called on the executor's IO dispatcher. */
class ImportJob(
    val uri: String,
    val run: suspend (BundleWriteObserver) -> ImportReport,
)

/** What the project list needs from the process-wide import; [BundleImportExecutor] is the real one. */
interface ImportJobHost {
    val state: StateFlow<ImportJobState>

    /** Whether the progress dialog is open. It opens by itself when the import has run past the quick-import threshold; Hide closes it. */
    val detailsOpen: StateFlow<Boolean>

    /** Returns at once; the work is on the IO dispatcher. Refused, with words, while another long job runs. */
    fun start(next: ImportJob): BundleStart

    fun cancel()

    /** Forgets a finished, failed or cancelled import. Never touches a running one. */
    fun acknowledge()

    fun showDetails()

    fun hideDetails()
}
