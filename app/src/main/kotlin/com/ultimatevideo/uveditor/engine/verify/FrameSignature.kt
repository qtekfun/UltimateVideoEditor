package com.ultimatevideo.uveditor.engine.verify

import kotlin.math.abs

/**
 * The signature format shared with `encode/frame_signature.h`: a [W] x [H] grid of mean luma, Cb and Cr of a frame in the
 * nominal Y'CbCr of the output (BT.709 for SDR, BT.2020 for HLG), each stored 0..[SCALE] (chroma is shifted by half).
 */
object SignatureFormat {
    const val W = 32
    const val H = 18
    const val CELLS = W * H
    const val SCALE = 65535
}

/** A frame's grid of means. Values are 0..[SignatureFormat.SCALE]; [y], [cb] and [cr] hold [SignatureFormat.CELLS] each. */
class FrameSignature(val frame: Long, val ptsUs: Long, val y: IntArray, val cb: IntArray, val cr: IntArray) {
    init {
        require(y.size == SignatureFormat.CELLS && cb.size == SignatureFormat.CELLS && cr.size == SignatureFormat.CELLS) { "wrong grid size" }
    }

    /** Spread (max - min over cells) of each plane, 0..1: how flat the picture is. */
    val lumaSpread: Double get() = spread(y)
    val chromaSpread: Double get() = maxOf(spread(cb), spread(cr))
    val meanLuma: Double get() = y.average() / SignatureFormat.SCALE

    private fun spread(plane: IntArray): Double = (plane.max() - plane.min()).toDouble() / SignatureFormat.SCALE

    companion object {
        /** Decodes what the native exporter hands over: `{frame, ptsUs}` per signature, then 3 x CELLS shorts (y, cb, cr) each. */
        fun parse(meta: LongArray, data: ShortArray): List<FrameSignature> {
            val per = 3 * SignatureFormat.CELLS
            val count = meta.size / 2
            if (meta.size % 2 != 0 || data.size != count * per) return emptyList()
            return List(count) { n ->
                val o = n * per
                fun plane(k: Int) = IntArray(SignatureFormat.CELLS) { data[o + k * SignatureFormat.CELLS + it].toInt() and 0xFFFF }
                FrameSignature(meta[n * 2], meta[n * 2 + 1], plane(0), plane(1), plane(2))
            }
        }
    }
}

/** How far two signatures are apart, in nominal units (1.0 = the whole range of a channel). */
data class SignatureDistance(
    val lumaMean: Double,
    val lumaWorstCell: Double,
    val chromaMean: Double,
    val chromaWorstCell: Double,
    /** Share of cells whose luma differs by more than [VerifyThresholds.cellLuma]. */
    val badCellShare: Double,
)

/**
 * The tolerances of the comparison between the signature taken from the encoder's input and the same frame decoded from
 * the file. Deliberately LOOSE (DECISIONS.md "Post-export verification"): real exports differ by well under a tenth of these values
 * (worst measured 0.007 mean luma on 4K HLG), so a good file never alarms; the check catches a wrong or blank picture, not a subtle one.
 */
data class VerifyThresholds(
    /** Mean absolute luma difference over all cells. */
    val lumaMean: Double = 0.10,
    /** Mean absolute chroma difference over all cells (both planes). */
    val chromaMean: Double = 0.10,
    /** A cell counts as bad above this luma difference. */
    val cellLuma: Double = 0.20,
    /** The picture fails when more than this share of cells is bad. */
    val badCellShare: Double = 0.40,
    /** A picture whose luma and chroma each vary less than this across the grid is flat (black, grey, one colour). */
    val flatSpread: Double = 0.012,
) {
    fun matches(d: SignatureDistance): Boolean =
        d.lumaMean <= lumaMean && d.chromaMean <= chromaMean && d.badCellShare <= badCellShare
}

object SignatureMath {
    fun distance(a: FrameSignature, b: FrameSignature, cellLuma: Double = VerifyThresholds().cellLuma): SignatureDistance {
        var lumaSum = 0.0
        var lumaWorst = 0.0
        var chromaSum = 0.0
        var chromaWorst = 0.0
        var bad = 0
        for (i in 0 until SignatureFormat.CELLS) {
            val dy = abs(a.y[i] - b.y[i]).toDouble() / SignatureFormat.SCALE
            val dc = (abs(a.cb[i] - b.cb[i]) + abs(a.cr[i] - b.cr[i])).toDouble() / (2 * SignatureFormat.SCALE)
            lumaSum += dy
            chromaSum += dc
            if (dy > lumaWorst) lumaWorst = dy
            if (dc > chromaWorst) chromaWorst = dc
            if (dy > cellLuma) bad++
        }
        val n = SignatureFormat.CELLS.toDouble()
        return SignatureDistance(lumaSum / n, lumaWorst, chromaSum / n, chromaWorst, bad / n)
    }

    fun isFlat(s: FrameSignature, t: VerifyThresholds = VerifyThresholds()): Boolean =
        s.lumaSpread <= t.flatSpread && s.chromaSpread <= t.flatSpread
}
