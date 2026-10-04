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

/** Order mirrors uv::timeline::HitKind; PLAYHEAD is the playhead handle inside the ruler. */
/**
 * [ABOVE_LANES] is the free room between the ruler and the first lane (the 'add a lane' zone) and
 * [OUTSIDE] means the finger left the panel; both exist so a drag can tell where it would land.
 */
enum class HitKind { NONE, RULER, CLIP, CLIP_LEFT_EDGE, CLIP_RIGHT_EDGE, EMPTY_TRACK, PLAYHEAD, ABOVE_LANES, OUTSIDE }

/** What the indicator drawn over the timeline during a clip drag shows; mirrors the native `DropHintKind`. */
enum class DropIndicator(val code: Int) { NONE(0), INSERT(1), OVERWRITE(2), NEW_LANE(3), CANCEL(4) }

data class TimelineHit(val kind: HitKind, val trackIndex: Int, val clipKey: Long, val frame: Long)

/**
 * Owns the native timeline canvas, waveform service and thumbnail service. Call all methods from the main thread;
 * [onWaveform] is invoked on a native worker thread and must hop to the main thread itself.
 */
class TimelineEngine(
    density: Float,
    private val onThumbnailError: (assetKey: Long, status: EngineStatus) -> Unit = { _, _ -> },
    private val onWaveform: (assetKey: Long, status: EngineStatus) -> Unit,
) : AutoCloseable {

    private val listener = object : WaveformListener {
        override fun onWaveformReady(assetKey: Long, statusCode: Int) {
            onWaveform(assetKey, EngineStatus.fromCode(statusCode))
        }
    }

    private val thumbnailListener = object : ThumbnailListener {
        override fun onThumbnailError(assetKey: Long, statusCode: Int) {
            onThumbnailError(assetKey, EngineStatus.fromCode(statusCode))
        }
    }

    private var handle: Long = try {
        NativeTimeline.nativeCreate(density, listener)
    } catch (e: UnsatisfiedLinkError) {
        throw EngineException("Native engine is not available", e)
    }

    init {
        if (handle == 0L) throw EngineException("Could not create the native timeline")
        val attached = NativeThumbnails.nativeAttach(handle, thumbnailListener)
        if (attached != EngineStatus.OK.code) {
            close()
            throw EngineException("Could not start the thumbnail service: ${EngineStatus.fromCode(attached)}")
        }
    }

    fun surfaceCreated(surface: Surface) = throwIfFailed(NativeTimeline.nativeSurfaceCreated(live(), surface), "surfaceCreated")

    // The view can outlive the engine by a frame when a screen is torn down; late surface
    // callbacks after close() have nothing left to release.
    fun surfaceChanged(width: Int, height: Int) {
        if (handle != 0L) NativeTimeline.nativeSurfaceChanged(handle, width, height)
    }

    fun surfaceDestroyed() {
        if (handle != 0L) NativeTimeline.nativeSurfaceDestroyed(handle)
    }

    fun setSnapshot(snapshot: TimelineSnapshot) {
        val buffer = snapshot.encode()
        throwIfFailed(NativeTimeline.nativeSetSnapshot(live(), buffer, buffer.remaining()), "setSnapshot")
    }

    fun scrollBy(dx: Float, dy: Float) = NativeTimeline.nativeScrollBy(live(), dx, dy)
    fun zoomBy(factor: Float, focusX: Float) = NativeTimeline.nativeZoomBy(live(), factor, focusX)
    fun fling(velocityX: Float) = NativeTimeline.nativeFling(live(), velocityX)

    /** Zooms to show the whole timeline and follows it on resize until the user zooms by hand. */
    fun fitToContent() = NativeTimeline.nativeFitToContent(live())

    /** False once the user has zoomed by hand, until the next [fitToContent]. */
    fun isAutoFit(): Boolean = NativeTimeline.nativeIsAutoFit(live())
    fun setPlayhead(frame: Long) = NativeTimeline.nativeSetPlayhead(live(), frame)

    /** Scrolls, keeping the zoom, until [frame] is on screen (pages when it leaves the view). */
    fun ensureVisible(frame: Long) = NativeTimeline.nativeEnsureVisible(live(), frame)

    /** Draws the drop indicator on lane [trackIndex] of the current snapshot over [startFrame, endFrame). */
    /** Lane height as a multiple of the default (0.5 to 2); the lanes, their waveforms, thumbnails and diamonds scale with it. */
    fun setLaneScale(scale: Float) = NativeTimeline.nativeSetLaneScale(live(), scale)

    fun setDropHint(indicator: DropIndicator, trackIndex: Int = -1, startFrame: Long = 0, endFrame: Long = 0) =
        NativeTimeline.nativeSetDropHint(live(), indicator.code, trackIndex, startFrame, endFrame)

    /** Draws the selection rectangle from (x0, y0) to (x1, y1) in view pixels, or hides it with [clearMarquee]. */
    fun setMarquee(x0: Float, y0: Float, x1: Float, y1: Float) = NativeTimeline.nativeSetMarquee(live(), true, x0, y0, x1, y1)

    fun clearMarquee() = NativeTimeline.nativeSetMarquee(live(), false, 0f, 0f, 0f, 0f)

    /** Keys of the clips whose block intersects the view-pixel rectangle, for the marquee selection. */
    fun clipsInRect(x0: Float, y0: Float, x1: Float, y1: Float): List<Long> =
        NativeTimeline.nativeClipsInRect(live(), x0, y0, x1, y1)?.toList().orEmpty()

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

    /**
     * Starts background thumbnail generation for a video asset (tiles are cached under [cacheDir]).
     * Takes ownership of [fd], which must be a detached descriptor. Tiles appear on the timeline as
     * they are decoded; a failure is reported through `onThumbnailError`.
     */
    fun requestThumbnails(assetKey: Long, fd: Int, cacheDir: File) {
        throwIfFailed(NativeThumbnails.nativeRegisterAsset(live(), assetKey, fd, cacheDir.absolutePath), "requestThumbnails")
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
