package com.ultimatevideo.uveditor.domain.beat

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Loudness of a source over time: [values] hold 0..1 per bin and there are [binsPerSecond] bins in
 * each second. It is built from the waveform peak cache, so no audio is decoded again for analysis.
 */
class PeakEnvelope(val binsPerSecond: Double, val values: FloatArray) {
    init {
        require(binsPerSecond > 0.0 && binsPerSecond.isFinite()) { "binsPerSecond must be positive" }
    }

    val durationSeconds: Double get() = values.size / binsPerSecond
}

/** Beats found in a source: times are microseconds from its start. [bpm] is within [BeatDetector.MIN_BPM, BeatDetector.MAX_BPM). */
class BeatGrid(val bpm: Double, val confidence: Double, val beatsMicros: List<Long>)

/**
 * Tempo and beat estimation from a loudness envelope.
 *
 * Deliberately simple and deterministic: the envelope is resampled to 100 Hz, its positive
 * log-energy rise (an onset strength curve) is autocorrelated to find the beat period, a prior
 * around 120 BPM settles the half/double ambiguity, and beats are placed on the best phase of that
 * period and pulled to the nearest onset peak. It reads amplitude only (no spectrum), so it works
 * best on music with a clear pulse and returns null when it finds no convincing period. Because of
 * the half/double ambiguity a fast pulse may be reported at half tempo; the beats still sit on real beats.
 */
object BeatDetector {
    const val MIN_BPM = 60.0
    const val MAX_BPM = 180.0

    /** Needs at least this much audio to see a repeating pattern. */
    const val MIN_SECONDS = 4.0

    /** Autocorrelation peak over its mean below this means "no clear beat". */
    const val MIN_CONFIDENCE = 1.8

    const val ANALYSIS_RATE = 100.0
    private const val MEAN_WINDOW = 20
    private const val SNAP_FRACTION = 0.15
    private const val ONSET_FLOOR_FRACTION = 0.2
    private const val LOG_GAIN = 1000.0
    private const val PRIOR_BPM = 120.0
    private const val MICROS_PER_FRAME = 10_000L

    fun analyze(envelope: PeakEnvelope): BeatGrid? {
        val level = resample(envelope)
        if (level.size < MIN_SECONDS * ANALYSIS_RATE) return null
        val onset = onsetStrength(level)
        val peak = onset.max()
        if (peak <= 1e-3f) return null

        val minLag = (ANALYSIS_RATE * 60.0 / MAX_BPM).roundToInt()
        val maxLag = (ANALYSIS_RATE * 60.0 / MIN_BPM).roundToInt()
        if (onset.size <= maxLag * 3) return null
        val correlation = DoubleArray(maxLag + 2)
        val weighted = DoubleArray(maxLag + 2)
        for (lag in minLag..maxLag) {
            correlation[lag] = autocorrelation(onset, lag)
            val octaves = ln(ANALYSIS_RATE * 60.0 / lag / PRIOR_BPM) / ln(2.0)
            weighted[lag] = correlation[lag] * exp(-0.5 * octaves * octaves)
        }
        var bestLag = minLag
        for (lag in minLag..maxLag) if (weighted[lag] > weighted[bestLag]) bestLag = lag
        val meanCorrelation = (minLag..maxLag).sumOf { correlation[it] } / (maxLag - minLag + 1)
        if (meanCorrelation <= 0.0) return null
        val confidence = correlation[bestLag] / meanCorrelation
        if (confidence < MIN_CONFIDENCE) return null

        val period = refinePeriod(onset, bestLag)
        val bpm = ANALYSIS_RATE * 60.0 / period
        val beats = placeBeats(onset, period, peak)
        if (beats.size < 2) return null
        return BeatGrid(bpm, confidence, beats.map { it * MICROS_PER_FRAME })
    }

    /** Maximum of the source bins that fall into each 10 ms step. */
    private fun resample(envelope: PeakEnvelope): FloatArray {
        val perStep = envelope.binsPerSecond / ANALYSIS_RATE
        val steps = (envelope.values.size / perStep).toInt()
        val out = FloatArray(steps)
        for (step in 0 until steps) {
            val from = (step * perStep).toInt()
            val to = min(envelope.values.size, max(from + 1, ((step + 1) * perStep).toInt()))
            var m = 0f
            for (i in from until to) m = max(m, envelope.values[i].coerceIn(0f, 1f))
            out[step] = m
        }
        return out
    }

    /** Positive rise of log-compressed energy over its mean in the previous [MEAN_WINDOW] steps. */
    private fun onsetStrength(level: FloatArray): FloatArray {
        val logLevel = DoubleArray(level.size) { ln(1.0 + LOG_GAIN * level[it]) }
        val onset = FloatArray(level.size)
        var windowSum = 0.0
        for (i in level.indices) {
            val window = min(i, MEAN_WINDOW)
            val mean = if (window == 0) logLevel[i] else windowSum / window
            onset[i] = max(0.0, logLevel[i] - mean).toFloat()
            windowSum += logLevel[i]
            if (i >= MEAN_WINDOW) windowSum -= logLevel[i - MEAN_WINDOW]
        }
        return onset
    }

    private fun autocorrelation(onset: FloatArray, lag: Int): Double {
        var acc = 0.0
        val count = onset.size - lag
        for (i in 0 until count) acc += onset[i].toDouble() * onset[i + lag]
        return acc / count
    }

    /** Parabolic interpolation around the integer lag with the best autocorrelation. */
    private fun refinePeriod(onset: FloatArray, lag: Int): Double {
        if (lag <= 1) return lag.toDouble()
        val a = autocorrelation(onset, lag - 1)
        val b = autocorrelation(onset, lag)
        val c = autocorrelation(onset, lag + 1)
        val denom = a - 2 * b + c
        if (denom >= 0.0) return lag.toDouble()
        val shift = 0.5 * (a - c) / denom
        return lag + shift.coerceIn(-0.5, 0.5)
    }

    private fun placeBeats(onset: FloatArray, period: Double, peak: Float): List<Long> {
        // Best phase: the offset whose beat comb collects the most onset strength.
        var bestPhase = 0.0
        var bestScore = -1.0
        var phase = 0.0
        while (phase < period) {
            var score = 0.0
            var position = phase
            while (position < onset.size) {
                score += localMax(onset, position.roundToInt(), 1).second
                position += period
            }
            if (score > bestScore) {
                bestScore = score
                bestPhase = phase
            }
            phase += 1.0
        }
        val radius = max(1, (period * SNAP_FRACTION).roundToInt())
        val floor = peak * ONSET_FLOOR_FRACTION
        val beats = mutableListOf<Long>()
        var expected = bestPhase
        while (expected < onset.size) {
            val centre = expected.roundToInt()
            val (index, strength) = localMax(onset, centre, radius)
            val at = if (strength >= floor) index else centre
            if (at in onset.indices && (beats.isEmpty() || at > beats.last())) beats += at.toLong()
            // Follow the music: continue from where the beat really was, so slow drift does not accumulate.
            expected = (if (strength >= floor) index.toDouble() else expected) + period
        }
        return beats
    }

    private fun localMax(values: FloatArray, centre: Int, radius: Int): Pair<Int, Float> {
        var bestIndex = centre.coerceIn(0, values.size - 1)
        var best = -1f
        for (i in max(0, centre - radius)..min(values.size - 1, centre + radius)) {
            if (values[i] > best || (values[i] == best && abs(i - centre) < abs(bestIndex - centre))) {
                best = values[i]
                bestIndex = i
            }
        }
        return bestIndex to max(0f, best)
    }
}
