package com.ultimatevideo.uveditor.engine.timeline

import android.view.Surface
import com.ultimatevideo.uveditor.engine.EngineException
import java.io.File

/** Native status codes (mirror of uv::core::Status). */
enum class EngineStatus(val code: Int) {
    OK(0), INVALID_ARGUMENT(1), BAD_SNAPSHOT(2), IO_ERROR(3), UNSUPPORTED_FORMAT(4),
    CODEC_ERROR(5), GL_ERROR(6), NOT_INITIALIZED(7), CANCELLED(8), UNKNOWN(-1);

    companion object {
        fun fromCode(code: Int): EngineStatus = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

enum class HitKind { NONE, RULER, CLIP, CLIP_LEFT_EDGE, CLIP_RIGHT_EDGE, EMPTY_TRACK }

data class TimelineHit(val kind: HitKind, val trackIndex: Int, val clipKey: Long, val frame: Long)

/**
 * Owns the native timeline canvas and waveform service. Call all methods from the main thread;
 * [onWaveform] is invoked on a native worker thread and must hop to the main thread itself.
 */
class TimelineEngine(
    density: Float,
    private val onWaveform: (assetKey: Long, status: EngineStatus) -> Unit,
) : AutoCloseable {

    private val listener = object : WaveformListener {
        override fun onWaveformReady(assetKey: Long, statusCode: Int) {
            onWaveform(assetKey, EngineStatus.fromCode(statusCode))
        }
    }

    private var handle: Long = try {
        NativeTimeline.nativeCreate(density, listener)
    } catch (e: UnsatisfiedLinkError) {
        throw EngineException("Native engine is not available", e)
    }

    init {
        if (handle == 0L) throw EngineException("Could not create the native timeline")
    }

    fun surfaceCreated(surface: Surface) = throwIfFailed(NativeTimeline.nativeSurfaceCreated(live(), surface), "surfaceCreated")
    fun surfaceChanged(width: Int, height: Int) = NativeTimeline.nativeSurfaceChanged(live(), width, height)
    fun surfaceDestroyed() = NativeTimeline.nativeSurfaceDestroyed(live())

    fun setSnapshot(snapshot: TimelineSnapshot) {
        val buffer = snapshot.encode()
        throwIfFailed(NativeTimeline.nativeSetSnapshot(live(), buffer, buffer.remaining()), "setSnapshot")
    }

    fun scrollBy(dx: Float, dy: Float) = NativeTimeline.nativeScrollBy(live(), dx, dy)
    fun zoomBy(factor: Float, focusX: Float) = NativeTimeline.nativeZoomBy(live(), factor, focusX)
    fun fling(velocityX: Float) = NativeTimeline.nativeFling(live(), velocityX)
    fun setPlayhead(frame: Long) = NativeTimeline.nativeSetPlayhead(live(), frame)

    fun hitTest(x: Float, y: Float): TimelineHit {
        val r = NativeTimeline.nativeHitTest(live(), x, y) ?: throw EngineException("hitTest failed")
        val kind = HitKind.entries.getOrElse(r[0].toInt()) { HitKind.NONE }
        return TimelineHit(kind, r[1].toInt(), r[2], r[3])
    }

    /**
     * Starts background extraction (or loads the cache at [cacheFile]). Takes ownership of
     * [fd], which must be a detached descriptor. The result arrives via the `onWaveform` callback.
     */
    fun requestWaveform(assetKey: Long, fd: Int, cacheFile: File) {
        throwIfFailed(NativeTimeline.nativeRequestWaveform(live(), assetKey, fd, cacheFile.absolutePath), "requestWaveform")
    }

    override fun close() {
        val h = handle
        if (h == 0L) return
        handle = 0L
        NativeTimeline.nativeDestroy(h)
    }

    private fun live(): Long {
        check(handle != 0L) { "TimelineEngine is closed" }
        return handle
    }

    private fun throwIfFailed(code: Int, what: String) {
        val status = EngineStatus.fromCode(code)
        if (status != EngineStatus.OK) throw EngineException("$what failed: $status")
    }
}
