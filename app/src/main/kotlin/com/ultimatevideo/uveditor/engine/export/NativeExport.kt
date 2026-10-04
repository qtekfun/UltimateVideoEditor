package com.ultimatevideo.uveditor.engine.export

import java.nio.ByteBuffer
import com.ultimatevideo.uveditor.engine.fx.FxWire

internal interface NativeExportListener {
    fun onProgress(permille: Int)
    fun onFinished(code: Int, message: String)

    /**
     * Called from the render thread for a still the export draws: returns its pixels (a direct buffer) and writes
     * {width, height, displayWidth, displayHeight} into [meta], or returns null.
     */
    fun loadPicture(key: Int, meta: IntArray): ByteBuffer?
}

/** JNI bindings only; use [NativeExportRunner]. */
internal object NativeExport {
    init {
        System.loadLibrary("uveditor_engine")
    }

    @Suppress("LongParameterList")
    external fun nativeStart(
        listener: NativeExportListener,
        width: Int,
        height: Int,
        fpsNum: Int,
        fpsDen: Int,
        projectFpsNum: Int,
        projectFpsDen: Int,
        canvasWidth: Int,
        canvasHeight: Int,
        codec: Int,
        videoBitrate: Int,
        audioBitrate: Int,
        totalFrames: Long,
        assetKeys: LongArray,
        assetFds: IntArray,
        clips: LongArray,
        transforms: DoubleArray,
        keyClips: LongArray,
        keyFrames: LongArray,
        keyValues: DoubleArray,
        fx: DoubleArray,
        fxFrameClips: LongArray,
        fxFrameData: DoubleArray,
        sourceClips: LongArray,
        sourceTable: LongArray,
        titleMeta: IntArray,
        titlePixels: Array<ByteBuffer>,
        lutMeta: IntArray,
        lutData: Array<ByteBuffer>,
        audioSnapshot: ByteBuffer?,
        outputFd: Int,
        pictureBudget: Long,
    ): Long

    external fun nativeCancel(handle: Long)
    external fun nativeDestroy(handle: Long)
}

