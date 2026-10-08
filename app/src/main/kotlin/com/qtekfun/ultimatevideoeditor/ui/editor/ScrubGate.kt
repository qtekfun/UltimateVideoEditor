package com.qtekfun.ultimatevideoeditor.ui.editor

import kotlin.math.abs

/**
 * Decides when a one-finger drag on the timeline canvas is a scrub, so that playback stops at that moment (LumaFusion: touch
 * the timeline while it plays and you are in control of it).
 *
 * A drag that picks up a clip, draws a selection rectangle or moves a lane never reaches this; it only sees drags that scroll
 * the content. Of those, a drag that is mainly horizontal (it moves along the time axis further than across the lanes, once
 * it has passed [slopPx]) is a scrub and fires once per gesture. A mostly vertical drag only scrolls the lanes, which is no
 * reason to stop the playback. Pure arithmetic, no Android types.
 */
class ScrubGate(private val slopPx: Float) {
    private var sumX = 0f
    private var sumY = 0f
    private var fired = false

    /** A finger went down: a new gesture starts. */
    fun begin() {
        sumX = 0f
        sumY = 0f
        fired = false
    }

    /**
     * One scroll step ([distanceX], [distanceY] as the platform reports them). Returns true exactly once per gesture: on the
     * step where the movement turns out to be a horizontal scrub.
     */
    fun onScroll(distanceX: Float, distanceY: Float): Boolean {
        if (fired) return false
        sumX += distanceX
        sumY += distanceY
        if (abs(sumX) < slopPx || abs(sumX) < abs(sumY)) return false
        fired = true
        return true
    }

    /** A fling: true once if the gesture had not been recognised as a scrub yet and the fling runs along the time axis. */
    fun onFling(velocityX: Float, velocityY: Float): Boolean {
        if (fired || abs(velocityX) < abs(velocityY)) return false
        fired = true
        return true
    }
}
