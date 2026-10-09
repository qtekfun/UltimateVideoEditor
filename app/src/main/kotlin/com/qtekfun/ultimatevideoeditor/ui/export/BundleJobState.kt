package com.qtekfun.ultimatevideoeditor.ui.export

import com.qtekfun.ultimatevideoeditor.ui.text.UiText
import com.qtekfun.ultimatevideoeditor.data.interchange.BundleItemKind
import com.qtekfun.ultimatevideoeditor.data.interchange.BundleVerification
import com.qtekfun.ultimatevideoeditor.data.interchange.BundleWriteObserver
import com.qtekfun.ultimatevideoeditor.data.interchange.BundleWriteResult
import kotlinx.coroutines.flow.StateFlow

/** A snapshot of how far a backup has got; built by [BundleProgressTracker]. */
data class BundleProgress(
    val kind: BundleItemKind = BundleItemKind.PROJECT,
    /** Name of the file being added (a media file, a LUT, a font); empty for the project data. */
    val itemName: String = "",
    /** 1-based index of the media file being copied and how many there are; 0 when the entry is not media. */
    val mediaIndex: Int = 0,
    val mediaCount: Int = 0,
    val doneBytes: Long = 0,
    val totalBytes: Long = 0,
    /** Smoothed copy speed, or null while it is not known yet. */
    val bytesPerSecond: Double? = null,
    /** Time left, or null while unknown (just started, or nothing moved for a while). */
    val remainingMs: Long? = null,
) {
    /** Whole percent, 100 only when everything is done. */
    val percent: Int
        get() = if (totalBytes <= 0) 0 else (doneBytes.coerceAtMost(totalBytes) * 100 / totalBytes).toInt().coerceIn(0, 100)
}

/**
 * What the process-wide [BundleExportExecutor] is doing. The dialog, the bar of the project list and the notification of the
 * foreground service are all views of this one value, so they always agree.
 */
sealed interface BundleJobState {
    data object Idle : BundleJobState

    data class Running(
        val projectId: String,
        val projectName: String,
        val progress: BundleProgress,
        val startedAtMs: Long,
        /** The file is written and is being checked. */
        val verifying: Boolean = false,
    ) : BundleJobState

    data class Done(
        val projectId: String,
        val projectName: String,
        val uri: String,
        val fileName: String,
        val result: BundleWriteResult,
        /** What looking at the saved file found; null only when no verifier is installed (tests). */
        val verification: BundleVerification?,
        /** Wall time from start to the checked file. */
        val tookMs: Long,
    ) : BundleJobState

    data class Failed(val projectId: String, val projectName: String, val message: UiText, val leftoverNote: UiText = UiText.Empty) : BundleJobState

    /** The user cancelled. [leftoverNote] is not empty only when the provider refused to delete the partial file. */
    data class Cancelled(val projectId: String, val projectName: String, val leftoverNote: UiText = UiText.Empty) : BundleJobState

    val isRunning: Boolean get() = this is Running
}

/** One backup to run. [run] writes the bundle (and may take minutes); it is called on the executor's IO dispatcher. */
class BundleJob(
    val projectId: String,
    val projectName: String,
    val outputUri: String,
    val run: suspend (BundleWriteObserver) -> BundleWriteResult,
)

/** The answer to starting a backup. */
sealed interface BundleStart {
    data object Started : BundleStart

    /** Not started, and [reason] says why in words for the user (another long job is running). */
    data class Refused(val reason: UiText) : BundleStart
}

/** What the project list, the editors and the dialog need from the process-wide backup; [BundleExportExecutor] is the real one. */
interface BundleJobHost {
    val state: StateFlow<BundleJobState>

    /** Whether the progress dialog is open. A new job opens it; Hide closes it while the job goes on. */
    val detailsOpen: StateFlow<Boolean>

    fun start(next: BundleJob): BundleStart

    fun cancel()

    /** Forgets a finished, failed or cancelled backup. Never touches a running one. */
    fun acknowledge()

    fun showDetails()

    fun hideDetails()
}
