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
        codec: Int,
        videoBitrate: Int,
        audioBitrate: Int,
        totalFrames: Long,
        assetKeys: LongArray,
        assetFds: IntArray,
        clips: LongArray,
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
        request.videoClips.forEachIndexed { i, c ->
            val o = i * CLIP_LONGS
            clips[o] = c.startFrame
            clips[o + 1] = c.durationFrames
            clips[o + 2] = c.sourceInFrame
            clips[o + 3] = c.assetKey
            clips[o + 4] = c.layer.toLong()
            clips[o + 5] = c.colorMode.toLong()
        }
        val s = request.settings
        val handle = try {
            NativeExport.nativeStart(
                native, s.width, s.height, s.fpsNum, s.fpsDen, request.projectFpsNum, request.projectFpsDen,
                s.codec.value, s.videoBitrate, s.audioBitrate, request.totalFrames,
                keys, fds, clips, request.audioSnapshot, request.outputFd,
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
        const val CLIP_LONGS = 6
    }
}
