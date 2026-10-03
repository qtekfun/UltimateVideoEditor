package com.ultimatevideo.uveditor.engine.preview

import android.os.ParcelFileDescriptor
import android.view.Surface
import com.ultimatevideo.uveditor.engine.fx.FxWire

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
    external fun nativeSetScene(
        handle: Long,
        canvasWidth: Int,
        canvasHeight: Int,
        ids: LongArray,
        params: FloatArray,
        fx: DoubleArray,
    )
    external fun nativePlayScene(
        handle: Long,
        canvasWidth: Int,
        canvasHeight: Int,
        ids: LongArray,
        params: FloatArray,
        fx: DoubleArray,
        fpsNum: Int,
        fpsDen: Int,
    )
    external fun nativeUploadTitle(handle: Long, key: Int, width: Int, height: Int, pixels: java.nio.ByteBuffer)
    external fun nativeReleaseTitle(handle: Long, key: Int)
    external fun nativeSeek(handle: Long, assetId: Int, frame: Long)
    external fun nativePlay(handle: Long, assetId: Int, startFrame: Long)
    external fun nativePause(handle: Long)
    external fun nativeSetColorMode(handle: Long, assetId: Int, mode: Int)
    external fun nativeUploadLut(handle: Long, key: Int, size: Int, rgb: java.nio.ByteBuffer)
    external fun nativeReleaseLut(handle: Long, key: Int)
    external fun nativeSetOutputSpace(handle: Long, space: Int): Int
    external fun nativeGetOutputSpace(handle: Long): Int
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

    /**
     * Shows [layers] (bottom to top) composited on a [canvasWidth] x [canvasHeight] project canvas that
     * is letterboxed into the surface. Each layer's asset must be open; frames are in the frame rate
     * the asset was opened with. The scene is drawn once every layer's frame is decoded, so a layer
     * that is still catching up delays the whole update rather than showing a half-built composite.
     * Replaces any earlier scene and stops [play]. Cheap enough to call on every playhead tick.
     */
    fun setScene(canvasWidth: Int, canvasHeight: Int, layers: List<PreviewLayer>) {
        require(canvasWidth > 0 && canvasHeight > 0) { "canvas must be positive: ${canvasWidth}x$canvasHeight" }
        val ids = LongArray(layers.size * 2)
        val params = FloatArray(layers.size * PARAMS_PER_LAYER)
        packLayers(layers, ids, 2, params)
        NativePreview.nativeSetScene(requireHandle(), canvasWidth, canvasHeight, ids, params, FxWire.encode(layers.map { it.fx }))
    }

    /**
     * Plays [layers] (as for [setScene]) on a native monotonic clock: every layer advances in step at
     * [fpsNum]/[fpsDen], the project frame rate, starting from its own [PreviewLayer.frame]. Layers
     * stop at [PreviewLayer.endFrame]. Call it again to re-anchor, for instance when the audio clock
     * disagrees with the preview or the composition changes. [setScene] or [pause] stops it.
     */
    fun playScene(canvasWidth: Int, canvasHeight: Int, layers: List<PreviewLayer>, fpsNum: Int, fpsDen: Int) {
        require(canvasWidth > 0 && canvasHeight > 0) { "canvas must be positive: ${canvasWidth}x$canvasHeight" }
        require(fpsNum > 0 && fpsDen > 0) { "frame rate must be positive: $fpsNum/$fpsDen" }
        val ids = LongArray(layers.size * 3)
        val params = FloatArray(layers.size * PARAMS_PER_LAYER)
        packLayers(layers, ids, 3, params)
        NativePreview.nativePlayScene(requireHandle(), canvasWidth, canvasHeight, ids, params, FxWire.encode(layers.map { it.fx }), fpsNum, fpsDen)
    }

    private fun packLayers(layers: List<PreviewLayer>, ids: LongArray, idStride: Int, params: FloatArray) {
        layers.forEachIndexed { i, layer ->
            ids[i * idStride] = if (layer.titleKey != 0) -layer.titleKey.toLong() else layer.assetId.toLong()
            ids[i * idStride + 1] = layer.frame
            if (idStride > 2) ids[i * idStride + 2] = layer.endFrame ?: Long.MAX_VALUE
            val p = layer.placement
            val base = i * PARAMS_PER_LAYER
            params[base] = p.positionX
            params[base + 1] = p.positionY
            params[base + 2] = p.scaleX
            params[base + 3] = p.scaleY
            params[base + 4] = p.rotationDegrees
            params[base + 5] = p.opacity
            params[base + 6] = if (layer.reverse) -1f else 1f
            params[base + 7] = layer.sourceOverride.toFloat()
        }
    }

    /**
     * Stores a rasterised title under [key] (positive, chosen by the caller): [width] x [height]
     * premultiplied RGBA pixels in a direct buffer, top row first, in project canvas pixels (drawn
     * 1:1). The pixels are copied. Upload before the scene that uses it; re-uploading a key replaces it.
     */
    fun uploadTitle(key: Int, width: Int, height: Int, pixels: java.nio.ByteBuffer) {
        require(key > 0) { "title keys must be positive" }
        require(width > 0 && height > 0 && pixels.isDirect && pixels.remaining() >= width * height * 4) {
            "title pixels must be a direct buffer of ${width}x$height RGBA"
        }
        NativePreview.nativeUploadTitle(requireHandle(), key, width, height, pixels)
    }

    fun releaseTitle(key: Int) = NativePreview.nativeReleaseTitle(requireHandle(), key)

    /**
     * Stores a 3D LUT under [key] (positive; the LUT library's key) for LUT effects: [size]^3 RGB floats, red
     * varying fastest, in a direct native-order buffer. The values are copied; re-uploading a key replaces it.
     */
    fun uploadLut(key: Int, size: Int, rgb: java.nio.ByteBuffer) {
        require(key > 0) { "LUT keys must be positive" }
        require(size in 2..65 && rgb.isDirect && rgb.remaining() >= size * size * size * 3 * Float.SIZE_BYTES) {
            "LUT data must be a direct buffer of $size^3 RGB floats"
        }
        NativePreview.nativeUploadLut(requireHandle(), key, size, rgb)
    }

    fun releaseLut(key: Int) = NativePreview.nativeReleaseLut(requireHandle(), key)

    /** Shows [frame] of the asset as soon as it is decoded; also moves the look-ahead window. */
    fun seek(assetId: Int, frame: Long) = NativePreview.nativeSeek(requireHandle(), assetId, frame)

    /** Plays from [startFrame] on a monotonic clock (no audio yet). */
    fun play(assetId: Int, startFrame: Long) = NativePreview.nativePlay(requireHandle(), assetId, startFrame)

    fun pause() = NativePreview.nativePause(requireHandle())

    fun setColorMode(assetId: Int, mode: ColorMode) =
        NativePreview.nativeSetColorMode(requireHandle(), assetId, mode.value)

    /**
     * Chooses the colour space the preview surface is rendered in and returns the one actually in use:
     * [OutputSpace.HLG_2020] needs a ten-bit surface the device accepts as BT.2020 HLG, otherwise the
     * preview stays [OutputSpace.SDR_709] (HLG sources are tone-mapped). Call it again after the
     * surface is attached, because a request made earlier only applies once there is a surface.
     */
    fun setOutputSpace(space: OutputSpace): OutputSpace =
        OutputSpace.fromValue(NativePreview.nativeSetOutputSpace(requireHandle(), space.value))

    /** The colour space the surface is rendered in right now, see [setOutputSpace]. */
    fun outputSpace(): OutputSpace = OutputSpace.fromValue(NativePreview.nativeGetOutputSpace(requireHandle()))

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

        /** posX, posY, scaleX, scaleY, rotationDeg, opacity, direction (+1 forward, -1 reverse), source override (-1 auto, 0 SDR, 1 HLG, 2 PQ): the layout `nativeSetScene` reads. */
        private const val PARAMS_PER_LAYER = 8

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
