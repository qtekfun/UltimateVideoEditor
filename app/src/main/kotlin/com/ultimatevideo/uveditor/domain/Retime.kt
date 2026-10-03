package com.ultimatevideo.uveditor.domain

import kotlin.math.floor

/**
 * One key of a speed ramp: the relative speed ([weightPermille], 1000 = the clip's average) at
 * [frame] clip frames after the clip's start. Between keys the weight is linear; before the first and
 * after the last it holds. Only the shape matters: the clip's average speed still comes from its
 * source range and length, so a ramp never changes where a clip starts or ends.
 */
data class SpeedKey(val frame: Long, val weightPermille: Int)

/** Pure maths on speed ramps, in integer clip frames. */
object SpeedRamps {
    const val MIN_WEIGHT = 50
    const val MAX_WEIGHT = 8000

    /** Reason [keys] cannot shape a clip of [durationFrames] frames, or null when they can. */
    fun problem(keys: List<SpeedKey>, durationFrames: Long): String? {
        var previous = -1L
        for (key in keys) {
            if (key.frame < 0 || key.frame >= durationFrames) return "speed key at ${key.frame} is outside the clip"
            if (key.frame <= previous) return "speed keys must be in strictly increasing order"
            if (key.weightPermille !in MIN_WEIGHT..MAX_WEIGHT) return "speed key at ${key.frame}: weight must be $MIN_WEIGHT to $MAX_WEIGHT"
            previous = key.frame
        }
        return null
    }

    /** The weight at [frame] (continuous), holding the first and last keys' weight outside them. */
    fun weightAt(keys: List<SpeedKey>, frame: Double): Double {
        val first = keys.first()
        if (frame <= first.frame) return first.weightPermille.toDouble()
        val last = keys.last()
        if (frame >= last.frame) return last.weightPermille.toDouble()
        var index = 0
        while (keys[index + 1].frame <= frame) index++
        val from = keys[index]
        val to = keys[index + 1]
        val t = (frame - from.frame) / (to.frame - from.frame).toDouble()
        return from.weightPermille + (to.weightPermille - from.weightPermille) * t
    }

    /**
     * Re-bases [keys] onto the range [from, to) of the old clip's frames (`from` may be negative and
     * `to` past the end when a clip grows), adding a key at each end that holds the weight there so
     * the shape over the kept range is unchanged.
     */
    fun cropped(keys: List<SpeedKey>, from: Long, to: Long): List<SpeedKey> {
        if (keys.isEmpty()) return keys
        val length = to - from
        if (length <= 0) return emptyList()
        val kept = keys.filter { it.frame in from until to }.map { it.copy(frame = it.frame - from) }.toMutableList()
        if (kept.none { it.frame == 0L } && (keys.any { it.frame < from } || from < 0)) {
            kept.add(0, SpeedKey(0, weightAt(keys, from.toDouble()).toInt().coerceIn(MIN_WEIGHT, MAX_WEIGHT)))
        }
        val lastKept = kept.lastOrNull()?.frame ?: -1L
        if (keys.any { it.frame >= to } && lastKept < length - 1) {
            kept += SpeedKey(length - 1, weightAt(keys, (to - 1).toDouble()).toInt().coerceIn(MIN_WEIGHT, MAX_WEIGHT))
        }
        return kept
    }

    /** [keys] rescaled for a clip that went from [oldLength] to [newLength] frames. */
    fun scaled(keys: List<SpeedKey>, oldLength: Long, newLength: Long): List<SpeedKey> {
        if (keys.isEmpty() || oldLength <= 0) return emptyList()
        val result = ArrayList<SpeedKey>(keys.size)
        for (key in keys) {
            val frame = (key.frame * newLength / oldLength).coerceIn(0, newLength - 1)
            if (result.isEmpty() || frame > result.last().frame) result += key.copy(frame = frame)
        }
        return result
    }

    /** Slow start, fast end: the speed rises smoothly over the clip. */
    fun easeIn(durationFrames: Long): List<SpeedKey> = shape(durationFrames, intArrayOf(400, 500, 800, 1300, 1600))

    /** Fast start, slow end. */
    fun easeOut(durationFrames: Long): List<SpeedKey> = shape(durationFrames, intArrayOf(1600, 1300, 800, 500, 400))

    /** Slow, fast, slow: the speed peaks in the middle of the clip. */
    fun bell(durationFrames: Long): List<SpeedKey> = shape(durationFrames, intArrayOf(400, 1000, 1600, 1000, 400))

    private fun shape(durationFrames: Long, weights: IntArray): List<SpeedKey> {
        if (durationFrames < 2) return emptyList()
        val last = weights.size - 1
        val result = ArrayList<SpeedKey>(weights.size)
        weights.forEachIndexed { i, w ->
            val frame = (durationFrames - 1) * i / last
            if (result.isEmpty() || frame > result.last().frame) result += SpeedKey(frame, w)
        }
        return result
    }
}

