package com.qtekfun.ultimatevideoeditor.ui.export

import com.qtekfun.ultimatevideoeditor.R
import com.qtekfun.ultimatevideoeditor.ui.text.UiText

/**
 * What the export notification says, without any Android type. Progress is in whole percent so that two states which
 * look the same compare equal and the service does not repost an identical notification a thousand times.
 */
data class ExportNotificationModel(
    val title: UiText,
    val text: UiText,
    /** 0..100 while running with a known position; null when there is no bar. */
    val progressPercent: Int?,
    /** A bar with no position yet (the files are still being opened). */
    val indeterminate: Boolean,
    /** Running: cannot be swiped away and the service is in the foreground. */
    val ongoing: Boolean,
    val showCancel: Boolean,
    /** The project the export belongs to: tapping the notification opens its editor. Empty for the placeholder before the job is known. */
    val projectId: String = "",
    /** A project backup (`.uvbundle`), not a movie: tapping it shows the project list, whose bar and dialog hold the backup. */
    val bundle: Boolean = false,
    /** A project import: tapping it shows the project list with the import's dialog. */
    val import: Boolean = false,
    /** A finished file the notification offers to share; null when there is none or it must not be shared. */
    val shareUri: String? = null,
)

/** The notification for [state], or null when there is nothing to show (idle, or the user cancelled). */
fun exportNotificationFor(state: ExportJobState): ExportNotificationModel? = when (state) {
    ExportJobState.Idle, is ExportJobState.Cancelled -> null
    is ExportJobState.Running -> {
        val percent = (state.progressPermille / 10).coerceIn(0, 100)
        val left = state.estimate.remainingMs?.takeIf { !state.estimate.stalled && state.progressPermille in 1..999 }
        ExportNotificationModel(
            title = UiText.res(if (state.verifying) R.string.notif_verifying_project else R.string.notif_exporting_project, state.projectName),
            text = when {
                state.verifying -> UiText.res(R.string.notif_checking_saved, percent)
                left != null -> UiText.res(R.string.percent_value_left, percent, formatDuration(left))
                else -> UiText.res(R.string.percent_value, percent)
            },
            progressPercent = percent,
            indeterminate = state.progressPermille <= 0,
            ongoing = true,
            showCancel = true,
            projectId = state.projectId,
        )
    }
    is ExportJobState.Done -> {
        val result = exportResultText(state.note, state.verification, state.exportMs, state.verifyMs)
        ExportNotificationModel(
            title = UiText.res(if (result.severity == ResultSeverity.WARNING) R.string.notif_export_saved_check else R.string.notif_export_finished),
            text = UiText.join(" · ", UiText.res(R.string.notif_file_saved, state.fileName), result.headline, result.timing),
            progressPercent = null,
            indeterminate = false,
            ongoing = false,
            showCancel = false,
            projectId = state.projectId,
        )
    }
    is ExportJobState.Failed -> ExportNotificationModel(
        title = UiText.res(R.string.notif_export_failed),
        text = describeExportFailure(state.error, hdr = false),
        progressPercent = null,
        indeterminate = false,
        ongoing = false,
        showCancel = false,
        projectId = state.projectId,
    )
}
