package com.ultimatevideo.uveditor.ui.export

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
        val detail: String
            get() = when {
                verifying -> "Verifying the saved file · $percent%"
                remainingMs != null -> "$percent% · about ${formatDuration(remainingMs)} left"
                else -> "$percent%"
            }
    }

    data class Finished(
        override val projectId: String,
        override val projectName: String,
        val uri: String,
        val fileName: String,
        /** What the post-export check found (empty headline when none ran). */
        val result: ExportResultText = ExportResultText(ResultSeverity.UNVERIFIED, "", ""),
    ) : ExportBar

    data class Failed(
        override val projectId: String,
        override val projectName: String,
        val message: String,
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
        ExportBar.Finished(state.projectId, state.projectName, state.uri, state.fileName, exportResultText(state.note, state.verification))
    is ExportJobState.Failed ->
        ExportBar.Failed(state.projectId, state.projectName, describeExportFailure(state.error, hdr = false) + state.leftoverNote)
}

/** Whether the Export button of the editor of one project may open its dialog right now. */
sealed interface ExportAvailability {
    data object Available : ExportAvailability

    /** This project is the one exporting: the button shows its progress. */
    data object RunningHere : ExportAvailability

    /** Another project is exporting; only one export runs at a time. */
    data class BlockedBy(val projectName: String) : ExportAvailability {
        val message: String get() = "Another export is running: $projectName"
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
    const val ACTION_SHOW = "com.ultimatevideo.uveditor.export.SHOW"
    const val EXTRA_PROJECT_ID = "com.ultimatevideo.uveditor.export.PROJECT_ID"
}
