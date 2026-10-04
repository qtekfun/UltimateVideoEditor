package com.ultimatevideo.uveditor.engine.track

/**
 * What the Kotlin side needs from the native tracking service (`jni/track_jni.cpp`): a background analysis job.
 * An interface so the logic above it is tested with a fake. Status codes are `core::Status` (see EngineErrorCode).
 */
interface TrackNative {
    fun create(): Long

    /** Cancels any running job, waits for it and frees the handle. */
    fun destroy(handle: Long)

    /**
     * Takes ownership of [fd]. The box is in fractions of the upright frame. Returns a status code; 0 means the job
     * started.
     */
    fun start(
        handle: Long,
        fd: Int,
        startUs: Long,
        endUs: Long,
        seedUs: Long,
        halfFrameUs: Long,
        cx: Float,
        cy: Float,
        w: Float,
        h: Float,
        cachePath: String,
    ): Int

    fun cancel(handle: Long)

    /** Packed job state, the same layout as the stabiliser's (`StabJob.unpack`). */
    fun poll(handle: Long): Long
}

/** JNI bindings only; the object's name and method names are fixed by the exported C symbols. */
internal object NativeMotionTracker {
    init {
        System.loadLibrary("uveditor_engine")
    }

    external fun nativeCreate(): Long

    external fun nativeDestroy(handle: Long)

    external fun nativeStart(
        handle: Long,
        fd: Int,
        startUs: Long,
        endUs: Long,
        seedUs: Long,
        halfFrameUs: Long,
        cx: Float,
        cy: Float,
        w: Float,
        h: Float,
        cachePath: String,
    ): Int

    external fun nativeCancel(handle: Long)

    external fun nativePoll(handle: Long): Long
}

/** [TrackNative] backed by `libuveditor_engine`. */
internal object JniTrackNative : TrackNative {
    override fun create(): Long = NativeMotionTracker.nativeCreate()

    override fun destroy(handle: Long) = NativeMotionTracker.nativeDestroy(handle)

    override fun start(
        handle: Long,
        fd: Int,
        startUs: Long,
        endUs: Long,
        seedUs: Long,
        halfFrameUs: Long,
        cx: Float,
        cy: Float,
        w: Float,
        h: Float,
        cachePath: String,
    ): Int = NativeMotionTracker.nativeStart(handle, fd, startUs, endUs, seedUs, halfFrameUs, cx, cy, w, h, cachePath)

    override fun cancel(handle: Long) = NativeMotionTracker.nativeCancel(handle)

    override fun poll(handle: Long): Long = NativeMotionTracker.nativePoll(handle)
}
