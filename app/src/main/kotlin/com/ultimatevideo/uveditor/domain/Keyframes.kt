package com.ultimatevideo.uveditor.domain

/** How a value moves from one keyframe to the next. The mode of the earlier keyframe of a pair applies. */
enum class Interpolation(val code: Int) {
    LINEAR(0),

    /** Smoothstep: slow start, slow end. */
    EASE(1),

    /** Keeps the earlier value until the next keyframe, then jumps. */
    HOLD(2),

    /**
     * A cubic Bezier between the two keyframes, shaped by the earlier key's [Keyframe.out] handle and
     * the later key's [Keyframe.inn] handle (see [BezierHandle]). Evaluated in Kotlin only: the exporter
     * is given the curve already baked into one linear key per frame ([Keyframes.bakedForNative]).
     */
    BEZIER(3),
}

/**
 * A handle of a Bezier segment in the segment's own unit square: [x] is a fraction of the segment's length
 * (0..1) and [y] a fraction of the value change across it (it may leave 0..1 to overshoot). The earlier key
 * of a segment owns the control point `(x, y)` measured from its start; the later key owns the one measured
 * back from the segment's end, `(1 - x, 1 - y)`. This is the CSS `cubic-bezier(x1, y1, x2, y2)` convention.
 */
data class BezierHandle(val x: Double, val y: Double) {
    fun problem(): String? = when {
        !(x.isFinite() && y.isFinite()) -> "a Bezier handle must be finite"
        x !in 0.0..1.0 -> "a Bezier handle's x must stay between 0 and 1"
        y !in MIN_Y..MAX_Y -> "a Bezier handle's y must stay between $MIN_Y and $MAX_Y"
        else -> null
    }

    companion object {
        const val MIN_Y = -2.0
        const val MAX_Y = 3.0

        /** What a Bezier segment uses for a handle that was never set: an ease in-out. */
        val DEFAULT = BezierHandle(0.42, 0.0)
    }
}

/** The weight (0..1 progress of the value) of a Bezier segment at progress [t] of its length. */
object BezierCurve {
    private const val ITERATIONS = 48

    fun weight(t: Double, out: BezierHandle?, inn: BezierHandle?): Double {
        if (t <= 0.0) return 0.0
        if (t >= 1.0) return 1.0
        val a = out ?: BezierHandle.DEFAULT
        val b = inn ?: BezierHandle.DEFAULT
        val x1 = a.x
        val y1 = a.y
        val x2 = 1.0 - b.x
        val y2 = 1.0 - b.y
        // x(u) is monotonic for handles inside 0..1, so bisection finds the curve parameter u with x(u) = t.
        var lo = 0.0
        var hi = 1.0
        repeat(ITERATIONS) {
            val mid = (lo + hi) * 0.5
            if (cubic(mid, x1, x2) < t) lo = mid else hi = mid
        }
        return cubic((lo + hi) * 0.5, y1, y2)
    }

    private fun cubic(u: Double, p1: Double, p2: Double): Double {
        val v = 1.0 - u
        return 3.0 * v * v * u * p1 + 3.0 * v * u * u * p2 + u * u * u
    }
}

/**
 * A pose of a clip at [frame] frames after the clip's own start (not the project's), so keyframes
 * travel with the clip when it is moved. Opacity is part of [transform].
 */
data class Keyframe(
    val frame: Long,
    val transform: ClipTransform,
    val interpolation: Interpolation = Interpolation.LINEAR,
    /** Handle that shapes the segment leaving this key when [interpolation] is [Interpolation.BEZIER]; null is the default ease. */
    val out: BezierHandle? = null,
    /** Handle that shapes the segment arriving at this key, used when the previous key is [Interpolation.BEZIER]. */
    val inn: BezierHandle? = null,
)

/**
 * Pure keyframe maths on integer clip frames. Before the first keyframe the first pose holds, after
 * the last one the last pose holds. [evaluate] is mirrored by `core/keyframe_math.h`, which the
 * exporter uses; change both together (the host tests share vectors).
 */
object Keyframes {

    /** Reason [keys] cannot belong to a clip of [durationFrames] frames, or null when they can. */
    fun problem(keys: List<Keyframe>, durationFrames: Long): String? {
        var previous = -1L
        for (key in keys) {
            if (key.frame < 0 || key.frame >= durationFrames) return "keyframe at ${key.frame} is outside the clip"
            if (key.frame <= previous) return "keyframes must be in strictly increasing order"
            key.transform.problem()?.let { return "keyframe at ${key.frame}: $it" }
            key.out?.problem()?.let { return "keyframe at ${key.frame}: $it" }
            key.inn?.problem()?.let { return "keyframe at ${key.frame}: $it" }
            previous = key.frame
        }
        return null
    }

    /** The pose at [frame]; [base] when there are no keyframes. */
    fun evaluate(keys: List<Keyframe>, frame: Long, base: ClipTransform): ClipTransform {
        if (keys.isEmpty()) return base
        val first = keys.first()
        if (frame <= first.frame) return first.transform
        val last = keys.last()
        if (frame >= last.frame) return last.transform
        var index = 0
        while (keys[index + 1].frame <= frame) index++
        val from = keys[index]
        val to = keys[index + 1]
        if (from.frame == frame) return from.transform
        val t = (frame - from.frame).toDouble() / (to.frame - from.frame).toDouble()
        val w = weight(from.interpolation, t, from.out, to.inn)
        return ClipTransform(
            positionX = lerp(from.transform.positionX, to.transform.positionX, w),
            positionY = lerp(from.transform.positionY, to.transform.positionY, w),
            scaleX = lerp(from.transform.scaleX, to.transform.scaleX, w),
            scaleY = lerp(from.transform.scaleY, to.transform.scaleY, w),
            rotationDegrees = lerp(from.transform.rotationDegrees, to.transform.rotationDegrees, w),
            opacity = lerp(from.transform.opacity, to.transform.opacity, w),
        )
    }

