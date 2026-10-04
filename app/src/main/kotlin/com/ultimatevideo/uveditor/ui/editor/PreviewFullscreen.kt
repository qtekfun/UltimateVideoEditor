package com.ultimatevideo.uveditor.ui.editor

import kotlin.math.hypot

/** How long the fullscreen controls stay up after the last touch. */
internal const val FULLSCREEN_OVERLAY_MS = 2500L

/**
 * Whether the preview fills the window, and whether its small control overlay (play/pause, exit) is showing.
 * Pure: the screen owns one, the reducer below is tested on the JVM. Only [active] is saved across recreation;
 * the overlay always starts hidden. [overlayEpoch] changes every time the overlay is (re)shown, so a timer
 * armed for an older showing cannot hide a newer one.
 */
internal data class FullscreenState(
    val active: Boolean = false,
    val overlayVisible: Boolean = false,
    val overlayEpoch: Int = 0,
)

internal sealed interface FullscreenAction {
    /** A double tap on the preview: enter, or leave. */
    data object DoubleTap : FullscreenAction

    /** A single tap on the preview: toggles the overlay while fullscreen, does nothing otherwise. */
    data object Tap : FullscreenAction

    /** The system Back or the exit icon. */
    data object Exit : FullscreenAction

    /** A press on an overlay control: keeps the overlay up for another [FULLSCREEN_OVERLAY_MS]. */
    data object Interact : FullscreenAction

    /** The overlay timer armed for [epoch] ran out. */
    data class Timeout(val epoch: Int) : FullscreenAction
}

internal fun FullscreenState.reduce(action: FullscreenAction): FullscreenState = when (action) {
    FullscreenAction.DoubleTap -> if (active) leave() else enter()
    FullscreenAction.Exit -> if (active) leave() else this
    FullscreenAction.Tap -> when {
        !active -> this
        overlayVisible -> copy(overlayVisible = false)
        else -> copy(overlayVisible = true, overlayEpoch = overlayEpoch + 1)
    }
    FullscreenAction.Interact -> if (active) copy(overlayVisible = true, overlayEpoch = overlayEpoch + 1) else this
    is FullscreenAction.Timeout ->
        if (active && overlayVisible && action.epoch == overlayEpoch) copy(overlayVisible = false) else this
}

// The overlay is shown for a moment on entering, so the way out is visible.
private fun FullscreenState.enter() = copy(active = true, overlayVisible = true, overlayEpoch = overlayEpoch + 1)

private fun FullscreenState.leave() = copy(active = false, overlayVisible = false)

/** Back leaves fullscreen first; it is the editor's own Back only when this is false. */
internal val FullscreenState.consumesBack: Boolean get() = active

/**
 * Tells a double tap from two single ones. A "tap" (one finger, short, barely moving) is reported to [tap]; if
 * the previous tap was at most [maxGapMs] earlier and within [maxDistancePx], it is the second of a double tap.
 * [continuesTap] answers the same for a touch that has only just gone down, so the caller can claim it early.
 */
internal class DoubleTapTracker(private val maxGapMs: Long, private val maxDistancePx: Float) {
    private var lastUpMs = Long.MIN_VALUE
    private var lastX = 0f
    private var lastY = 0f

    fun continuesTap(downMs: Long, x: Float, y: Float): Boolean =
        lastUpMs != Long.MIN_VALUE && downMs - lastUpMs in 0..maxGapMs && hypot(x - lastX, y - lastY) <= maxDistancePx

    /** A finished tap that went down at [downMs] and up at [upMs] at ([x], [y]). True when it completes a double tap. */
    fun tap(downMs: Long, upMs: Long, x: Float, y: Float): Boolean {
        if (continuesTap(downMs, x, y)) {
            reset()
            return true
        }
        lastUpMs = upMs
        lastX = x
        lastY = y
        return false
    }

    /** Something that is not a tap happened (a drag, a second finger): the next tap starts over. */
    fun reset() {
        lastUpMs = Long.MIN_VALUE
    }
}
