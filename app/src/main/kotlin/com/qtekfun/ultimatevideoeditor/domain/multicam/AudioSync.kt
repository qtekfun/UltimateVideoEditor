package com.qtekfun.ultimatevideoeditor.domain.multicam

import com.qtekfun.ultimatevideoeditor.domain.beat.PeakEnvelope
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where [other] sits relative to the reference recording, as found by [AudioSync].
 *
 * @property offsetFrames shared-time frame at which the other recording's first frame happens: the
 *   reference's frame `t` is the other's frame `t - offsetFrames` (see [MulticamAngle]).
 * @property ncc normalised cross-correlation at the best offset, -1..1; 1 is the same loudness shape.
 * @property confidence [ncc] minus the best rival peak elsewhere, so a clean single peak scores high and
 *   an ambiguous one (repetitive audio, silence) scores low; below [AudioSync.MIN_CONFIDENCE] do not trust it.
 */
class SyncResult(val offsetFrames: Long, val offsetSteps: Int, val ncc: Double, val confidence: Double) {
    val isConfident: Boolean get() = confidence >= AudioSync.MIN_CONFIDENCE
}

/**
 * Finds the time offset between two recordings of the same event from the loudness of their audio
 * (classical cross-correlation, nothing learned): both envelopes are brought to 100 Hz, log-compressed and
 * centred, correlated with an FFT over every offset at a coarse rate (1/8), the best offset is refined at the
 * full rate, and the result is converted to project frames with exact integer arithmetic. The envelope is
 * the one the waveform cache already holds, so no audio is decoded again.
 */
object AudioSync {
    const val RATE_HZ = 100
    const val MIN_CONFIDENCE = 0.15

    private const val COARSE = 8
    private const val LOG_GAIN = 30.0
    private const val MIN_SECONDS = 3
    private const val MAX_OVERLAP_SECONDS = 10
    private const val RIVAL_GUARD_SECONDS = 1.0

    /**
     * The offset of [other] against [reference] in project frames of `fpsNum / fpsDen`, or null when either
     * recording is too short or silent, or no offset keeps enough of them overlapping.
     */
    fun offsetOf(reference: PeakEnvelope, other: PeakEnvelope, fpsNum: Long, fpsDen: Long): SyncResult? {
        require(fpsNum > 0 && fpsDen > 0) { "frame rate must be positive" }
        val x = prepare(toRate(reference)) ?: return null
        val y = prepare(toRate(other)) ?: return null
        if (x.size < MIN_SECONDS * RATE_HZ || y.size < MIN_SECONDS * RATE_HZ) return null

        val minOverlap = min(MAX_OVERLAP_SECONDS * RATE_HZ, min(x.size, y.size) / 2).coerceAtLeast(MIN_SECONDS * RATE_HZ / 2)
        val xs = Prefix(x)
        val ys = Prefix(y)

        // Coarse: every offset at 1/COARSE of the rate through one FFT, scored by normalised correlation.
        val xc = decimate(x)
        val yc = decimate(y)
        val raw = crossCorrelate(xc, yc)
        val xcs = Prefix(xc)
        val ycs = Prefix(yc)
        val coarseMin = max(1, minOverlap / COARSE)
        var bestOffset = Int.MIN_VALUE
        var bestScore = Double.NEGATIVE_INFINITY
        val scores = HashMap<Int, Double>()
        for (o in -(yc.size - 1)..(xc.size - 1)) {
            val score = normalised(raw.at(o), xcs, ycs, o, coarseMin) ?: continue
            scores[o] = score
            if (score > bestScore) {
                bestScore = score
                bestOffset = o
            }
        }
        if (bestOffset == Int.MIN_VALUE || bestScore <= 0.0) return null

        // Fine: a direct search around the coarse winner at the full rate.
        var fineOffset = bestOffset * COARSE
        var fineScore = Double.NEGATIVE_INFINITY
        for (o in (bestOffset * COARSE - 2 * COARSE)..(bestOffset * COARSE + 2 * COARSE)) {
            val dot = directDot(x, y, o) ?: continue
            val score = normalised(dot, xs, ys, o, minOverlap) ?: continue
            if (score > fineScore) {
                fineScore = score
                fineOffset = o
            }
        }
        if (fineScore <= 0.0) return null

        val guard = max(1, (RIVAL_GUARD_SECONDS * RATE_HZ / COARSE).toInt())
        val rival = scores.entries.filter { abs(it.key - bestOffset) > guard }.maxOfOrNull { it.value }?.coerceAtLeast(0.0) ?: 0.0
        return SyncResult(
            offsetFrames = stepsToFrames(fineOffset, fpsNum, fpsDen),
            offsetSteps = fineOffset,
            ncc = fineScore,
            confidence = fineScore - rival,
        )
    }

