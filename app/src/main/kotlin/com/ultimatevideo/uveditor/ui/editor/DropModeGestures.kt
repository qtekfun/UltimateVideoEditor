package com.ultimatevideo.uveditor.ui.editor

import kotlin.math.abs
import kotlin.math.hypot

/** How close to a cut a dragged clip still counts as at the cut, in frames, from what a fingertip covers at the current zoom. */
internal object DropReach {
    /** Width of the zone around a cut, in dp (about a fingertip). */
    const val REACH_DP = 28f

    /** A zoomed-out timeline cannot make everything a cut. */
    const val MAX_FRAMES = 100_000L

    /**
     * [frameAtFinger] and [frameOneReachAway] are the frames under two points [REACH_DP] apart on the time axis. Never below one
     * frame, so a cut can always be hit exactly.
     */
    fun frames(frameAtFinger: Long, frameOneReachAway: Long): Long = abs(frameOneReachAway - frameAtFinger).coerceIn(1L, MAX_FRAMES)
}

/**
 * A tap with a second finger while the first one drags a clip: the way to switch the drop between insert and overwrite without
 * letting go. A press that stays put and lifts quickly is a tap; one that moves, lingers or becomes a third finger is not.
 * Pure arithmetic, no Android types.
 */
internal class SecondFingerTap(private val slopPx: Float, private val timeoutMs: Long = DEFAULT_TIMEOUT_MS) {
    private var pointerId = NONE
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var spoiled = false

    /** A finger other than the first went down. Another one on top of it spoils the tap (a pinch or an accident). */
    fun onPointerDown(id: Int, x: Float, y: Float, timeMs: Long) {
        if (pointerId != NONE) {
            spoiled = true
            return
        }
        pointerId = id
        downX = x
        downY = y
        downAt = timeMs
        spoiled = false
    }

    /** The finger [id] moved to ([x], [y]). */
    fun onMove(id: Int, x: Float, y: Float) {
        if (id == pointerId && hypot(x - downX, y - downY) > slopPx) spoiled = true
    }

    /** The finger [id] lifted; true when that completes a tap. */
    fun onPointerUp(id: Int, timeMs: Long): Boolean {
        if (id != pointerId) return false
        val tap = !spoiled && timeMs - downAt <= timeoutMs
        reset()
        return tap
    }

    /** The drag ended or was cancelled. */
    fun reset() {
        pointerId = NONE
        spoiled = false
    }

    private companion object {
        const val NONE = -1
        const val DEFAULT_TIMEOUT_MS = 350L
    }
}
