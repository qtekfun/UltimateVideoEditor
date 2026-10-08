package com.qtekfun.ultimatevideoeditor.ui.export

import com.qtekfun.ultimatevideoeditor.engine.verify.VerificationOutcome
import com.qtekfun.ultimatevideoeditor.engine.verify.VerificationText

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
data class ExportResultText(val severity: ResultSeverity, val headline: String, val detail: String, val timing: String = "")

fun exportResultText(note: String, verification: VerificationOutcome?, exportMs: Long = 0, verifyMs: Long = 0): ExportResultText {
    val timing = exportTimingLine(exportMs, verifyMs)
    val noteLine = if (note.isNotEmpty()) "Note: $note." else ""
    if (verification == null) return ExportResultText(ResultSeverity.UNVERIFIED, "", noteLine, timing)
    val severity = when (verification) {
        is VerificationOutcome.Verified -> ResultSeverity.OK
        is VerificationOutcome.Warning -> ResultSeverity.WARNING
        is VerificationOutcome.CouldNotVerify, VerificationOutcome.Skipped -> ResultSeverity.UNVERIFIED
    }
    val detail = listOf(VerificationText.detail(verification), noteLine).filter { it.isNotEmpty() }.joinToString(" ")
    return ExportResultText(severity, VerificationText.headline(verification), detail, timing)
}

/** True when the dialog should offer "Export again". */
val ExportResultText.offersExportAgain: Boolean get() = severity == ResultSeverity.WARNING

/** "Exported in 3:12, checked in 4 s": how long the movie took and how long the check of the file took; empty when unknown. */
fun exportTimingLine(exportMs: Long, verifyMs: Long): String {
    if (exportMs <= 0) return ""
    val checked = if (verifyMs > 0) ", checked in ${formatTook(verifyMs)}" else ""
    return "Exported in ${formatTook(exportMs)}$checked"
}

/** m:ss, h:mm:ss from one hour, "N s" under ten seconds' worth of minutes so a quick check reads "4 s". */
fun formatTook(ms: Long): String {
    val totalSeconds = (ms + 500) / 1000
    if (totalSeconds < 60) return "$totalSeconds s"
    val hours = totalSeconds / 3600
    val minutes = totalSeconds % 3600 / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}