    /** Whole project frames nearest to [steps] 100 Hz steps, rounding half up. */
    fun stepsToFrames(steps: Int, fpsNum: Long, fpsDen: Long): Long =
        Math.floorDiv(2L * steps * fpsNum + RATE_HZ * fpsDen, 2L * RATE_HZ * fpsDen)

    /** The envelope as `RATE_HZ` steps per second, each the mean of the bins it covers. */
    internal fun toRate(envelope: PeakEnvelope): FloatArray {
        val perStep = envelope.binsPerSecond / RATE_HZ
        val steps = (envelope.values.size / perStep).toInt()
        val out = FloatArray(steps)
        for (step in 0 until steps) {
            val from = (step * perStep).toInt()
            val to = min(envelope.values.size, max(from + 1, ((step + 1) * perStep).toInt()))
            var sum = 0f
            for (i in from until to) sum += abs(envelope.values[i]).coerceAtMost(1f)
            out[step] = sum / (to - from)
        }
        return out
    }

    /** Log-compressed (speech and clap bursts alike) and centred; null for silence. */
    private fun prepare(level: FloatArray): DoubleArray? {
        if (level.isEmpty()) return null
        val logged = DoubleArray(level.size) { ln(1.0 + LOG_GAIN * level[it]) }
        val mean = logged.average()
        for (i in logged.indices) logged[i] -= mean
        val energy = logged.sumOf { it * it }
        return if (energy < 1e-6) null else logged
    }

    private fun decimate(a: DoubleArray): DoubleArray {
        val n = a.size / COARSE
        return DoubleArray(n) { i ->
            var s = 0.0
            for (k in 0 until COARSE) s += a[i * COARSE + k]
            s / COARSE
        }
    }

    /** Running sums of squares so the energy of any overlap is O(1). */
    private class Prefix(a: DoubleArray) {
        val squares = DoubleArray(a.size + 1)

        init {
            for (i in a.indices) squares[i + 1] = squares[i] + a[i] * a[i]
        }

        val size: Int get() = squares.size - 1
        fun energy(from: Int, to: Int): Double = squares[to] - squares[from]
    }

    /** `Σ y[m] · x[m + o]` over the samples both have, or null when they do not overlap. */
    private fun directDot(x: DoubleArray, y: DoubleArray, o: Int): Double? {
        val from = max(0, -o)
        val to = min(y.size, x.size - o)
        if (to <= from) return null
        var acc = 0.0
        for (m in from until to) acc += y[m] * x[m + o]
        return acc
    }

    /** Correlation divided by the energies of the overlap; null when less than [minOverlap] samples overlap. */
    private fun normalised(dot: Double, x: Prefix, y: Prefix, o: Int, minOverlap: Int): Double? {
        val from = max(0, -o)
        val to = min(y.size, x.size - o)
        if (to - from < minOverlap) return null
        val e = sqrt(x.energy(from + o, to + o) * y.energy(from, to))
        return if (e < 1e-9) null else dot / e
    }

    private class Correlation(val values: DoubleArray, val xSize: Int, val ySize: Int) {
        /** `Σ y[m] · x[m + o]`. */
        fun at(o: Int): Double = values[if (o >= 0) o else values.size + o]
    }

    /** Circular cross-correlation through the FFT, padded so no offset wraps onto another. */
    private fun crossCorrelate(x: DoubleArray, y: DoubleArray): Correlation {
        var n = 1
        while (n < x.size + y.size) n = n shl 1
        val xr = DoubleArray(n)
        val xi = DoubleArray(n)
        val yr = DoubleArray(n)
        val yi = DoubleArray(n)
        x.copyInto(xr)
        y.copyInto(yr)
        fft(xr, xi, false)
        fft(yr, yi, false)
        // X · conj(Y)
        for (k in 0 until n) {
            val re = xr[k] * yr[k] + xi[k] * yi[k]
            val im = xi[k] * yr[k] - xr[k] * yi[k]
            xr[k] = re
            xi[k] = im
        }
        fft(xr, xi, true)
        return Correlation(xr, x.size, y.size)
    }

    /** In-place iterative radix-2 FFT (inverse scaled by 1/n); the size must be a power of two. */
    internal fun fft(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val n = re.size
        require(n > 0 && n and (n - 1) == 0) { "FFT size must be a power of two" }
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val angle = 2.0 * PI / len * if (inverse) 1 else -1
            val wr = cos(angle)
            val wi = sin(angle)
            var i = 0
            while (i < n) {
                var cr = 1.0
                var ci = 0.0
                for (k in 0 until len / 2) {
                    val a = i + k
                    val b = a + len / 2
                    val tr = re[b] * cr - im[b] * ci
                    val ti = re[b] * ci + im[b] * cr
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                    val nr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = nr
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) for (i in 0 until n) {
            re[i] /= n
            im[i] /= n
        }
    }
}
