package com.ultimatevideo.uveditor.engine.audio

import android.os.ParcelFileDescriptor
import com.ultimatevideo.uveditor.engine.EngineException

/**
 * Audio playback: Oboe output, native mixer, per-clip gain, and the audio device as the master
 * clock for A/V sync. Call all methods from one thread (normally main); none of them block on
 * decoding or I/O.
 *
 * Time is in integer project frames. [positionFrame] is the frame currently being *heard*
 * (latency compensated), which is what the preview should be showing.
 */
class AudioPlaybackEngine : AutoCloseable {

    private var handle: Long = try {
        NativeAudio.nativeCreate()
    } catch (e: UnsatisfiedLinkError) {
        throw EngineException("Native engine is not available", e)
    }

    init {
        if (handle == 0L) throw EngineException("Could not create the native audio engine")
    }

    /** Opens the output stream and the decode worker. @throws AudioException on device failure. */
    fun start() = throwIfFailed(NativeAudio.nativeStart(live(), false), "start")

    /** Starts without an audio device; frames are pulled with [renderOffline] (tests, export). */
    internal fun startOffline() = throwIfFailed(NativeAudio.nativeStart(live(), true), "startOffline")

    fun stop() = NativeAudio.nativeStop(live())

    /** Registers the media for [assetKey]. The engine duplicates the descriptor; the caller keeps its own. */
    fun setAsset(assetKey: Long, fd: ParcelFileDescriptor) =
        throwIfFailed(NativeAudio.nativeSetAssetFd(live(), assetKey, fd.fd), "setAsset")

    fun removeAsset(assetKey: Long) = NativeAudio.nativeRemoveAsset(live(), assetKey)

    fun setSnapshot(snapshot: AudioSnapshot) {
        val buffer = snapshot.encode()
        throwIfFailed(NativeAudio.nativeSetSnapshot(live(), buffer, buffer.remaining()), "setSnapshot")
    }

    fun play() = NativeAudio.nativePlay(live())

    /** Freezes at the frame being heard, so [play] resumes without skipping audio. */
    fun pause() = NativeAudio.nativePause(live())

    fun seek(frame: Long) = throwIfFailed(NativeAudio.nativeSeek(live(), frame), "seek")

    /** Master clock: project frame being heard right now. */
    fun positionFrame(): Long = NativeAudio.nativePositionFrame(live())

    fun stats(): AudioStats {
        val v = NativeAudio.nativeStats(live()) ?: throw EngineException("Native audio stats unavailable")
        check(v.size >= STATS_FIELDS) { "unexpected stats size ${v.size}" }
        return AudioStats(
            sampleRate = v[0].toInt(),
            framesPerBurst = v[1].toInt(),
            bufferSizeFrames = v[2].toInt(),
            exclusive = v[3] != 0L,
            lowLatency = v[4] != 0L,
            api = v[5].toInt(),
            latencyMicros = v[6].takeIf { it >= 0 },
            xruns = v[7].toInt().takeIf { it >= 0 },
            underrunBlocks = v[8],
            deviceId = v[9].toInt(),
        )
    }

    /** Problems raised on the audio/decode threads since the last call. */
    fun pollFaults(): List<AudioFault> {
        val flat = NativeAudio.nativePollFaults(live()) ?: return emptyList()
        return (flat.indices step FAULT_STRIDE).map { i ->
            val clipKey = flat[i + 1]
            val error = AudioErrorCode.fromValue(flat[i + 2].toInt())
            when (flat[i].toInt()) {
                FAULT_UNDERRUN -> AudioFault.Underrun(clipKey, blocks = flat[i + 3])
                FAULT_DECODE -> AudioFault.Decode(clipKey, error)
                else -> AudioFault.Device(AudioErrorCode.DeviceError)
            }
        }
    }

    /** Offline mode only. Returns the playhead in samples after rendering [frames] stereo frames into [out]. */
    internal fun renderOffline(out: FloatArray, frames: Int): Long = NativeAudio.nativeRenderOffline(live(), out, frames)

    internal fun positionSamples(): Long = NativeAudio.nativePositionSamples(live())

    override fun close() {
        val h = handle
        if (h == 0L) return
        handle = 0L
        NativeAudio.nativeStop(h)
        NativeAudio.nativeDestroy(h)
    }

    private fun live(): Long {
        check(handle != 0L) { "AudioPlaybackEngine is closed" }
        return handle
    }

    private fun throwIfFailed(code: Int, what: String) {
        if (code != 0) throw AudioException(code, "$what failed: ${AudioErrorCode.fromValue(code)} ($code)")
    }

    private companion object {
        const val STATS_FIELDS = 10
        const val FAULT_STRIDE = 4
        const val FAULT_UNDERRUN = 1
        const val FAULT_DECODE = 2
    }
}
