package com.ultimatevideo.uveditor.ui.editor

import kotlin.math.abs

/** The axis a two-finger pinch on the timeline scales. */
enum class PinchAxis { UNDECIDED, HORIZONTAL, VERTICAL }

/**
 * Decides, once per pinch, whether it zooms the time axis or the lane heights: the axis along which the span between the
 * fingers changed more, once either change passes [slopPx]. A tie goes to the time axis, which is what a pinch did before
 * lanes could be zoomed. The choice is kept until the fingers lift, so a wobble cannot switch axes mid-gesture. Pure
 * arithmetic, no Android types.
 */
class PinchAxisLock(private val slopPx: Float) {
    var axis: PinchAxis = PinchAxis.UNDECIDED
        private set
    private var startX = 0f
    private var startY = 0f

    fun begin(spanX: Float, spanY: Float) {
        axis = PinchAxis.UNDECIDED
        startX = spanX
        startY = spanY
    }

    /** Feeds the current finger spans and returns the (possibly just decided) axis. */
    fun update(spanX: Float, spanY: Float): PinchAxis {
        if (axis != PinchAxis.UNDECIDED) return axis
        val dx = abs(spanX - startX)
        val dy = abs(spanY - startY)
        if (maxOf(dx, dy) < slopPx) return axis
        axis = if (dy > dx) PinchAxis.VERTICAL else PinchAxis.HORIZONTAL
        return axis
    }

    /**
     * The lane height factor for one step: [held] (the factor accumulated before the axis was known, 1 afterwards) times the
     * change of the vertical span. A span too small to divide by leaves the lanes alone.
     */
    fun verticalFactor(held: Float, currentSpanY: Float, previousSpanY: Float): Float {
        val step = if (previousSpanY < MIN_SPAN_PX || currentSpanY < MIN_SPAN_PX) 1f else currentSpanY / previousSpanY
        return held * step
    }

    private companion object {
        const val MIN_SPAN_PX = 1f
    }
}
