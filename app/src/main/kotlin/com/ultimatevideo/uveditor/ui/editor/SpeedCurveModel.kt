package com.ultimatevideo.uveditor.ui.editor

import com.ultimatevideo.uveditor.domain.SpeedKey
import com.ultimatevideo.uveditor.domain.SpeedRamps
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The geometry and edits of the speed-curve editor, free of Compose so they can be tested. A curve is the clip's list of
 * [SpeedKey]s: frames count clip frames, weights are relative speed in permille (1000 = the clip's average). The editor
 * draws weight on a log scale (doubling the speed is the same distance up everywhere), with 0 at the bottom and 1 at the
 * top of the plot, and frames from left (0) to right (the last frame).
 */
object SpeedCurveModel {
    private val LOG_MIN = ln(SpeedRamps.MIN_WEIGHT.toDouble())
    private val LOG_MAX = ln(SpeedRamps.MAX_WEIGHT.toDouble())

    /** Weights the editor snaps to, in permille: tens, so the numbers shown stay readable. */
    private const val WEIGHT_STEP = 10

    /** Vertical position (0 bottom, 1 top) of [weightPermille]. */
    fun weightToFraction(weightPermille: Int): Float =
        ((ln(weightPermille.coerceIn(SpeedRamps.MIN_WEIGHT, SpeedRamps.MAX_WEIGHT).toDouble()) - LOG_MIN) / (LOG_MAX - LOG_MIN)).toFloat()

    /** The weight at vertical position [fraction] (0 bottom, 1 top), snapped and kept inside the allowed range. */
    fun fractionToWeight(fraction: Float): Int {
        val raw = Math.exp(LOG_MIN + fraction.coerceIn(0f, 1f) * (LOG_MAX - LOG_MIN))
        val snapped = ((raw / WEIGHT_STEP).roundToInt() * WEIGHT_STEP)
        return snapped.coerceIn(SpeedRamps.MIN_WEIGHT, SpeedRamps.MAX_WEIGHT)
    }

    fun frameToFraction(frame: Long, durationFrames: Long): Float =
        if (durationFrames <= 1) 0f else (frame.toDouble() / (durationFrames - 1)).toFloat().coerceIn(0f, 1f)

    fun fractionToFrame(fraction: Float, durationFrames: Long): Long =
        (fraction.coerceIn(0f, 1f).toDouble() * max(0L, durationFrames - 1)).roundToLong().coerceIn(0, max(0L, durationFrames - 1))

    /** Index of the key nearest to the point ([x], [y]) (both as plot fractions), within [radius], or null. */
    fun nearestKey(keys: List<SpeedKey>, x: Float, y: Float, durationFrames: Long, aspect: Float, radius: Float): Int? {
        var best: Int? = null
        var bestDistance = Float.MAX_VALUE
        keys.forEachIndexed { i, key ->
            // The plot is wider than tall: weigh the horizontal distance by the aspect so the hit area is round on screen.
            val dx = (frameToFraction(key.frame, durationFrames) - x) * aspect
            val dy = weightToFraction(key.weightPermille) - y
            val d = kotlin.math.sqrt(dx * dx + dy * dy)
            if (d <= radius && d < bestDistance) {
                best = i
                bestDistance = d
            }
        }
        return best
    }

    /**
     * Moves key [index] to ([frame], [weightPermille]). The frame stays strictly between its neighbours' frames and inside
     * the clip; the weight stays inside the allowed range.
     */
    fun move(keys: List<SpeedKey>, index: Int, frame: Long, weightPermille: Int, durationFrames: Long): List<SpeedKey> {
        if (index !in keys.indices) return keys
        val low = if (index == 0) 0L else keys[index - 1].frame + 1
        val high = if (index == keys.lastIndex) durationFrames - 1 else keys[index + 1].frame - 1
        if (low > high) return keys
        val moved = keys[index].copy(
            frame = frame.coerceIn(low, high),
            weightPermille = weightPermille.coerceIn(SpeedRamps.MIN_WEIGHT, SpeedRamps.MAX_WEIGHT),
        )
        return keys.toMutableList().also { it[index] = moved }
    }

    /**
     * Adds a key at [frame] on the curve (it keeps the speed the curve has there, so the shape does not change until the
     * key is moved). An empty curve gets a flat one with a key at each end of the clip first. A key already at that
     * frame is returned as is.
     */
    fun add(keys: List<SpeedKey>, frame: Long, durationFrames: Long): Pair<List<SpeedKey>, Int> {
        if (durationFrames < 2) return keys to -1
        val base = keys.ifEmpty { listOf(SpeedKey(0, 1000), SpeedKey(durationFrames - 1, 1000)) }
        val clamped = frame.coerceIn(0, durationFrames - 1)
        val existing = base.indexOfFirst { it.frame == clamped }
        if (existing >= 0) return base to existing
        val weight = SpeedRamps.weightAt(base, clamped.toDouble()).roundToInt().coerceIn(SpeedRamps.MIN_WEIGHT, SpeedRamps.MAX_WEIGHT)
        val index = base.indexOfFirst { it.frame > clamped }.let { if (it < 0) base.size else it }
        // The new key starts a segment that continues the one it splits, so it inherits the easing of the key before it.
        val smooth = base.getOrNull(index - 1)?.smooth ?: false
        return base.toMutableList().also { it.add(index, SpeedKey(clamped, weight, smooth)) } to index
    }

    /** Removes key [index]; a curve left with fewer than two keys is no curve at all (constant speed). */
    fun remove(keys: List<SpeedKey>, index: Int): List<SpeedKey> {
        if (index !in keys.indices) return keys
        val rest = keys.toMutableList().also { it.removeAt(index) }
        return if (rest.size < 2) emptyList() else rest
    }

    /** Flips whether the segment after key [index] eases (smoothstep) or is a straight line. */
    fun toggleSmooth(keys: List<SpeedKey>, index: Int): List<SpeedKey> {
        if (index !in keys.indices) return keys
        return keys.toMutableList().also { it[index] = it[index].copy(smooth = !it[index].smooth) }
    }

    /** Plot points (x fraction, y fraction) of the curve for drawing: one per key and [stepsPerSegment] inside eased segments. */
    fun polyline(keys: List<SpeedKey>, durationFrames: Long, stepsPerSegment: Int = 12): List<Pair<Float, Float>> {
        if (keys.isEmpty()) return emptyList()
        val points = ArrayList<Pair<Float, Float>>()
        val firstX = frameToFraction(keys.first().frame, durationFrames)
        points += 0f to weightToFraction(keys.first().weightPermille)
        if (firstX > 0f) points += firstX to weightToFraction(keys.first().weightPermille)
        for (i in 0 until keys.lastIndex) {
            val from = keys[i]
            val to = keys[i + 1]
            val steps = if (from.smooth) stepsPerSegment else 1
            for (s in 1..steps) {
                val frame = from.frame + (to.frame - from.frame) * s.toDouble() / steps
                val weight = SpeedRamps.weightAt(keys, frame)
                points += frameToFraction(frame.roundToLong(), durationFrames) to weightToFraction(weight.roundToInt())
            }
        }
        val lastX = frameToFraction(keys.last().frame, durationFrames)
        if (lastX < 1f) points += 1f to weightToFraction(keys.last().weightPermille)
        return points
    }

    /** True when two curves are the same up to the editor's resolution (used to skip commits of drags that went nowhere). */
    fun same(a: List<SpeedKey>, b: List<SpeedKey>): Boolean =
        a.size == b.size && a.indices.all { a[it] == b[it] || (a[it].frame == b[it].frame && abs(a[it].weightPermille - b[it].weightPermille) < WEIGHT_STEP && a[it].smooth == b[it].smooth) }

    /** Reference grid lines: weights (permille) drawn across the plot. */
    val GRID_WEIGHTS = listOf(250, 500, 1000, 2000, 4000)
}
