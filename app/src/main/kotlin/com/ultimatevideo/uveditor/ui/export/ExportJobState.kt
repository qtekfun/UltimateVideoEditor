package com.ultimatevideo.uveditor.ui.export

import com.ultimatevideo.uveditor.engine.export.ExportErrorCode
import com.ultimatevideo.uveditor.engine.export.ExportException
import com.ultimatevideo.uveditor.engine.export.ExportRequest
import com.ultimatevideo.uveditor.engine.verify.VerificationOutcome

/**
 * What the process-wide [ExportExecutor] is doing. The export dialog and the notification of the foreground service
 * are both views of this one value, so they always agree and either can be gone while the export goes on.
 */
sealed interface ExportJobState {
    /** Nothing running and nothing waiting to be acknowledged. */
    data object Idle : ExportJobState

    data class Running(
        override val projectId: String,
        val projectName: String,
        val progressPermille: Int,
        val startedAtMs: Long,
        val estimate: ExportEstimate = ExportEstimate(),
        /** The movie is finished and the file is being checked; [progressPermille] is then the check's progress. */
        val verifying: Boolean = false,
    ) : ExportJobState

    /**
     * [note] is empty unless the movie was not exact (some frames repeated). [verification] is what the post-export check of
     * the file found; null only when no verifier is installed (tests).
     */
    data class Done(
        override val projectId: String,
        val projectName: String,
        val uri: String,
        val fileName: String,
        val note: String = "",
        val verification: VerificationOutcome? = null,
        /** Wall time of the export itself (start to the finished file) and of the check of that file, for the summary. */
        val exportMs: Long = 0,
        val verifyMs: Long = 0,
    ) : ExportJobState

    /** [error] is null only when the engine reported a failure without a reason. */
    data class Failed(override val projectId: String, val projectName: String, val error: ExportException?, val leftoverNote: String = "") : ExportJobState

    /** The user cancelled; the partial file is already removed. */
    data class Cancelled(override val projectId: String, val projectName: String) : ExportJobState

    val isRunning: Boolean get() = this is Running

    /** The project this export belongs to; null when there is none ([Idle]). */
    val projectId: String? get() = null
}

/** One export to run; the work of opening files and building the request is in [prepare], which runs off the main thread. */
class ExportJob(
    /** The project being exported: the notification and the project list use it to lead back to its editor. */
    val projectId: String,
    val projectName: String,
    val outputUri: String,
    /** Opens the descriptors and builds the request; throws [ExportException] (or [IllegalArgumentException]) on failure. */
    val prepare: () -> ExportRequest,
)

/** The text shown for a failed export, in the dialog and in the notification. */
internal fun describeExportFailure(error: ExportException?, hdr: Boolean): String = when (error?.code) {
    null -> "The export failed"
    ExportErrorCode.UNSUPPORTED_FORMAT ->
        "This device cannot encode with these settings: ${error.message}." + if (hdr) " Export as SDR instead." else ""
    ExportErrorCode.IO_ERROR -> "A file error stopped the export: ${error.message}"
    else -> "The export failed: ${error.message}"
}
