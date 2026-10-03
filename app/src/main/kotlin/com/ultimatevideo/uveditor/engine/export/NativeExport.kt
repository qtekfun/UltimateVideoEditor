package com.ultimatevideo.uveditor.engine.export

import java.nio.ByteBuffer

internal interface NativeExportListener {
    fun onProgress(permille: Int)
    fun onFinished(code: Int, message: String)
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
        sourceClips: LongArray,
        sourceTable: LongArray,
        titleMeta: IntArray,
        titlePixels: Array<ByteBuffer>,
        audioSnapshot: ByteBuffer?,
        outputFd: Int,
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
                listener.onFinished(if (code == 0) null else ExportException(code, message))
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
        val titleMeta = IntArray(request.titles.size * TITLE_INTS)
        request.titles.forEachIndexed { i, title ->
            titleMeta[i * TITLE_INTS] = title.key
            titleMeta[i * TITLE_INTS + 1] = title.width
            titleMeta[i * TITLE_INTS + 2] = title.height
        }
        val titlePixels = Array(request.titles.size) { request.titles[it].pixels }
        val s = request.settings
        val handle = try {
            NativeExport.nativeStart(
                native, s.width, s.height, s.fpsNum, s.fpsDen, request.projectFpsNum, request.projectFpsDen,
                request.canvasWidth, request.canvasHeight, s.codec.value, s.videoBitrate, s.audioBitrate, request.totalFrames,
                keys, fds, clips, transforms, keyClips, keyFrames, keyValues, sourceClips, sourceTable, titleMeta, titlePixels, request.audioSnapshot, request.outputFd,
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
        const val CLIP_LONGS = 9
        const val CLIP_DOUBLES = 6
        const val KEY_CLIP_LONGS = 2
        const val KEY_LONGS = 2
        const val KEY_DOUBLES = 6
        const val SOURCE_CLIP_LONGS = 2
        const val TITLE_INTS = 3
    }
}
