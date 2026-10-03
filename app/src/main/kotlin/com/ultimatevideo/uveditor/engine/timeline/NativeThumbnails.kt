package com.ultimatevideo.uveditor.engine.timeline

/** Receives thumbnail failures from the native worker thread (not the main thread). */
interface ThumbnailListener {
    fun onThumbnailError(assetKey: Long, statusCode: Int)
}

/** JNI bindings only. Use [TimelineEngine]. */
internal object NativeThumbnails {
    init {
        System.loadLibrary("uveditor_engine")
    }

    external fun nativeAttach(timelineHandle: Long, listener: ThumbnailListener): Int
    external fun nativeRegisterAsset(timelineHandle: Long, assetKey: Long, fd: Int, cacheDir: String): Int
}
