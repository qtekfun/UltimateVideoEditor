package com.ultimatevideo.uveditor.domain

/** How a value moves from one keyframe to the next. The mode of the earlier keyframe of a pair applies. */
enum class Interpolation(val code: Int) {
    LINEAR(0),

    /** Smoothstep: slow start, slow end. */
    EASE(1),

    /** Keeps the earlier value until the next keyframe, then jumps. */
    HOLD(2),
}

/**
 * A pose of a clip at [frame] frames after the clip's own start (not the project's), so keyframes
 * travel with the clip when it is moved. Opacity is part of [transform].
 */
data class Keyframe(
    val frame: Long,
    val transform: ClipTransform,
    val interpolation: Interpolation = Interpolation.LINEAR,
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
        val w = weight(from.interpolation, t)
        return ClipTransform(
            positionX = lerp(from.transform.positionX, to.transform.positionX, w),
            positionY = lerp(from.transform.positionY, to.transform.positionY, w),
            scaleX = lerp(from.transform.scaleX, to.transform.scaleX, w),
            scaleY = lerp(from.transform.scaleY, to.transform.scaleY, w),
            rotationDegrees = lerp(from.transform.rotationDegrees, to.transform.rotationDegrees, w),
            opacity = lerp(from.transform.opacity, to.transform.opacity, w),
        )
    }

    internal fun weight(mode: Interpolation, t: Double): Double = when (mode) {
        Interpolation.LINEAR -> t
        Interpolation.EASE -> t * t * (3.0 - 2.0 * t)
        Interpolation.HOLD -> 0.0
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
            val mode = keys.last { it.frame < from }.interpolation
            kept.add(0, Keyframe(0, evaluate(keys, from, base), mode))
        }
        val lastKept = kept.lastOrNull()?.frame ?: -1L
        if (keys.any { it.frame >= to } && lastKept < length - 1) {
            kept += Keyframe(length - 1, evaluate(keys, to - 1, base))
        }
        return kept
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
