package com.ultimatevideo.uveditor.ui.editor

import com.ultimatevideo.uveditor.domain.CurvePoint
import com.ultimatevideo.uveditor.domain.GradeCurve
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Pure maths behind the colour wheels. A wheel is a unit disk in screen coordinates (x right, y down) whose
 * ring is red at 0 degrees, green at 120 and blue at 240 clockwise; pushing the puck towards a colour
 * raises that channel and lowers the others, so the three channel values always sum to zero. The wheel
 * covers +-[SCALE] on each channel; the master slider carries the rest of the range.
 */
internal object WheelMath {
    const val SCALE = 0.5
    private val ROOT3_OVER_2 = sqrt(3.0) / 2.0

    /** Red, green, blue values for a puck at ([x], [y]) in the unit disk. */
    fun toRgb(x: Double, y: Double): Triple<Double, Double, Double> {
        val (cx, cy) = clampToDisk(x, y)
        return Triple(SCALE * cx, SCALE * (-0.5 * cx + ROOT3_OVER_2 * cy), SCALE * (-0.5 * cx - ROOT3_OVER_2 * cy))
    }

    /** Where the puck sits for channel values [r], [g], [b] (only their chroma part shows). */
    fun toPuck(r: Double, g: Double, b: Double): Pair<Double, Double> =
        clampToDisk((r - (g + b) / 2.0) / (1.5 * SCALE), (g - b) / (2.0 * ROOT3_OVER_2 * SCALE))

    fun clampToDisk(x: Double, y: Double): Pair<Double, Double> {
        val length = hypot(x, y)
        return if (length <= 1.0) x to y else (x / length) to (y / length)
    }

    /** A drag that starts within this distance of the puck (unit-disk units) grabs it; any other starts a scroll of the panel. */
    const val GRAB_RADIUS = 0.3

    /** Whether a touch at ([px], [py]) in a wheel of [size] pixels lands close enough to the puck at ([puckX], [puckY]) to grab it. */
    fun grabs(px: Float, py: Float, size: Float, puckX: Double, puckY: Double): Boolean {
        val (x, y) = fromTouch(px, py, size)
        return hypot(x - puckX, y - puckY) <= GRAB_RADIUS
    }

    /** Position of a touch at ([px], [py]) in a wheel of [size] pixels, as a point of the unit disk. */
    fun fromTouch(px: Float, py: Float, size: Float): Pair<Double, Double> {
        val half = size / 2.0
        return clampToDisk((px - half) / half, (py - half) / half)
    }
}

/** Pure editing of a tone curve's control points, used by the curve editor's gestures. */
internal object CurveEdit {
    /** Two points closer than this in x are the same point; interior points keep this distance from neighbours. */
    const val MIN_GAP = 0.02

    /** Adds a point at ([x], [y]), or null when the curve is full or a point is already there. */
    fun add(curve: GradeCurve, x: Double, y: Double): GradeCurve? {
        if (curve.points.size >= GradeCurve.MAX_POINTS) return null
        val cx = x.coerceIn(0.0, 1.0)
        if (curve.points.any { abs(it.x - cx) < MIN_GAP }) return null
        val points = (curve.points + CurvePoint(cx, y.coerceIn(0.0, 1.0))).sortedBy { it.x }
        return GradeCurve(points)
    }

    /**
     * Moves point [index] to ([x], [y]). The end points keep x = 0 and x = 1; interior points stay between
     * their neighbours. Returns the curve unchanged for a bad index.
     */
    fun move(curve: GradeCurve, index: Int, x: Double, y: Double): GradeCurve {
        val points = curve.points
        if (index !in points.indices) return curve
        val newY = y.coerceIn(0.0, 1.0)
        val newX = when (index) {
            0 -> points[0].x
            points.lastIndex -> points[index].x
            else -> x.coerceIn(points[index - 1].x + MIN_GAP, points[index + 1].x - MIN_GAP)
        }
        return GradeCurve(points.mapIndexed { i, p -> if (i == index) CurvePoint(newX, newY) else p })
    }

    /** Removes an interior point; the end points stay. Returns the curve unchanged otherwise. */
    fun remove(curve: GradeCurve, index: Int): GradeCurve =
        if (index in 1 until curve.points.lastIndex) GradeCurve(curve.points.filterIndexed { i, _ -> i != index }) else curve

    /** The index of the point within [radius] (in curve units) of ([x], [y]), nearest first, or null. */
    fun nearest(curve: GradeCurve, x: Double, y: Double, radius: Double): Int? =
        curve.points.withIndex()
            .map { (i, p) -> i to hypot(p.x - x, p.y - y) }
            .filter { it.second <= radius }
            .minByOrNull { it.second }?.first
}
