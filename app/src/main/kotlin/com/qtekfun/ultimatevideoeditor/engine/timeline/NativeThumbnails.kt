package com.qtekfun.ultimatevideoeditor.engine.timeline

/** Receives thumbnail failures from the native worker thread (not the main thread). */
interface ThumbnailListener {
    /** [detail] says what failed and the underlying code (also in logcat, tag uv_thumb); it may be empty. */
    fun onThumbnailError(assetKey: Long, statusCode: Int, detail: String)
}

/** JNI bindings only. Use [TimelineEngine]. */
internal object NativeThumbnails {
    init {
        System.loadLibrary("uveditor_engine")
    }

    external fun nativeAttach(timelineHandle: Long, listener: ThumbnailListener): Int
    external fun nativeRegisterAsset(timelineHandle: Long, assetKey: Long, fd: Int, cacheDir: String): Int
}