/**
 * How a clip's timeline frames map to frames of its source: the one definition the preview, the
 * exporter and the audio mixer all use, so retimed clips look and sound the same everywhere.
 *
 * The clip uses source range [sourceIn, sourceOut) and occupies [timelineFrames] frames. Frame
 * `t` of the clip (0 is its first) shows the source frame at continuous position `f(t)` into the
 * range, where `f(0) = 0` and `f(timelineFrames) = span`. Without a ramp `f` is linear, so the
 * speed is `span / timelineFrames` and `floor(f(t))` is exact integer arithmetic; with a ramp `f`
 * follows the ramp's weights. A [reverse] clip runs the range backwards. A range of a single frame
 * is a freeze frame: every timeline frame shows it. Positions outside the clip (a transition
 * extends clips past their ends) continue the same speed.
 */
class ClipRetime(
    val sourceIn: Long,
    val sourceOut: Long,
    val timelineFrames: Long,
    val reverse: Boolean,
    private val ramp: List<SpeedKey> = emptyList(),
) {
    val span: Long = sourceOut - sourceIn

    /** A single source frame held for the clip's whole length. */
    val isFreeze: Boolean get() = span == 1L

    /** True when frame `t` simply shows source frame `sourceIn + t`. */
    val isIdentity: Boolean get() = !reverse && ramp.isEmpty() && timelineFrames == span

    /** Source frames per timeline frame on average, as `span / timelineFrames`. */
    val averageSpeed: Double get() = span.toDouble() / timelineFrames.toDouble()

    // Integral of the ramp's weight from 0 to each key, and over the whole clip.
    private val cumulative: DoubleArray
    private val total: Double

    init {
        require(span >= 1 && timelineFrames >= 1) { "a retimed clip needs a source range and a length" }
        if (ramp.isEmpty()) {
            cumulative = DoubleArray(0)
            total = 0.0
        } else {
            cumulative = DoubleArray(ramp.size)
            cumulative[0] = ramp[0].weightPermille.toDouble() * ramp[0].frame
            for (i in 1 until ramp.size) {
                val length = (ramp[i].frame - ramp[i - 1].frame).toDouble()
                cumulative[i] = cumulative[i - 1] + (ramp[i - 1].weightPermille + ramp[i].weightPermille) * 0.5 * length
            }
            total = integral(timelineFrames.toDouble())
        }
    }

    private fun integral(t: Double): Double {
        val first = ramp.first()
        if (t <= first.frame) return first.weightPermille * t
        val last = ramp.last()
        if (t >= last.frame) return cumulative.last() + last.weightPermille * (t - last.frame)
        var index = 0
        while (ramp[index + 1].frame <= t) index++
        val from = ramp[index]
        val to = ramp[index + 1]
        val u = t - from.frame
        val length = (to.frame - from.frame).toDouble()
        return cumulative[index] + from.weightPermille * u + (to.weightPermille - from.weightPermille) * u * u / (2.0 * length)
    }

    /** Continuous position into the range, in source frames, at the (fractional) clip frame [t]. */
    fun position(t: Double): Double = when {
        ramp.isEmpty() -> t * span / timelineFrames
        t >= timelineFrames -> span + (t - timelineFrames) * edgeSpeedAfter()
        else -> span * (integral(t) / total)
    }

    // Beyond the clip's ends the speed holds that of the nearest ramp key.
    private fun edgeSpeedAfter(): Double = span * ramp.last().weightPermille / total

    /** How many whole source frames in (floor of [position]) a clip frame [t] is; negative before the clip. */
    fun offsetAt(t: Long): Long {
        if (isFreeze) return 0
        if (ramp.isEmpty()) return floorTimes(t, span, timelineFrames)
        return floor(position(t.toDouble()) + EPSILON).toLong()
    }

    /** The source frame shown at clip frame [t] (any integer: transitions reach past the ends). */
    fun sourceFrameAt(t: Long): Long {
        val offset = offsetAt(t)
        return if (reverse) sourceOut - 1 - offset else sourceIn + offset
    }

    override fun equals(other: Any?): Boolean = other is ClipRetime && sourceIn == other.sourceIn && sourceOut == other.sourceOut &&
        timelineFrames == other.timelineFrames && reverse == other.reverse && ramp == other.ramp

    override fun hashCode(): Int = listOf(sourceIn, sourceOut, timelineFrames, reverse, ramp).hashCode()

    /**
     * Knots `(clip frame, position in source frames)` of the continuous mapping, for the audio mixer
     * to interpolate between: the clip's ends and a knot at most [maxStepFrames] apart. Positions
     * count forward from the start of the range; a reversed clip reads them from its end.
     */
    fun knots(maxStepFrames: Long, fromFrame: Long = 0, toFrame: Long = timelineFrames): List<Pair<Long, Double>> {
        val result = ArrayList<Pair<Long, Double>>()
        var t = fromFrame
        while (t < toFrame) {
            result += t to position(t.toDouble())
            t += if (ramp.isEmpty()) toFrame - fromFrame else maxStepFrames
        }
        result += toFrame to position(toFrame.toDouble())
        return result
    }

    /**
     * [knots] for the audio mixer: the same mapping with the position made absolute (a reversed
     * clip's positions fall from its end) and the frame counted from [fromFrame], the first clip frame
     * the audio clip covers (a transition starts clips early).
     */
    fun sourceKnots(fromFrame: Long, toFrame: Long, maxStepFrames: Long): List<Pair<Long, Double>> =
        knots(maxStepFrames, fromFrame, toFrame).map { (t, position) ->
            (t - fromFrame) to (if (reverse) sourceOut - position else sourceIn + position)
        }

    companion object {
        private const val EPSILON = 1e-9

        /** `floor(a * b / c)` for `c > 0`, exact while the product fits a Long. */
        internal fun floorTimes(a: Long, b: Long, c: Long): Long {
            val product = try {
                Math.multiplyExact(a, b)
            } catch (_: ArithmeticException) {
                return floor(a.toDouble() * b.toDouble() / c.toDouble()).toLong()
            }
            return Math.floorDiv(product, c)
        }
    }
}

