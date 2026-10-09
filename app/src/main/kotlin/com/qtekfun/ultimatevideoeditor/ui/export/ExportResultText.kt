package com.qtekfun.ultimatevideoeditor.ui.export

import com.qtekfun.ultimatevideoeditor.R
import com.qtekfun.ultimatevideoeditor.engine.verify.VerificationOutcome
import com.qtekfun.ultimatevideoeditor.engine.verify.VerificationText
import com.qtekfun.ultimatevideoeditor.ui.text.UiText
import com.qtekfun.ultimatevideoeditor.ui.text.isEmpty

/** How much the user should trust a finished export. */
enum class ResultSeverity {
    /** Verified. */
    OK,

    /** The check did not run or was cancelled: nothing is known to be wrong, nothing is confirmed. */
    UNVERIFIED,

    /** The check found a problem: the file is kept, but the user should look at it. */
    WARNING,
}

/**
 * The words about a finished export's file, shared by the dialog, the project list bar, the notification and the QA result file,
 * so they always say the same. [note] is the exporter's own remark (frames that had to be repeated); it is combined with the
 * verification, never replaced by it. [headline] is empty only when no verifier ran at all (tests).
 */
data class ExportResultText(val severity: ResultSeverity, val headline: UiText, val detail: UiText, val timing: UiText = UiText.Empty)

fun exportResultText(note: String, verification: VerificationOutcome?, exportMs: Long = 0, verifyMs: Long = 0): ExportResultText {
    val timing = exportTimingLine(exportMs, verifyMs)
    val noteLine = if (note.isNotEmpty()) UiText.res(R.string.export_note_line, note) else UiText.Empty
    if (verification == null) return ExportResultText(ResultSeverity.UNVERIFIED, UiText.Empty, noteLine, timing)
    val severity = when (verification) {
        is VerificationOutcome.Verified -> ResultSeverity.OK
        is VerificationOutcome.Warning -> ResultSeverity.WARNING
        is VerificationOutcome.CouldNotVerify, VerificationOutcome.Skipped -> ResultSeverity.UNVERIFIED
    }
    val detail = UiText.join(" ", VerificationText.detail(verification), noteLine)
    return ExportResultText(severity, VerificationText.headline(verification), detail, timing)
}

/** True when the dialog should offer "Export again". */
val ExportResultText.offersExportAgain: Boolean get() = severity == ResultSeverity.WARNING

/** "Exported in 3:12, checked in 4 s": how long the movie took and how long the check of the file took; empty when unknown. */
fun exportTimingLine(exportMs: Long, verifyMs: Long): UiText {
    if (exportMs <= 0) return UiText.Empty
    return if (verifyMs > 0) {
        UiText.res(R.string.export_timing_checked, formatTook(exportMs), formatTook(verifyMs))
    } else {
        UiText.res(R.string.export_timing, formatTook(exportMs))
    }
}

/** m:ss, h:mm:ss from one hour, "N s" under a minute so a quick check reads "4 s". Digits and unit symbol follow the language in use. */
fun formatTook(ms: Long): String {
    val totalSeconds = (ms + 500) / 1000
    if (totalSeconds < 60) return "%d s".format(java.util.Locale.getDefault(), totalSeconds)
    val hours = totalSeconds / 3600
    val minutes = totalSeconds % 3600 / 60
    val seconds = totalSeconds % 60
    val locale = java.util.Locale.getDefault()
    return if (hours > 0) "%d:%02d:%02d".format(locale, hours, minutes, seconds) else "%d:%02d".format(locale, minutes, seconds)
}
