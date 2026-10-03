package com.ultimatevideo.uveditor.ui.editor

import com.ultimatevideo.uveditor.domain.FrameRate
import kotlin.math.abs

/**
 * Tracks what the native preview clock was last told, to decide when it has to be told again.
 *
 * The native compositor advances every layer on its own monotonic clock, so while playing the
 * editor does not seek on every tick. It re-anchors only when the composition changed (another
 * clip under the playhead, an edited transform, an asset that finished opening) or when the heard
 * frame, which comes from the audio device, has drifted from where that clock should be by more
 * than [thresholdFrames]. All times are nanoseconds on one monotonic clock.
 */
internal class PreviewAnchor(private val thresholdFrames: Long) {

    private var key: Any? = null
    private var anchorFrame = 0L
    private var anchorNanos = 0L

    val isActive: Boolean get() = key != null

    /** Frame the native clock should be showing at [nowNanos], or null before the first anchor. */
    fun expectedFrame(nowNanos: Long, fps: FrameRate): Long? {
        if (key == null) return null
        val elapsedMicros = (nowNanos - anchorNanos).coerceAtLeast(0) / NANOS_PER_MICRO
        return anchorFrame + fps.microsToFrames(elapsedMicros)
    }

    fun needsReanchor(composition: Any, heardFrame: Long, nowNanos: Long, fps: FrameRate): Boolean {
        if (composition != key) return true
        val expected = expectedFrame(nowNanos, fps) ?: return true
        return abs(heardFrame - expected) > thresholdFrames
    }

    fun anchor(composition: Any, frame: Long, nowNanos: Long) {
        key = composition
        anchorFrame = frame
        anchorNanos = nowNanos
    }

    fun reset() {
        key = null
    }

    private companion object {
        const val NANOS_PER_MICRO = 1_000L
    }
}