/** Offline export on a native thread: GLES compositor -> MediaCodec encoders -> MP4. */
class NativeExportRunner : ExportRunner {
    override fun start(request: ExportRequest, listener: ExportListener): ExportHandle {
        val native = object : NativeExportListener {
            override fun onProgress(permille: Int) = listener.onProgress(permille)

            override fun onFinished(code: Int, message: String) {
                // A picture that could not be made is reported with the reason, not the engine's generic text.
                val reason = request.pictureProvider?.lastError
                val text = if (code != 0 && reason != null) "A picture could not be drawn: $reason" else message
                listener.onFinished(if (code == 0) null else ExportException(code, text))
            }

            override fun loadPicture(key: Int, meta: IntArray): ByteBuffer? {
                val picture = request.pictureProvider?.load(key) ?: return null
                meta[0] = picture.width
                meta[1] = picture.height
                meta[2] = picture.displayWidth
                meta[3] = picture.displayHeight
                return picture.pixels
            }
        }
        val keys = request.assetFds.keys.toLongArray()
        val fds = IntArray(keys.size) { request.assetFds.getValue(keys[it]) }
        val clips = LongArray(request.videoClips.size * CLIP_LONGS)
        val transforms = DoubleArray(request.videoClips.size * CLIP_DOUBLES)
        request.videoClips.forEachIndexed { i, c ->
            val o = i * CLIP_LONGS
            clips[o] = c.startFrame
            clips[o + 1] = c.durationFrames
            clips[o + 2] = c.sourceInFrame
            clips[o + 3] = c.assetKey
            clips[o + 4] = c.layer.toLong()
            clips[o + 5] = c.colorMode.toLong()
            clips[o + 6] = c.lane.toLong()
            clips[o + 7] = c.crossfadeInFrames
            clips[o + 8] = c.titleKey.toLong()
            val t = i * CLIP_DOUBLES
            transforms[t] = c.positionX
            transforms[t + 1] = c.positionY
            transforms[t + 2] = c.scaleX
            transforms[t + 3] = c.scaleY
            transforms[t + 4] = c.rotationDegrees
            transforms[t + 5] = c.opacity
        }
        // Keyframes, flattened: per clip {origin frame, count}, per key {frame, interpolation} and six pose values.
        val allKeys = request.videoClips.flatMap { it.keyframes }
        val keyClips = LongArray(request.videoClips.size * KEY_CLIP_LONGS)
        request.videoClips.forEachIndexed { i, c ->
            keyClips[i * KEY_CLIP_LONGS] = c.keyframeOriginFrame
            keyClips[i * KEY_CLIP_LONGS + 1] = c.keyframes.size.toLong()
        }
        val keyFrames = LongArray(allKeys.size * KEY_LONGS)
        val keyValues = DoubleArray(allKeys.size * KEY_DOUBLES)
        allKeys.forEachIndexed { i, k ->
            keyFrames[i * KEY_LONGS] = k.frame
            keyFrames[i * KEY_LONGS + 1] = k.interpolation.toLong()
            val v = i * KEY_DOUBLES
            keyValues[v] = k.positionX
            keyValues[v + 1] = k.positionY
            keyValues[v + 2] = k.scaleX
            keyValues[v + 3] = k.scaleY
            keyValues[v + 4] = k.rotationDegrees
            keyValues[v + 5] = k.opacity
        }
        // Retimed clips, flattened: per clip {reverse flag, table length}, then every clip's table in order.
        val sourceClips = LongArray(request.videoClips.size * SOURCE_CLIP_LONGS)
        request.videoClips.forEachIndexed { i, c ->
            sourceClips[i * SOURCE_CLIP_LONGS] = if (c.reverse) 1L else 0L
            sourceClips[i * SOURCE_CLIP_LONGS + 1] = c.sourceFrames?.size?.toLong() ?: 0L
        }
        val sourceTable = LongArray(request.videoClips.sumOf { it.sourceFrames?.size ?: 0 })
        var tableAt = 0
        for (c in request.videoClips) {
            val frames = c.sourceFrames ?: continue
            frames.copyInto(sourceTable, tableAt)
            tableAt += frames.size
        }
        // Keyframed effect values: per animated clip {clip index, frame count}, then one effects blob per frame.
        val animated = request.videoClips.withIndex().filter { it.value.fxFrames != null }
        val fxFrameClips = LongArray(animated.size * 2)
        animated.forEachIndexed { n, (i, c) ->
            fxFrameClips[n * 2] = i.toLong()
            fxFrameClips[n * 2 + 1] = c.fxFrames?.size?.toLong() ?: 0L
        }
        val fxFrameData = FxWire.encodeAll(animated.flatMap { it.value.fxFrames.orEmpty() })
        val titleMeta = IntArray(request.titles.size * TITLE_INTS)
        request.titles.forEachIndexed { i, title ->
            titleMeta[i * TITLE_INTS] = title.key
            titleMeta[i * TITLE_INTS + 1] = title.width
            titleMeta[i * TITLE_INTS + 2] = title.height
            titleMeta[i * TITLE_INTS + 3] = title.displayWidth
            titleMeta[i * TITLE_INTS + 4] = title.displayHeight
        }
        val titlePixels = Array(request.titles.size) { request.titles[it].pixels }
        val lutMeta = IntArray(request.luts.size * 2)
        request.luts.forEachIndexed { i, lut ->
            lutMeta[i * 2] = lut.key
            lutMeta[i * 2 + 1] = lut.size
        }
        val lutData = Array(request.luts.size) { request.luts[it].rgb }
        val s = request.settings
        val handle = try {
            NativeExport.nativeStart(
                native, s.width, s.height, s.fpsNum, s.fpsDen, request.projectFpsNum, request.projectFpsDen,
                request.canvasWidth, request.canvasHeight, s.codec.value or (if (s.hdr) HDR_FLAG else 0), s.videoBitrate, s.audioBitrate, request.totalFrames,
                keys, fds, clips, transforms, keyClips, keyFrames, keyValues, FxWire.encode(request.videoClips.map { it.fx }),
                fxFrameClips, fxFrameData, sourceClips, sourceTable, titleMeta, titlePixels, lutMeta, lutData, request.audioSnapshot, request.outputFd,
                request.pictureBudgetBytes,
            )
        } catch (e: UnsatisfiedLinkError) {
            throw ExportException(ExportErrorCode.NOT_INITIALIZED, "The native engine is not available: ${e.message}")
        }
        return NativeHandle(handle, native)
    }

    private class NativeHandle(
        private var handle: Long,
        @Suppress("unused") private val listener: NativeExportListener, // keeps the callback reachable
    ) : ExportHandle {
        @Synchronized
        override fun cancel() {
            if (handle != 0L) NativeExport.nativeCancel(handle)
        }

        @Synchronized
        override fun close() {
            if (handle == 0L) return
            NativeExport.nativeDestroy(handle)
            handle = 0L
        }
    }

    private companion object {
        /** Added to the codec value for an HLG export; see `export_jni.cpp`. */
        const val HDR_FLAG = 0x100
        const val CLIP_LONGS = 9
        const val CLIP_DOUBLES = 6
        const val KEY_CLIP_LONGS = 2
        const val KEY_LONGS = 2
        const val KEY_DOUBLES = 6
        const val SOURCE_CLIP_LONGS = 2
        const val TITLE_INTS = 5
    }
}
