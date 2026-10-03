package com.ultimatevideo.uveditor.engine

/** Failure reported by the native engine or while loading it. Never swallowed silently. */
class EngineException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The only entry point from Kotlin into the native engine. Everything above this
 * interface stays free of JNI so it can be unit-tested with a fake.
 */
interface EngineClient {
    /** @throws EngineException if the native library is unavailable. */
    fun version(): String
}

/** [EngineClient] backed by the `uveditor_engine` shared library. */
class NativeEngineClient : EngineClient {
    override fun version(): String = try {
        NativeEngine.nativeVersion()
    } catch (e: UnsatisfiedLinkError) {
        throw EngineException("Native engine is not available", e)
    }
}

/** JNI bindings only. Keep logic out of this object; see [EngineClient]. */
internal object NativeEngine {
    init {
        System.loadLibrary("uveditor_engine")
    }

    external fun nativeVersion(): String
}
