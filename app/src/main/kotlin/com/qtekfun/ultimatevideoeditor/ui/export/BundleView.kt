package com.qtekfun.ultimatevideoeditor.ui.export

import com.qtekfun.ultimatevideoeditor.ui.text.isEmpty
import com.qtekfun.ultimatevideoeditor.R
import com.qtekfun.ultimatevideoeditor.ui.text.UiText
import com.qtekfun.ultimatevideoeditor.data.interchange.BundleVerification

/**
 * A backup as the screens show it: the dialog, the bar at the bottom of the project list and the notification are all built
 * from this one value (which is built from [BundleJobState] only), so they always say the same.
 */
data class BundleView(
    val phase: Phase,
    val projectId: String,
    val projectName: String,
    val title: UiText,
    /** What is happening: "Packing media 3 of 12: IMG_0014.mov". Empty when finished. */
    val step: UiText = UiText.Empty,
    val stepShort: UiText = UiText.Empty,
    /** "1.8 of 7.4 GB, about 2 min left". Empty when finished. */
    val progressLine: UiText = UiText.Empty,
    /** "42 MB/s" while the speed is known. */
    val rateLine: String = "",
    val percent: Int = 0,
    val indeterminate: Boolean = false,
    /** The end state's headline: the saved line, or why it failed. */
    val message: UiText = UiText.Empty,
    /** The check of the saved file and what could not go in; empty when there is nothing to add. */
    val detail: UiText = UiText.Empty,
    val fileName: String = "",
    val uri: String? = null,
    /** The first problem of a warning, short enough for the notification. */
    val warningLead: UiText = UiText.Empty,
) {
    enum class Phase { PACKING, VERIFYING, SAVED, WARNING, FAILED, CANCELLED }

    val running: Boolean get() = phase == Phase.PACKING || phase == Phase.VERIFYING

    /** A damaged file is never offered for sharing. */
    val canShare: Boolean get() = uri != null && (phase == Phase.SAVED)

    /** The one-line state for the bar. */
    val barLine: UiText
        get() = when (phase) {
            Phase.PACKING -> UiText.join(" · ", progressLine, if (percent > 0) UiText.res(R.string.percent_value, percent) else UiText.Empty)
            Phase.VERIFYING -> UiText.res(R.string.bundle_bar_checking)
            else -> message
        }
}

/** The view for [state], or null when there is nothing to show (idle, or cancelled and cleaned up: it just goes away). */
fun bundleViewFor(state: BundleJobState): BundleView? = when (state) {
    BundleJobState.Idle -> null
    is BundleJobState.Cancelled ->
        if (state.leftoverNote.isEmpty()) null
        else BundleView(BundleView.Phase.CANCELLED, state.projectId, state.projectName, UiText.res(R.string.bundle_cancelled), message = state.leftoverNote)
    is BundleJobState.Running -> {
        val p = state.progress
        if (state.verifying) {
            BundleView(
                BundleView.Phase.VERIFYING, state.projectId, state.projectName, UiText.res(R.string.bundle_checking_title, state.projectName),
                step = UiText.res(R.string.bundle_checking_step), stepShort = UiText.res(R.string.bundle_checking_step), percent = 100, indeterminate = true,
            )
        } else {
            BundleView(
                BundleView.Phase.PACKING, state.projectId, state.projectName, UiText.res(R.string.bundle_backing_up_title, state.projectName),
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
            title = UiText.res(if (warning) R.string.bundle_saved_check_title else R.string.bundle_saved_title),
            message = saved,
            detail = UiText.join(
                ". ",
                if (state.verification !is BundleVerification.Warning) BundleJobText.verificationHeadline(state.verification) else UiText.Empty,
                BundleJobText.verificationDetail(state.verification),
                BundleJobText.skippedLine(state.result),
            ),
            fileName = state.fileName,
            uri = state.uri,
            warningLead = (state.verification as? BundleVerification.Warning)?.problems?.firstOrNull()?.let { UiText.Raw(it.substringBefore(". ")) } ?: UiText.Empty,
        )
    }
    is BundleJobState.Failed ->
        BundleView(BundleView.Phase.FAILED, state.projectId, state.projectName, UiText.res(R.string.bundle_failed_title), message = UiText.join(" ", state.message, state.leftoverNote))
}

/** The notification for [state]: the same words, no Android type. Null when there is nothing to show. */
fun bundleNotificationFor(state: BundleJobState): ExportNotificationModel? {
    val view = bundleViewFor(state) ?: return null
    return when (view.phase) {
        BundleView.Phase.PACKING -> ExportNotificationModel(
            title = view.title,
            text = UiText.join(" · ", view.stepShort, view.progressLine),
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
            text = UiText.join(" · ", view.message, if (view.phase == BundleView.Phase.WARNING) view.warningLead else UiText.Empty),
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