    internal fun weight(mode: Interpolation, t: Double, out: BezierHandle? = null, inn: BezierHandle? = null): Double = when (mode) {
        Interpolation.LINEAR -> t
        Interpolation.EASE -> t * t * (3.0 - 2.0 * t)
        Interpolation.HOLD -> 0.0
        Interpolation.BEZIER -> BezierCurve.weight(t, out, inn)
    }

    private fun lerp(a: Double, b: Double, w: Double): Double = a + (b - a) * w

    /** [keys] with [key] added, or replacing the keyframe at the same frame. */
    fun set(keys: List<Keyframe>, key: Keyframe): List<Keyframe> =
        (keys.filter { it.frame != key.frame } + key).sortedBy { it.frame }

    fun at(keys: List<Keyframe>, frame: Long): Keyframe? = keys.firstOrNull { it.frame == frame }

    /** Nearest keyframe frame strictly before / after [frame], for the previous/next jump. */
    fun previousFrame(keys: List<Keyframe>, frame: Long): Long? = keys.lastOrNull { it.frame < frame }?.frame

    fun nextFrame(keys: List<Keyframe>, frame: Long): Long? = keys.firstOrNull { it.frame > frame }?.frame

    /**
     * Re-bases [keys] onto a clip made from the range [from, to) of the old clip's frames (`from`
     * may be negative when a clip grows at its start), so that frame 0 of the new clip is old frame
     * `from`. The animation over the kept range is unchanged: when keyframes outside the range
     * shaped it, a keyframe is added at the new start and/or end holding the pose there. A segment
     * that is cut in the middle keeps its mode over the remaining span, so only an ease changes
     * shape slightly; linear and hold segments are exact.
     */
    fun cropped(keys: List<Keyframe>, from: Long, to: Long, base: ClipTransform): List<Keyframe> {
        if (keys.isEmpty()) return keys
        val length = to - from
        val kept = keys.filter { it.frame in from until to }.map { it.copy(frame = it.frame - from) }.toMutableList()
        if (keys.any { it.frame < from } && kept.none { it.frame == 0L }) {
            val before = keys.last { it.frame < from }
            kept.add(0, Keyframe(0, evaluate(keys, from, base), before.interpolation, before.out))
        }
        val lastKept = kept.lastOrNull()?.frame ?: -1L
        if (keys.any { it.frame >= to } && lastKept < length - 1) {
            kept += Keyframe(length - 1, evaluate(keys, to - 1, base), inn = keys.first { it.frame >= to }.inn)
        }
        return kept
    }

    /**
     * [keys] with every Bezier segment replaced by one linear key per frame, which reproduces the curve
     * exactly at integer frames. The exporter evaluates keyframes natively with the three simple modes only,
     * so it is given this form; the preview evaluates [evaluate] directly.
     */
    fun bakedForNative(keys: List<Keyframe>, base: ClipTransform): List<Keyframe> {
        if (keys.none { it.interpolation == Interpolation.BEZIER }) return keys
        val result = ArrayList<Keyframe>(keys.size)
        for ((i, key) in keys.withIndex()) {
            val next = keys.getOrNull(i + 1)
            if (key.interpolation != Interpolation.BEZIER || next == null) {
                val mode = if (key.interpolation == Interpolation.BEZIER) Interpolation.LINEAR else key.interpolation
                result += key.copy(interpolation = mode, out = null, inn = null)
                continue
            }
            result += Keyframe(key.frame, key.transform, Interpolation.LINEAR)
            for (frame in key.frame + 1 until next.frame) result += Keyframe(frame, evaluate(keys, frame, base), Interpolation.LINEAR)
        }
        return result
    }

    /**
     * [keys] stretched for a clip that went from [oldLength] to [newLength] frames, so the animation
     * keeps its shape against the clip. Keys that land on the same frame keep the earlier one.
     */
    fun scaled(keys: List<Keyframe>, oldLength: Long, newLength: Long): List<Keyframe> {
        if (keys.isEmpty() || oldLength <= 0) return keys
        val result = ArrayList<Keyframe>(keys.size)
        for (key in keys) {
            val frame = (key.frame * newLength / oldLength).coerceIn(0, newLength - 1)
            if (result.isEmpty() || frame > result.last().frame) result += key.copy(frame = frame)
        }
        return result
    }

    /** Scales positions to a canvas of another size: x by the width ratio, y by the height ratio. */
    fun remapped(keys: List<Keyframe>, xRatio: Double, yRatio: Double): List<Keyframe> =
        keys.map { it.copy(transform = it.transform.remapped(xRatio, yRatio)) }
}

/** The same pose on a canvas whose width and height are [xRatio] and [yRatio] times the old ones. */
fun ClipTransform.remapped(xRatio: Double, yRatio: Double): ClipTransform =
    copy(positionX = positionX * xRatio, positionY = positionY * yRatio)
