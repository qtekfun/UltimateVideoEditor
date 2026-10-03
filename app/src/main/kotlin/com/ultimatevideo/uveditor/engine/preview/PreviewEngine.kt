package com.ultimatevideo.uveditor.engine.preview

import android.os.ParcelFileDescriptor
import android.view.Surface

internal fun interface NativeErrorListener {
    fun onError(code: Int, message: String)
}

/** JNI bindings only; use [PreviewEngine]. */
internal object NativePreview {
    init {
        System.loadLibrary("uveditor_engine")
    }

    external fun nativeCreate(listener: NativeErrorListener, budgetBytes: Long): Long
    external fun nativeDestroy(handle: Long)
    external fun nativeAttachSurface(handle: Long, surface: Surface)
    external fun nativeDetachSurface(handle: Long)
    external fun nativeSurfaceChanged(handle: Long)
    external fun nativeOpenAsset(handle: Long, assetId: Int, fd: Int, fpsNum: Int, fpsDen: Int): LongArray
    external fun nativeCloseAsset(handle: Long, assetId: Int)
    external fun nativeSeek(handle: Long, assetId: Int, frame: Long)
    external fun nativePlay(handle: Long, assetId: Int, startFrame: Long)
    external fun nativePause(handle: Long)
    external fun nativeSetColorMode(handle: Long, assetId: Int, mode: Int)
    external fun nativeSetCacheBudget(handle: Long, bytes: Long)
    external fun nativeStats(handle: Long): LongArray
}

/**
 * Hardware-decoded preview: AMediaCodec decode into an LRU frame cache of AHardwareBuffers and a
 * GLES 3.2 compositor presenting on a [Surface]. Frame positions are integer frame indices.
 *
 * Calls that touch media ([openAsset]) do blocking I/O: invoke them off the main thread. All other
 * calls only enqueue work for the native render thread. Failures on native threads arrive through
 * the `onError` callback (on a native thread); synchronous failures throw [PreviewException].
 */
class PreviewEngine private constructor(
    private var handle: Long,
    @Suppress("unused") private val listener: NativeErrorListener, // keeps the callback strongly reachable
) : AutoCloseable {

    @Synchronized
    private fun requireHandle(): Long {
        check(handle != 0L) { "PreviewEngine is closed" }
        return handle
    }

    fun attachSurface(surface: Surface) = NativePreview.nativeAttachSurface(requireHandle(), surface)

    /** Blocks until the render thread has released the surface, so it is safe to destroy it afterwards. */
    @Synchronized
    fun detachSurface() {
        if (handle != 0L) NativePreview.nativeDetachSurface(handle) // already closed: nothing left to detach
    }

    /** Call when the surface size or format changed so the current frame is redrawn to fit. */
    fun surfaceChanged() = NativePreview.nativeSurfaceChanged(requireHandle())

    /**
     * Opens an H.264/HEVC asset under [assetId]. The descriptor is consumed (detached) even on failure.
     * Pass [fpsNum]/[fpsDen] = 0 to use the container frame rate.
     */
    fun openAsset(assetId: Int, descriptor: ParcelFileDescriptor, fpsNum: Int = 0, fpsDen: Int = 0): AssetInfo {
        val fd = descriptor.detachFd()
        val v = NativePreview.nativeOpenAsset(requireHandle(), assetId, fd, fpsNum, fpsDen)
        return AssetInfo(
            width = v[0].toInt(),
            height = v[1].toInt(),
            durationFrames = v[2],
            fpsNum = v[3],
            fpsDen = v[4],
            colorTransfer = v[5].toInt(),
            rotationDegrees = v[6].toInt(),
        )
    }

    fun closeAsset(assetId: Int) = NativePreview.nativeCloseAsset(requireHandle(), assetId)

    /** Shows [frame] of the asset as soon as it is decoded; also moves the look-ahead window. */
    fun seek(assetId: Int, frame: Long) = NativePreview.nativeSeek(requireHandle(), assetId, frame)

    /** Plays from [startFrame] on a monotonic clock (no audio yet). */
    fun play(assetId: Int, startFrame: Long) = NativePreview.nativePlay(requireHandle(), assetId, startFrame)

    fun pause() = NativePreview.nativePause(requireHandle())

    fun setColorMode(assetId: Int, mode: ColorMode) =
        NativePreview.nativeSetColorMode(requireHandle(), assetId, mode.value)

    fun setCacheBudget(bytes: Long) = NativePreview.nativeSetCacheBudget(requireHandle(), bytes)

    fun stats(): PreviewStats {
        val v = NativePreview.nativeStats(requireHandle())
        return PreviewStats(v[0], v[1], v[2], v[3], v[4], v[5])
    }

    @Synchronized
    override fun close() {
        if (handle == 0L) return
        NativePreview.nativeDestroy(handle)
        handle = 0L
    }

    companion object {
        /** 1 GiB, the reference budget for high-end devices. */
        const val DEFAULT_CACHE_BUDGET_BYTES: Long = 1L shl 30

        /** @throws PreviewException if EGL/GLES initialisation fails. */
        fun create(
            cacheBudgetBytes: Long = DEFAULT_CACHE_BUDGET_BYTES,
            onError: (PreviewException) -> Unit,
        ): PreviewEngine {
            val listener = NativeErrorListener { code, message -> onError(PreviewException(code, message)) }
            val handle = NativePreview.nativeCreate(listener, cacheBudgetBytes)
            return PreviewEngine(handle, listener)
        }
    }
}