/** Speed limits for the constant-speed control, as a multiple of normal speed. */
object SpeedLimits {
    const val MIN_NUM = 1L
    const val MIN_DEN = 10L // 0.1x
    const val MAX = 8L // 8x

    /** Reason `num/den` is not an allowed speed, or null when it is. */
    fun problem(num: Long, den: Long): String? = when {
        num <= 0 || den <= 0 -> "speed must be positive"
        num * MIN_DEN < MIN_NUM * den -> "speed must be at least 0.1x"
        num > MAX * den -> "speed must be at most ${MAX}x"
        else -> null
    }
}

/** True when the clip is not a plain 1x forward span. */
val Clip.isRetimed: Boolean get() = retimedFrames != null || reverse || speedRamp.isNotEmpty()

/** The clip's frame mapping; see [ClipRetime]. Titles, photos and stickers are never retimed. */
val Clip.retime: ClipRetime get() = ClipRetime(sourceIn.value, sourceOut.value, durationFrames, reverse, speedRamp)

/** Length of the source range the clip plays, in source frames. */
val Clip.sourceSpan: Long get() = sourceOut - sourceIn

/** Average speed as `source frames / timeline frames`. */
val Clip.speed: Double get() = sourceSpan.toDouble() / durationFrames.toDouble()

/** A single source frame held for the clip's whole length (never true for titles). */
val Clip.isFreeze: Boolean get() = hasMedia && sourceSpan == 1L && durationFrames > 1

/** The source frame the clip shows at project frame [frame] (which must be near the clip). */
fun Clip.sourceFrameAtProjectFrame(frame: FrameIndex): Long = retime.sourceFrameAt(frame - timelineStart)

/**
 * The part of this clip over its clip frames [from, to), as a clip of length `to - from` starting
 * where this one starts (callers set id and start). `from` may be negative and `to` past the end:
 * the clip then grows, continuing its speed. The source range, length, ramp and keyframes follow the
 * same mapping every renderer uses, so cutting a retimed clip in two and putting the halves back
 * to back shows the same frames. Without any retiming this is plain `sourceIn + from .. sourceIn + to`.
 */
fun Clip.cropped(from: Long, to: Long): Clip {
    val keys = Keyframes.cropped(keyframes, from, to, transform)
    val length = to - from
    // Titles, photos and stickers have no source to read: their range is just their length.
    if (!hasMedia) return copy(sourceIn = FrameIndex.ZERO, sourceOut = FrameIndex(length), keyframes = keys, title = title?.shiftedBy(from))
    if (!isRetimed) return copy(sourceIn = sourceIn + from, sourceOut = sourceIn + to, keyframes = keys)
    val r = retime
    if (r.isFreeze) return copy(retimedFrames = length.takeIf { it != 1L }, speedRamp = emptyList(), keyframes = keys)
    val a = r.offsetAt(from)
    val b = r.offsetAt(to)
    var newIn = if (reverse) sourceOut.value - b else sourceIn.value + a
    var newOut = if (reverse) sourceOut.value - a else sourceIn.value + b
    if (newOut <= newIn) {
        // So slow that this stretch never reaches a new frame: it holds the frame it is on.
        if (reverse) newIn = newOut - 1 else newOut = newIn + 1
    }
    val span = newOut - newIn
    return copy(
        sourceIn = FrameIndex(newIn),
        sourceOut = FrameIndex(newOut),
        retimedFrames = length.takeIf { it != span },
        speedRamp = SpeedRamps.cropped(speedRamp, from, to),
        keyframes = keys,
    )
}
