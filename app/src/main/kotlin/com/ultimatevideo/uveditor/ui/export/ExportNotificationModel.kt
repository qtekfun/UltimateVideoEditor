package com.ultimatevideo.uveditor.ui.export

/**
 * What the export notification says, without any Android type. Progress is in whole percent so that two states which
 * look the same compare equal and the service does not repost an identical notification a thousand times.
 */
data class ExportNotificationModel(
    val title: String,
    val text: String,
    /** 0..100 while running with a known position; null when there is no bar. */
    val progressPercent: Int?,
    /** A bar with no position yet (the files are still being opened). */
    val indeterminate: Boolean,
    /** Running: cannot be swiped away and the service is in the foreground. */
    val ongoing: Boolean,
    val showCancel: Boolean,
    /** The project the export belongs to: tapping the notification opens its editor. Empty for the placeholder before the job is known. */
    val projectId: String = "",
)

/** The notification for [state], or null when there is nothing to show (idle, or the user cancelled). */
fun exportNotificationFor(state: ExportJobState): ExportNotificationModel? = when (state) {
    ExportJobState.Idle, is ExportJobState.Cancelled -> null
    is ExportJobState.Running -> {
        val percent = (state.progressPermille / 10).coerceIn(0, 100)
        val left = state.estimate.remainingMs?.takeIf { !state.estimate.stalled && state.progressPermille in 1..999 }
        ExportNotificationModel(
            title = if (state.verifying) "Verifying ${state.projectName}" else "Exporting ${state.projectName}",
            text = when {
                state.verifying -> "Checking the saved file · $percent%"
                left != null -> "$percent% · about ${formatDuration(left)} left"
                else -> "$percent%"
            },
            progressPercent = percent,
            indeterminate = state.progressPermille <= 0,
            ongoing = true,
            showCancel = true,
            projectId = state.projectId,
        )
    }
    is ExportJobState.Done -> {
        val result = exportResultText(state.note, state.verification)
        ExportNotificationModel(
            title = if (result.severity == ResultSeverity.WARNING) "Export saved: check the file" else "Export finished",
            text = listOf("${state.fileName} is saved", result.headline).filter { it.isNotEmpty() }.joinToString(" · "),
            progressPercent = null,
            indeterminate = false,
            ongoing = false,
            showCancel = false,
            projectId = state.projectId,
        )
    }
    is ExportJobState.Failed -> ExportNotificationModel(
        title = "Export failed",
        text = describeExportFailure(state.error, hdr = false),
        progressPercent = null,
        indeterminate = false,
        ongoing = false,
        showCancel = false,
        projectId = state.projectId,
    )
}
