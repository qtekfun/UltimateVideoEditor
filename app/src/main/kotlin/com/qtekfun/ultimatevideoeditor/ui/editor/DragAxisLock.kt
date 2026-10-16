package com.qtekfun.ultimatevideoeditor.ui.editor

import kotlin.math.abs
import kotlin.math.max

/**
 * Keeps a clip drag on one axis. A finger never moves straight: dragging a clip down to another lane also slid it a few frames
 * along the time axis, and sliding it along the lane could tip it into the lane below. From where the finger went down, the
 * first clear movement chooses:
 *
 *  - mainly vertical: the clip changes lane and its position in time stays exactly where it was;
 *  - mainly horizontal: the clip moves in time and stays on its lane;
 *  - about diagonal: no lock, the clip follows the finger freely.
 *
 * A lock lets go (for the rest of the drag) once the finger has gone [releasePx] along the other axis, so a clip can still be
 * carried anywhere: pull past that distance and it follows the finger on both axes. Until the direction is clear
 * ([decidePx]) the clip stays where it was picked up. Pure arithmetic, no Android types.
 */
class DragAxisLock(private val decidePx: Float, private val releasePx: Float, private val dominance: Float = 1.25f) {
    enum class Axis { UNDECIDED, VERTICAL, HORIZONTAL, FREE }

    /** The point the drag should use for a finger at some position. */
    data class Point(val x: Float, val y: Float)

    var axis: Axis = Axis.UNDECIDED
        private set

    private var anchorX = 0f
    private var anchorY = 0f

    /** A drag starts with the finger at ([x], [y]), where it went down on the clip. */
    fun begin(x: Float, y: Float) {
        anchorX = x
        anchorY = y
        axis = Axis.UNDECIDED
    }

    /** The point to drag to for a finger now at ([x], [y]). */
    fun apply(x: Float, y: Float): Point {
        val dx = abs(x - anchorX)
        val dy = abs(y - anchorY)
        if (axis == Axis.UNDECIDED) {
            if (max(dx, dy) < decidePx) return Point(anchorX, anchorY)
            axis = when {
                dy >= dx * dominance -> Axis.VERTICAL
                dx >= dy * dominance -> Axis.HORIZONTAL
                else -> Axis.FREE
            }
        }
        when (axis) {
            Axis.VERTICAL -> if (dx > releasePx) axis = Axis.FREE
            Axis.HORIZONTAL -> if (dy > releasePx) axis = Axis.FREE
            else -> Unit
        }
        return when (axis) {
            Axis.VERTICAL -> Point(anchorX, y)
            Axis.HORIZONTAL -> Point(x, anchorY)
            else -> Point(x, y)
        }
    }
}
