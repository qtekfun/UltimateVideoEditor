package com.qtekfun.ultimatevideoeditor.engine.verify

import java.io.IOException

/** Decodes frames of the finished output file. Implemented with MediaCodec on a device and with fakes in tests. */
interface FrameSource {
    /**
     * Decodes display frames [from]..[to] (inclusive): seeks to the key frame before [from], decodes forward, keeps the
     * frames in the range and stops after [to] or at the end of the file. Never throws for damaged data: it returns what it
     * got with [DecodedRange.error] set. [cancel] is polled between frames.
     */
    fun decode(from: Long, to: Long, cancel: () -> Boolean): DecodedRange
}

/** The frames the verifier asks the decoder for. */
class VerifyPlan(val head: LongRange, val tail: LongRange?) {
    val frameCount: Long get() = head.last - head.first + 1 + (tail?.let { it.last - it.first + 1 } ?: 0)
}

/**
 * Looks at the finished file in four steps: its index, the stored bytes of its first and last frames, the decoded first
 * frames and last seconds, and a few probe frames in between against the signatures taken while exporting. Pure Kotlin
 * over [ByteSource] and [FrameSource]; the app supplies a MediaCodec-based [FrameSource].
 */
class VerifyRunner(
    private val thresholds: VerifyThresholds = VerifyThresholds(),
    private val tailSeconds: Int = TAIL_SECONDS,
    private val clock: () -> Long = System::nanoTime,
) {
    /** [progress] is 0..1000. A null [frames] means no decoder is available: the result is then [VerificationOutcome.CouldNotVerify]. */
    fun run(
        source: ByteSource,
        frames: FrameSource?,
        exp: VerifyExpectation,
        signatures: List<FrameSignature>,
        cancel: () -> Boolean,
        progress: (Int) -> Unit = {},
    ): VerificationOutcome {
        val began = clock()
        val file = try {
            Mp4Reader.read(source)
        } catch (e: Mp4FormatException) {
            return VerificationOutcome.Warning(
                listOf(Finding(VerifyCheck.CONTAINER, e.message ?: "the file is not a readable MP4", tailFrames = 0)),
                exp.totalFrames,
                exp.durationUs,
            )
        } catch (e: IOException) {
            return VerificationOutcome.CouldNotVerify("the file could not be read back (${e.message})")
        }
        val findings = mutableListOf<Finding>()
        findings += ContainerCheck.check(file, exp)
        val tailFrames = tailFrameCount(exp)
        file.video?.let { findings += SampleCheck.checkVideo(source, it, headFrameCount(exp).toInt(), tailFrames.toInt()) }
        if (exp.hasAudio) file.audio?.let { findings += SampleCheck.checkAudio(source, it, TAIL_AUDIO_SAMPLES) }
        progress(PROGRESS_STRUCTURE)
        if (cancel()) return VerificationOutcome.Skipped
        if (file.video == null) return warning(findings, exp)
        if (frames == null) {
            return if (findings.isEmpty()) VerificationOutcome.CouldNotVerify("this device has no decoder to read the file back") else warning(findings, exp)
        }
        if (signatures.isEmpty() && findings.isEmpty()) {
            return VerificationOutcome.CouldNotVerify("the pictures were not recorded while exporting, so they cannot be compared")
        }

        val plan = plan(exp, signatures)
        val assessor = FrameAssessor(exp, signatures, thresholds)
        var compared = 0
        var decoded = 0
        var worst = SignatureDistance(0.0, 0.0, 0.0, 0.0, 0.0)
        var done = 0L
        val total = plan.frameCount.coerceAtLeast(1)
        fun decodeRange(from: Long, to: Long): Boolean {
            val range = frames.decode(from, to, cancel)
            if (cancel()) return false
            val assessment = assessor.assess(range)
            findings += assessment.findings
            compared += assessment.probesCompared
            decoded += assessment.framesChecked
            assessment.worstDistance?.let { d ->
                worst = SignatureDistance(
                    maxOf(worst.lumaMean, d.lumaMean), maxOf(worst.lumaWorstCell, d.lumaWorstCell),
                    maxOf(worst.chromaMean, d.chromaMean), maxOf(worst.chromaWorstCell, d.chromaWorstCell),
                    maxOf(worst.badCellShare, d.badCellShare),
                )
            }
            done += to - from + 1
            progress((PROGRESS_STRUCTURE + (PROGRESS_MAX - PROGRESS_STRUCTURE) * done / total).toInt().coerceAtMost(PROGRESS_MAX))
            return true
        }
        if (!decodeRange(plan.head.first, plan.head.last)) return VerificationOutcome.Skipped
        plan.tail?.let { if (!decodeRange(it.first, it.last)) return VerificationOutcome.Skipped }

        val merged = merge(findings)
        progress(PROGRESS_MAX)
        val tookMs = (clock() - began) / 1_000_000
        return if (merged.isEmpty()) {
            VerificationOutcome.Verified(VerifiedFacts(exp.totalFrames, exp.durationUs, compared, decoded, tookMs, worst))
        } else {
            warning(merged, exp)
        }
    }

    /** The first second of the movie. */
    fun headFrameCount(exp: VerifyExpectation): Long = (exp.fpsNum + exp.fpsDen - 1L) / exp.fpsDen

    fun tailFrameCount(exp: VerifyExpectation): Long = (tailSeconds.toLong() * exp.fpsNum + exp.fpsDen - 1) / exp.fpsDen

    fun plan(exp: VerifyExpectation, signatures: List<FrameSignature>): VerifyPlan {
        val last = exp.totalFrames - 1
        val head = 0L..minOf(last, headFrameCount(exp) - 1L)
        val tailFrom = maxOf(head.last + 1, exp.totalFrames - tailFrameCount(exp))
        val tail = if (tailFrom <= last) tailFrom..last else null
        return VerifyPlan(head, tail)
    }

    private fun warning(findings: List<Finding>, exp: VerifyExpectation) =
        VerificationOutcome.Warning(merge(findings), exp.totalFrames, exp.durationUs)

    /** One finding per kind of check: the first message, how many places, and the longest damaged tail. */
    private fun merge(findings: List<Finding>): List<Finding> =
        findings.groupBy { it.check }.map { (check, group) ->
            val message = group.first().message + if (group.size > 1) " (and ${group.size - 1} more ${if (group.size == 2) "place" else "places"})" else ""
            Finding(check, message, group.maxOf { it.tailFrames })
        }

    companion object {
        const val TAIL_SECONDS = 3
        private const val TAIL_AUDIO_SAMPLES = 200
        private const val PROGRESS_STRUCTURE = 100
        private const val PROGRESS_MAX = 1000
    }
}
