package com.qtekfun.ultimatevideoeditor.engine.verify

/** A frame decoded from the output file: the display index its timestamp gives, and its signature in the same grid. */
class DecodedFrame(val index: Long, val signature: FrameSignature)

/**
 * What the decoder delivered for the frames [from]..[to] (inclusive, display indices) that were asked for, in the order it
 * delivered them. [error] is set when the decoder failed or stalled before the end.
 */
class DecodedRange(val from: Long, val to: Long, val frames: List<DecodedFrame>, val error: String? = null)

class Assessment(val findings: List<Finding>, val probesCompared: Int, val framesChecked: Int, val worstDistance: SignatureDistance?)

/**
 * Judges decoded frames against what the exporter promised: every frame there, in order, none blank unless the encoder's
 * input was, and each probed frame close to the signature taken from the encoder's input. Pure and tested with synthetic data.
 */
class FrameAssessor(
    private val exp: VerifyExpectation,
    signatures: List<FrameSignature>,
    private val thresholds: VerifyThresholds = VerifyThresholds(),
) {
    private val probes: List<FrameSignature> = signatures.sortedBy { it.frame }
    private val byFrame: Map<Long, FrameSignature> = probes.associateBy { it.frame }

    fun assess(range: DecodedRange): Assessment {
        val findings = mutableListOf<Finding>()
        val last = exp.totalFrames - 1
        val reachesEnd = range.to >= last

        // Order and completeness.
        val seen = HashSet<Long>()
        var outOfOrder = 0
        var highest = range.from - 1
        for (f in range.frames) {
            if (f.index < range.from || f.index > range.to) continue
            if (f.index <= highest || !seen.add(f.index)) outOfOrder++ else highest = f.index
        }
        val lastIndex = highest
        val missing = (range.to - range.from + 1) - seen.size
        val missingAtEnd = range.to - lastIndex
        if (range.error != null) {
            val firstLost = lastIndex + 1
            findings += Finding(
                VerifyCheck.DECODE,
                "the decoder stopped at frame ${firstLost + 1} of ${exp.totalFrames}: ${range.error}",
                tailFrames = if (reachesEnd) exp.totalFrames - firstLost else 0,
            )
        } else if (missing > 0) {
            findings += Finding(
                VerifyCheck.FRAME_COUNT,
                "$missing ${plural(missing, "frame")} could not be decoded between frames ${range.from + 1} and ${range.to + 1}",
                tailFrames = if (reachesEnd && missingAtEnd > 0) missingAtEnd else 0,
            )
        }
        if (outOfOrder > 0) findings += Finding(VerifyCheck.FRAME_ORDER, "$outOfOrder frames came out of order or twice")

        // Blank frames, and the comparison with what was encoded.
        var compared = 0
        var worst: SignatureDistance? = null
        val badFrames = mutableListOf<Long>()
        val comparedFrames = mutableListOf<Long>()
        val blankFrames = mutableListOf<Long>()
        for (f in range.frames) {
            val probe = byFrame[f.index]
            if (probe != null) {
                compared++
                comparedFrames += f.index
                val d = SignatureMath.distance(f.signature, probe, thresholds.cellLuma)
                val w = worst
                worst = if (w == null) d else SignatureDistance(
                    maxOf(w.lumaMean, d.lumaMean), maxOf(w.lumaWorstCell, d.lumaWorstCell),
                    maxOf(w.chromaMean, d.chromaMean), maxOf(w.chromaWorstCell, d.chromaWorstCell), maxOf(w.badCellShare, d.badCellShare),
                )
                if (!thresholds.matches(d)) badFrames += f.index
            }
        }
        // Flat frames matter only as a blank ending: a black cut in the middle is the user's own editing, so it is never reported;
        // the run of flat frames that reaches the last frame is, unless the encoder's own last picture was flat (a fade to black).
        val ordered = range.frames.sortedBy { it.index }
        if (reachesEnd && ordered.isNotEmpty() && ordered.last().index == exp.totalFrames - 1 && !blankIsExpected(exp.totalFrames - 1)) {
            for (f in ordered.reversed()) {
                if (!SignatureMath.isFlat(f.signature, thresholds)) break
                blankFrames.add(0, f.index)
            }
        }
        if (badFrames.isNotEmpty()) {
            findings += Finding(
                VerifyCheck.PICTURE,
                "${badFrames.size} checked ${plural(badFrames.size.toLong(), "frame")} (first: frame ${badFrames.first() + 1}) differ from what was encoded",
                tailFrames = tailRun(comparedFrames, badFrames, reachesEnd),
            )
        }
        if (blankFrames.isNotEmpty()) {
            findings += Finding(
                VerifyCheck.FLAT_FRAME,
                "${blankFrames.size} ${plural(blankFrames.size.toLong(), "frame")} are blank or one flat colour (first: frame ${blankFrames.first() + 1})",
                tailFrames = tailRun(range.frames.map { it.index }.sorted(), blankFrames, reachesEnd),
            )
        }
        return Assessment(findings, compared, range.frames.size, worst)
    }

    /**
     * How many frames at the end are bad: [checked] are the frames the criterion could judge (sorted), [bad] those that
     * failed. When the last frame of the file failed, the run of failures back from it counts, from its first bad frame.
     */
    private fun tailRun(checked: List<Long>, bad: List<Long>, reachesEnd: Boolean): Long {
        val last = exp.totalFrames - 1
        if (!reachesEnd || checked.isEmpty() || checked.last() != last) return 0
        val badSet = bad.toHashSet()
        if (last !in badSet) return 0
        var first = last
        for (i in checked.indices.reversed()) {
            if (checked[i] in badSet) first = checked[i] else break
        }
        return last - first + 1
    }

    /** A blank frame is fine when the probes of the encoder's input around it were blank too (a fade to black, a title card). */
    private fun blankIsExpected(frame: Long): Boolean {
        if (probes.isEmpty()) return true // nothing to compare with: do not guess
        var before: FrameSignature? = null
        var after: FrameSignature? = null
        for (p in probes) {
            if (p.frame <= frame) before = p
            if (p.frame >= frame && after == null) after = p
        }
        val neighbours = listOfNotNull(before, after)
        return neighbours.any { SignatureMath.isFlat(it, thresholds) }
    }

    private fun plural(n: Long, word: String) = if (n == 1L) word else "${word}s"
}
