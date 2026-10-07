package com.ultimatevideo.uveditor.ui.export

import com.ultimatevideo.uveditor.data.interchange.BundleVerification

/**
 * A backup as the screens show it: the dialog, the bar at the bottom of the project list and the notification are all built
 * from this one value (which is built from [BundleJobState] only), so they always say the same.
 */
data class BundleView(
    val phase: Phase,
    val projectId: String,
    val projectName: String,
    val title: String,
    /** What is happening: "Packing media 3 of 12: IMG_0014.mov". Empty when finished. */
    val step: String = "",
    val stepShort: String = "",
    /** "1.8 of 7.4 GB, about 2 min left". Empty when finished. */
    val progressLine: String = "",
    /** "42 MB/s" while the speed is known. */
    val rateLine: String = "",
    val percent: Int = 0,
    val indeterminate: Boolean = false,
    /** The end state's headline: the saved line, or why it failed. */
    val message: String = "",
    /** The check of the saved file and what could not go in; empty when there is nothing to add. */
    val detail: String = "",
    val fileName: String = "",
    val uri: String? = null,
) {
    enum class Phase { PACKING, VERIFYING, SAVED, WARNING, FAILED, CANCELLED }

    val running: Boolean get() = phase == Phase.PACKING || phase == Phase.VERIFYING

    /** A damaged file is never offered for sharing. */
    val canShare: Boolean get() = uri != null && (phase == Phase.SAVED)

    /** The one-line state for the bar. */
    val barLine: String
        get() = when (phase) {
            Phase.PACKING -> listOf(progressLine, if (percent > 0) "$percent%" else "").filter { it.isNotEmpty() }.joinToString(" · ")
            Phase.VERIFYING -> "Checking the saved file…"
            else -> message
        }
}

/** The view for [state], or null when there is nothing to show (idle, or cancelled and cleaned up: it just goes away). */
fun bundleViewFor(state: BundleJobState): BundleView? = when (state) {
    BundleJobState.Idle -> null
    is BundleJobState.Cancelled ->
        if (state.leftoverNote.isEmpty()) null
        else BundleView(BundleView.Phase.CANCELLED, state.projectId, state.projectName, "Backup cancelled", message = state.leftoverNote.trim())
    is BundleJobState.Running -> {
        val p = state.progress
        if (state.verifying) {
            BundleView(
                BundleView.Phase.VERIFYING, state.projectId, state.projectName, "Checking the backup of ${state.projectName}",
                step = "Checking the saved file", stepShort = "Checking the saved file", percent = 100, indeterminate = true,
            )
        } else {
            BundleView(
                BundleView.Phase.PACKING, state.projectId, state.projectName, "Backing up ${state.projectName}",
                step = BundleJobText.step(p), stepShort = BundleJobText.shortStep(p), progressLine = BundleJobText.progressLine(p),
                rateLine = p.bytesPerSecond?.let { BundleJobText.rate(it) }.orEmpty(),
                percent = p.percent, indeterminate = p.totalBytes <= 0 || p.doneBytes <= 0,
            )
        }
    }
    is BundleJobState.Done -> {
        val warning = state.verification is BundleVerification.Warning
        val fileBytes = (state.verification as? BundleVerification.Verified)?.fileBytes ?: state.result.bytesWritten
        val saved = BundleJobText.savedLine(state.fileName, state.result, fileBytes, state.tookMs)
        BundleView(
            phase = if (warning) BundleView.Phase.WARNING else BundleView.Phase.SAVED,
            projectId = state.projectId,
            projectName = state.projectName,
            title = if (warning) "Backup saved, but check the file" else "Backup saved",
            message = saved,
            detail = listOf(
                BundleJobText.verificationHeadline(state.verification).takeIf { state.verification !is BundleVerification.Warning },
                BundleJobText.verificationDetail(state.verification),
                BundleJobText.skippedLine(state.result),
            ).filter { !it.isNullOrEmpty() }.joinToString(". "),
            fileName = state.fileName,
            uri = state.uri,
        )
    }
    is BundleJobState.Failed ->
        BundleView(BundleView.Phase.FAILED, state.projectId, state.projectName, "Backup failed", message = (state.message + state.leftoverNote).trim())
}

/** The notification for [state]: the same words, no Android type. Null when there is nothing to show. */
fun bundleNotificationFor(state: BundleJobState): ExportNotificationModel? {
    val view = bundleViewFor(state) ?: return null
    return when (view.phase) {
        BundleView.Phase.PACKING -> ExportNotificationModel(
            title = view.title,
            text = listOf(view.stepShort, view.progressLine).filter { it.isNotEmpty() }.joinToString(" · "),
            progressPercent = view.percent,
            indeterminate = view.indeterminate,
            ongoing = true,
            showCancel = true,
            projectId = view.projectId,
            bundle = true,
        )
        BundleView.Phase.VERIFYING -> ExportNotificationModel(
            title = view.title, text = view.step, progressPercent = null, indeterminate = true, ongoing = true, showCancel = true,
            projectId = view.projectId, bundle = true,
        )
        else -> ExportNotificationModel(
            title = view.title,
            text = listOf(view.message, if (view.phase == BundleView.Phase.WARNING) view.detail.substringBefore(". ") else "").filter { it.isNotEmpty() }.joinToString(" · "),
            progressPercent = null,
            indeterminate = false,
            ongoing = false,
            showCancel = false,
            projectId = view.projectId,
            bundle = true,
            shareUri = view.uri.takeIf { view.canShare },
        )
    }
}
