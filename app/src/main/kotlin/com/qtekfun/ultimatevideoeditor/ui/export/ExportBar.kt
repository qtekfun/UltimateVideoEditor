package com.qtekfun.ultimatevideoeditor.ui.export

import com.qtekfun.ultimatevideoeditor.R
import com.qtekfun.ultimatevideoeditor.ui.text.UiText
import kotlinx.coroutines.flow.StateFlow

/** What the project list and the editors need from the process-wide export; [ExportExecutor] is the real one. */
interface ExportJobHost {
    val state: StateFlow<ExportJobState>
    fun cancel()

    /** Forgets a finished, failed or cancelled export (see [ExportExecutor.acknowledge]). */
    fun acknowledge(onlyProject: String? = null)
}

/**
 * The bar at the bottom of the project list: the export that is running, or the one that just ended and has not been
 * dismissed. Built from [ExportJobState] only, so it needs no Android type and is unit tested.
 */
sealed interface ExportBar {
    val projectId: String
    val projectName: String

    data class Running(
        override val projectId: String,
        override val projectName: String,
        val percent: Int,
        /** Time left, or null while it is not known (just started, or the encoder is waiting). */
        val remainingMs: Long?,
        /** The movie is finished and its file is being checked. */
        val verifying: Boolean = false,
    ) : ExportBar {
        val detail: UiText
            get() = when {
                verifying -> UiText.res(R.string.bar_verifying_saved, percent)
                remainingMs != null -> UiText.res(R.string.percent_value_left, percent, formatDuration(remainingMs))
                else -> UiText.res(R.string.percent_value, percent)
            }
    }

    data class Finished(
        override val projectId: String,
        override val projectName: String,
        val uri: String,
        val fileName: String,
        /** What the post-export check found (empty headline when none ran). */
        val result: ExportResultText = ExportResultText(ResultSeverity.UNVERIFIED, UiText.Empty, UiText.Empty),
    ) : ExportBar

    data class Failed(
        override val projectId: String,
        override val projectName: String,
        val message: UiText,
    ) : ExportBar
}

/** The bar for [state], or null when there is nothing to show (idle, or the user cancelled: the bar just goes away). */
fun exportBarFor(state: ExportJobState): ExportBar? = when (state) {
    ExportJobState.Idle, is ExportJobState.Cancelled -> null
    is ExportJobState.Running -> ExportBar.Running(
        state.projectId,
        state.projectName,
        percent = (state.progressPermille / 10).coerceIn(0, 100),
        remainingMs = state.estimate.remainingMs?.takeIf { !state.verifying && !state.estimate.stalled && state.progressPermille in 1..999 },
        verifying = state.verifying,
    )
    is ExportJobState.Done ->
        ExportBar.Finished(state.projectId, state.projectName, state.uri, state.fileName, exportResultText(state.note, state.verification, state.exportMs, state.verifyMs))
    is ExportJobState.Failed ->
        ExportBar.Failed(state.projectId, state.projectName, UiText.join("", describeExportFailure(state.error, hdr = false), state.leftoverNote))
}

/** Whether the Export button of the editor of one project may open its dialog right now. */
sealed interface ExportAvailability {
    data object Available : ExportAvailability

    /** This project is the one exporting: the button shows its progress. */
    data object RunningHere : ExportAvailability

    /** Another project is exporting; only one export runs at a time. */
    data class BlockedBy(val projectName: String) : ExportAvailability {
        val message: UiText get() = UiText.res(R.string.export_blocked_by, projectName)
    }
}

fun exportAvailability(state: ExportJobState, projectId: String): ExportAvailability = when {
    !state.isRunning -> ExportAvailability.Available
    state.projectId == projectId -> ExportAvailability.RunningHere
    else -> ExportAvailability.BlockedBy((state as ExportJobState.Running).projectName)
}

/** Where tapping an export notification or the bar leads. */
sealed interface ExportDestination {
    /** The project list (which shows the export bar). */
    data object ProjectList : ExportDestination

    /** The editor of the project, with the export dialog open. */
    data class Editor(val projectId: String) : ExportDestination
}

/**
 * The editor of [projectId] when it still exists and the executor still holds an export of it (running, finished or
 * failed: the dialog has something to show); otherwise the project list. A stale id (the project was deleted, or the
 * process was restarted since the notification was posted and the export is gone) never opens a dialog with nothing in it.
 */
fun exportDestination(projectId: String?, projectExists: Boolean, state: ExportJobState): ExportDestination =
    if (projectId != null && projectExists && state.projectId == projectId && state !is ExportJobState.Cancelled) {
        ExportDestination.Editor(projectId)
    } else {
        ExportDestination.ProjectList
    }

/** The extras of the intent behind an export notification. */
object ExportLaunch {
    const val ACTION_SHOW = "com.qtekfun.ultimatevideoeditor.export.SHOW"
    const val EXTRA_PROJECT_ID = "com.qtekfun.ultimatevideoeditor.export.PROJECT_ID"

    /** Set when the notification belongs to a project backup: the tap shows the project list with the backup dialog, not an editor. */
    const val EXTRA_BUNDLE = "com.qtekfun.ultimatevideoeditor.export.BUNDLE"

    /** Set when the notification belongs to a project import: the tap shows the import's dialog over whatever screen is open. */
    const val EXTRA_IMPORT = "com.qtekfun.ultimatevideoeditor.export.IMPORT"
}
