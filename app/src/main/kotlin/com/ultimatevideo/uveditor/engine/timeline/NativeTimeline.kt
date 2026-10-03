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
    external fun nativeSetPlayhead(handle: Long, frame: Long)
    external fun nativeHitTest(handle: Long, x: Float, y: Float): LongArray?
    external fun nativeRequestWaveform(handle: Long, assetKey: Long, fd: Int, cachePath: String): Int
}
