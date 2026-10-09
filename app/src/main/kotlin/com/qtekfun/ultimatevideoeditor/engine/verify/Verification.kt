package com.qtekfun.ultimatevideoeditor.engine.verify

import androidx.annotation.StringRes
import com.qtekfun.ultimatevideoeditor.R
import com.qtekfun.ultimatevideoeditor.ui.text.UiText

/** Which check found a problem; [labelRes] is the name the user reads. */
enum class VerifyCheck(@StringRes val labelRes: Int) {
    CONTAINER(R.string.verify_check_container),
    FRAME_COUNT(R.string.verify_check_frame_count),
    TIMING(R.string.verify_check_timing),
    AUDIO(R.string.verify_check_audio),
    SAMPLE_DATA(R.string.verify_check_sample_data),
    DECODE(R.string.verify_check_decode),
    FRAME_ORDER(R.string.verify_check_frame_order),
    FLAT_FRAME(R.string.verify_check_flat_frame),
    PICTURE(R.string.verify_check_picture),
}

/**
 * One thing that is wrong with the file. [message] is a technical remark of the verifier in English (its numbers and timestamps);
 * the words around it are translated (see [VerificationText]). [tailFrames] says how many frames at the very end are affected (0 when the
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
    data class CouldNotVerify(val reason: UiText) : VerificationOutcome

    /** The user cancelled while it ran. */
    data object Skipped : VerificationOutcome

    /** True when the user should look at the file before using it. */
    val isProblem: Boolean get() = this is Warning

    /** True only for [Verified]: the one outcome that may say the file is fine. */
    val isVerified: Boolean get() = this is Verified
}

/** The words for an outcome, shared by the dialog, the notification and the QA result file. */
object VerificationText {
    fun headline(o: VerificationOutcome): UiText = when (o) {
        is VerificationOutcome.Verified ->
            UiText.plural(R.plurals.verify_headline_complete, o.facts.frames.toInt(), o.facts.frames, formatClock(o.facts.durationUs))
        is VerificationOutcome.Warning -> warningHeadline(o)
        is VerificationOutcome.CouldNotVerify -> UiText.res(R.string.verify_headline_could_not)
        VerificationOutcome.Skipped -> UiText.res(R.string.verify_headline_skipped)
    }

    private fun warningHeadline(o: VerificationOutcome.Warning): UiText {
        val tail = o.damagedTailFrames
        val which = o.findings.map { it.check }.distinct()
        return when {
            tail > 0 && which.any { it == VerifyCheck.FRAME_COUNT || it == VerifyCheck.CONTAINER } && which.none { it == VerifyCheck.PICTURE || it == VerifyCheck.DECODE } ->
                UiText.plural(R.plurals.verify_headline_tail_missing, tail.toInt())
            tail > 0 -> UiText.plural(R.plurals.verify_headline_tail_damaged, tail.toInt())
            else -> UiText.res(R.string.verify_headline_failed)
        }
    }

    /** A second line: what was checked, or which checks failed and why. */
    fun detail(o: VerificationOutcome): UiText = when (o) {
        is VerificationOutcome.Verified -> {
            val f = o.facts
            UiText.plural(R.plurals.verify_detail_facts, f.frames.toInt(), f.frames, formatSeconds(f.durationUs))
        }
        is VerificationOutcome.Warning ->
            UiText.join(" ", o.findings.map { UiText.res(R.string.verify_detail_finding, UiText.res(it.check.labelRes), it.message) })
        is VerificationOutcome.CouldNotVerify -> UiText.res(R.string.verify_detail_could_not, o.reason)
        VerificationOutcome.Skipped -> UiText.res(R.string.verify_detail_skipped)
    }

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
        is VerificationOutcome.CouldNotVerify -> "verification=could_not_verify reason=${o.reason.toString().replace('\n', ' ')}"
        VerificationOutcome.Skipped -> "verification=skipped"
    }
}

