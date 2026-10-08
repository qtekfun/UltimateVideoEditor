package com.qtekfun.ultimatevideoeditor.engine.verify

/** Which check found a problem; [label] is what the user reads. */
enum class VerifyCheck(val label: String) {
    CONTAINER("file structure"),
    FRAME_COUNT("frame count"),
    TIMING("timestamps"),
    AUDIO("audio"),
    SAMPLE_DATA("stored data"),
    DECODE("decoding"),
    FRAME_ORDER("frame order"),
    FLAT_FRAME("blank frames"),
    PICTURE("picture"),
}

/**
 * One thing that is wrong with the file. [tailFrames] says how many frames at the very end are affected (0 when the
 * problem is not at the end or cannot be counted); the headline uses it: "the last N frames look damaged".
 */
data class Finding(val check: VerifyCheck, val message: String, val tailFrames: Long = 0)

/** What the exporter promised and the verifier measures the file against. */
data class VerifyExpectation(
    val totalFrames: Long,
    val fpsNum: Int,
    val fpsDen: Int,
    val hasAudio: Boolean,
    val hdr: Boolean,
) {
    init {
        require(totalFrames > 0 && fpsNum > 0 && fpsDen > 0) { "invalid expectation" }
    }

    val frameDurationUs: Double get() = fpsDen * 1_000_000.0 / fpsNum

    /** The frame a presentation time belongs to: the nearest frame, in integer arithmetic. */
    fun frameIndexOf(ptsUs: Long): Long {
        val denominator = 2L * fpsDen * 1_000_000L
        return Math.floorDiv(ptsUs * 2L * fpsNum + fpsDen * 1_000_000L, denominator)
    }

    /** Presentation time of [frame] in microseconds, rounded to the nearest. */
    fun ptsUsOf(frame: Long): Long = Math.floorDiv(frame * fpsDen * 1_000_000L * 2 + fpsNum, 2L * fpsNum)

    val durationUs: Long get() = ptsUsOf(totalFrames)
}

/** Facts about a verification that passed, for the detail line. */
data class VerifiedFacts(
    val frames: Long,
    val durationUs: Long,
    val probesCompared: Int,
    val framesDecoded: Int,
    val tookMs: Long,
    /** The largest distances seen over all compared frames (for calibration and the QA log); all zero when nothing was compared. */
    val worst: SignatureDistance = SignatureDistance(0.0, 0.0, 0.0, 0.0, 0.0),
)

/**
 * The result of looking at the finished file. It is never "fine" by default: when the check could not run it says
 * [CouldNotVerify], and a user who cancelled gets [Skipped].
 */
sealed interface VerificationOutcome {
    data class Verified(val facts: VerifiedFacts) : VerificationOutcome

    data class Warning(val findings: List<Finding>, val frames: Long, val durationUs: Long) : VerificationOutcome {
        /** The most frames at the end that a finding reports as damaged or missing, 0 when none says so. */
        val damagedTailFrames: Long get() = findings.maxOfOrNull { it.tailFrames } ?: 0L
    }

    /** The check itself could not run (no decoder, the file could not be opened, an error inside the verifier). */
    data class CouldNotVerify(val reason: String) : VerificationOutcome

    /** The user cancelled while it ran. */
    data object Skipped : VerificationOutcome

    /** True when the user should look at the file before using it. */
    val isProblem: Boolean get() = this is Warning

    /** True only for [Verified]: the one outcome that may say the file is fine. */
    val isVerified: Boolean get() = this is Verified
}

/** The words for an outcome, shared by the dialog, the notification and the QA result file. */
object VerificationText {
    fun headline(o: VerificationOutcome): String = when (o) {
        is VerificationOutcome.Verified -> "Checked: the video is complete (${o.facts.frames} frames, ${formatClock(o.facts.durationUs)})"
        is VerificationOutcome.Warning -> warningHeadline(o)
        is VerificationOutcome.CouldNotVerify -> "Could not check the file"
        VerificationOutcome.Skipped -> "Verification skipped (cancelled)"
    }

    private fun warningHeadline(o: VerificationOutcome.Warning): String {
        val tail = o.damagedTailFrames
        val which = o.findings.map { it.check }.distinct()
        return when {
            tail > 0 && which.any { it == VerifyCheck.FRAME_COUNT || it == VerifyCheck.CONTAINER } && which.none { it == VerifyCheck.PICTURE || it == VerifyCheck.DECODE } ->
                "WARNING: ${lastFrames(tail)} ${if (tail == 1L) "looks" else "look"} missing"
            tail > 0 -> "WARNING: ${lastFrames(tail)} ${if (tail == 1L) "looks" else "look"} damaged"
            else -> "WARNING: the exported file did not pass the check"
        }
    }

    /** A second line: what was checked, or which checks failed and why. */
    fun detail(o: VerificationOutcome): String = when (o) {
        is VerificationOutcome.Verified -> {
            val f = o.facts
            "${f.frames} frames, ${formatSeconds(f.durationUs)}"
        }
        is VerificationOutcome.Warning -> o.findings.joinToString(" ") { "${it.check.label}: ${it.message}." }
        is VerificationOutcome.CouldNotVerify -> "${o.reason}. The file was not checked; play its end before relying on it."
        VerificationOutcome.Skipped -> "The file was not checked."
    }

    private fun lastFrames(n: Long) = if (n == 1L) "the last frame" else "the last $n frames"

    /** mm:ss, or h:mm:ss from an hour. */
    fun formatClock(us: Long): String {
        val total = (us + 500_000) / 1_000_000
        return if (total >= 3600) "%d:%02d:%02d".format(total / 3600, total / 60 % 60, total % 60) else "%02d:%02d".format(total / 60, total % 60)
    }

    fun formatSeconds(us: Long): String {
        val totalSeconds = us / 1_000_000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        val tenths = (us % 1_000_000) / 100_000
        return if (minutes > 0) "%d:%02d.%d".format(minutes, seconds, tenths) else "%d.%d s".format(seconds, tenths)
    }

    /** One line a script can parse: `verification=verified|warning|could_not_verify|skipped` followed by facts. */
    fun resultLine(o: VerificationOutcome): String = when (o) {
        is VerificationOutcome.Verified -> "verification=verified frames=${o.facts.frames} probes=${o.facts.probesCompared} decoded=${o.facts.framesDecoded} took_ms=${o.facts.tookMs} worst_luma=%.4f worst_chroma=%.4f worst_bad=%.3f".format(
            java.util.Locale.ROOT, o.facts.worst.lumaMean, o.facts.worst.chromaMean, o.facts.worst.badCellShare,
        )
        is VerificationOutcome.Warning -> "verification=warning tail_frames=${o.damagedTailFrames} checks=${o.findings.joinToString(",") { it.check.name.lowercase() }}"
        is VerificationOutcome.CouldNotVerify -> "verification=could_not_verify reason=${o.reason.replace('\n', ' ')}"
        VerificationOutcome.Skipped -> "verification=skipped"
    }
}
