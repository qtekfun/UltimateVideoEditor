package com.ultimatevideo.uveditor.engine.timeline

import android.view.Surface
import java.nio.ByteBuffer

/** Receives waveform results from the native worker thread (not the main thread). */
interface WaveformListener {
    fun onWaveformReady(assetKey: Long, statusCode: Int)
}

/** JNI bindings only. Use [TimelineEngine]. */
internal object NativeTimeline {
    init {
        System.loadLibrary("uveditor_engine")
    }

    external fun nativeCreate(density: Float, listener: WaveformListener): Long
    external fun nativeDestroy(handle: Long)
    external fun nativeSurfaceCreated(handle: Long, surface: Surface): Int
    external fun nativeSurfaceChanged(handle: Long, width: Int, height: Int)
    external fun nativeSurfaceDestroyed(handle: Long)
    external fun nativeSetSnapshot(handle: Long, buffer: ByteBuffer, size: Int): Int
    external fun nativeScrollBy(handle: Long, dx: Float, dy: Float)
    external fun nativeZoomBy(handle: Long, factor: Float, focusX: Float)
    external fun nativeFling(handle: Long, velocityX: Float)
    external fun nativeZoomLanesBy(handle: Long, factor: Float, focusY: Float)
    external fun nativeFollowContent(handle: Long)
    external fun nativeFitToContent(handle: Long)
    external fun nativeIsAutoFit(handle: Long): Boolean
    external fun nativeSetPlayhead(handle: Long, frame: Long)
    external fun nativeEnsureVisible(handle: Long, frame: Long)

    external fun nativeSetDropHint(handle: Long, kind: Int, trackIndex: Int, startFrame: Long, endFrame: Long)

    /** The snap guide frame (negative for none) and the keys of the clips being dragged or trimmed (empty for none). */
    external fun nativeSetDragOverlay(handle: Long, snapGuideFrame: Long, clipKeys: LongArray?)

    external fun nativeSetMarquee(handle: Long, active: Boolean, x0: Float, y0: Float, x1: Float, y1: Float)
    external fun nativeSetLaneDrag(handle: Long, from: Int, to: Int)
    external fun nativeClipsInRect(handle: Long, x0: Float, y0: Float, x1: Float, y1: Float): LongArray?

    external fun nativeSetLaneScale(handle: Long, scale: Float)
    external fun nativeHitTest(handle: Long, x: Float, y: Float): LongArray?

    /** Colours of the canvas, from `Palette.nativeColours()`. */
    external fun nativeSetPalette(handle: Long, argb: IntArray)

    /** Copies a text bitmap (premultiplied RGBA, w*h*4 bytes) into the canvas's atlas under [hash]; callable from any thread. */
    external fun nativeLabelPut(handle: Long, hash: Long, buffer: ByteBuffer, width: Int, height: Int, colour: Boolean): Int

    /** Moves on each time the canvas drops its text atlas; the bitmaps sent before are gone. */
    external fun nativeLabelGeneration(handle: Long): Int

    /** Fills [out] with hashes of text bitmaps the canvas evicted to make room (each reported once); returns how many. */
    external fun nativeLabelTakeEvicted(handle: Long, out: LongArray): Int

    external fun nativeRequestWaveform(handle: Long, assetKey: Long, fd: Int, cachePath: String): Int
}
