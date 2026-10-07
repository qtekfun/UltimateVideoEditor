package com.ultimatevideo.uveditor.ui.export

import com.ultimatevideo.uveditor.engine.verify.VerificationOutcome
import com.ultimatevideo.uveditor.engine.verify.VerificationText

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
data class ExportResultText(val severity: ResultSeverity, val headline: String, val detail: String)

fun exportResultText(note: String, verification: VerificationOutcome?): ExportResultText {
    val noteLine = if (note.isNotEmpty()) "Note: $note." else ""
    if (verification == null) return ExportResultText(ResultSeverity.UNVERIFIED, "", noteLine)
    val severity = when (verification) {
        is VerificationOutcome.Verified -> ResultSeverity.OK
        is VerificationOutcome.Warning -> ResultSeverity.WARNING
        is VerificationOutcome.CouldNotVerify, VerificationOutcome.Skipped -> ResultSeverity.UNVERIFIED
    }
    val detail = listOf(VerificationText.detail(verification), noteLine).filter { it.isNotEmpty() }.joinToString(" ")
    return ExportResultText(severity, VerificationText.headline(verification), detail)
}

/** True when the dialog should offer "Export again". */
val ExportResultText.offersExportAgain: Boolean get() = severity == ResultSeverity.WARNING
