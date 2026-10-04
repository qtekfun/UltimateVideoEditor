package com.ultimatevideo.uveditor.engine.stabilise

/**
 * What the Kotlin side needs from the native stabiliser (`jni/stabilise_jni.cpp`): a background analysis service
 * and the registry of correction tables the compositor reads. An interface so the logic above it is tested
 * with a fake. Status codes are `core::Status` (see EngineErrorCode).
 */
interface StabNative {
    fun create(): Long

    /** Cancels any running job, waits for it and frees the handle. */
    fun destroy(handle: Long)

    /** Takes ownership of [fd]. Returns a status code; 0 means the job started. */
    fun start(handle: Long, fd: Int, startUs: Long, endUs: Long, cachePath: String): Int

    fun cancel(handle: Long)

    /** Packed job state: see [StabJob.unpack]. */
    fun poll(handle: Long): Long

    /** Builds the table of [key] from the analysis cache and registers it. Returns a status code; 0 is success. */
    fun register(key: Int, cachePath: String, fpsNum: Int, fpsDen: Int, strength: Float, crop: Int): Int

    fun release(key: Int)

    fun releaseAll()

    fun isRegistered(key: Int): Boolean
}

/** The state of a native analysis job as [StabNative.poll] reports it. */
data class StabJob(val state: State, val permille: Int, val errorCode: Int) {
    enum class State { IDLE, RUNNING, DONE, FAILED, CANCELLED }

    companion object {
        /** Bits 0..7 state, 8..23 progress in permille, 24..31 the status code of a failure. */
        fun unpack(packed: Long): StabJob {
            val state = State.entries.getOrElse((packed and 0xFF).toInt()) { State.FAILED }
            return StabJob(state, ((packed shr 8) and 0xFFFF).toInt(), ((packed shr 24) and 0xFF).toInt())
        }
    }
}

/** JNI bindings only; the object's name and method names are fixed by the exported C symbols. */
internal object NativeStabiliser {
    init {
        System.loadLibrary("uveditor_engine")
    }

    external fun nativeCreate(): Long

    external fun nativeDestroy(handle: Long)

    external fun nativeStart(handle: Long, fd: Int, startUs: Long, endUs: Long, cachePath: String): Int

    external fun nativeCancel(handle: Long)

    external fun nativePoll(handle: Long): Long

    external fun nativeRegister(key: Int, cachePath: String, fpsNum: Int, fpsDen: Int, strength: Float, crop: Int): Int

    external fun nativeRelease(key: Int)

    external fun nativeReleaseAll()

    external fun nativeIsRegistered(key: Int): Boolean
}

/** [StabNative] backed by `libuveditor_engine`. */
internal object JniStabNative : StabNative {
    override fun create(): Long = NativeStabiliser.nativeCreate()

    override fun destroy(handle: Long) = NativeStabiliser.nativeDestroy(handle)

    override fun start(handle: Long, fd: Int, startUs: Long, endUs: Long, cachePath: String): Int =
        NativeStabiliser.nativeStart(handle, fd, startUs, endUs, cachePath)

    override fun cancel(handle: Long) = NativeStabiliser.nativeCancel(handle)

    override fun poll(handle: Long): Long = NativeStabiliser.nativePoll(handle)

    override fun register(key: Int, cachePath: String, fpsNum: Int, fpsDen: Int, strength: Float, crop: Int): Int =
        NativeStabiliser.nativeRegister(key, cachePath, fpsNum, fpsDen, strength, crop)

    override fun release(key: Int) = NativeStabiliser.nativeRelease(key)

    override fun releaseAll() = NativeStabiliser.nativeReleaseAll()

    override fun isRegistered(key: Int): Boolean = NativeStabiliser.nativeIsRegistered(key)
}
