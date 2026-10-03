package com.ultimatevideo.uveditor.engine.audio

import java.nio.ByteBuffer

/** JNI bindings only. Use [AudioPlaybackEngine]. */
internal object NativeAudio {
    init {
        System.loadLibrary("uveditor_engine")
    }

    external fun nativeCreate(): Long
    external fun nativeDestroy(handle: Long)
    external fun nativeStart(handle: Long, offline: Boolean): Int
    external fun nativeStop(handle: Long)
    external fun nativeSetAssetFd(handle: Long, assetKey: Long, fd: Int): Int
    external fun nativeRemoveAsset(handle: Long, assetKey: Long)
    external fun nativeSetSnapshot(handle: Long, buffer: ByteBuffer, size: Int): Int
    external fun nativePlay(handle: Long)
    external fun nativePause(handle: Long)
    external fun nativeSeek(handle: Long, frame: Long): Int
    external fun nativePositionFrame(handle: Long): Long
    external fun nativePositionSamples(handle: Long): Long
    external fun nativeStats(handle: Long): LongArray?
    external fun nativePollFaults(handle: Long): LongArray?
    external fun nativeRenderOffline(handle: Long, out: FloatArray, frames: Int): Long
}
