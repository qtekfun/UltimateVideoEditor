package com.ultimatevideo.uveditor.ui.export

import com.ultimatevideo.uveditor.engine.export.ExportErrorCode
import com.ultimatevideo.uveditor.engine.export.ExportException
import com.ultimatevideo.uveditor.engine.export.ExportRequest

/**
 * What the process-wide [ExportExecutor] is doing. The export dialog and the notification of the foreground service
 * are both views of this one value, so they always agree and either can be gone while the export goes on.
 */
sealed interface ExportJobState {
    /** Nothing running and nothing waiting to be acknowledged. */
    data object Idle : ExportJobState

    data class Running(
        val projectName: String,
        val progressPermille: Int,
        val startedAtMs: Long,
        val estimate: ExportEstimate = ExportEstimate(),
    ) : ExportJobState

    data class Done(val projectName: String, val uri: String, val fileName: String) : ExportJobState

    /** [error] is null only when the engine reported a failure without a reason. */
    data class Failed(val projectName: String, val error: ExportException?) : ExportJobState

    /** The user cancelled; the partial file is already removed. */
    data class Cancelled(val projectName: String) : ExportJobState

    val isRunning: Boolean get() = this is Running
}

/** One export to run; the work of opening files and building the request is in [prepare], which runs off the main thread. */
class ExportJob(
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
