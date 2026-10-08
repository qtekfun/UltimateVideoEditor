package com.qtekfun.ultimatevideoeditor.engine.audio

import android.os.ParcelFileDescriptor
import com.qtekfun.ultimatevideoeditor.engine.EngineException
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

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

    /** Output peaks since the previous call (for the level meters); silent when nothing played. */
    fun takePeaks(): PeakLevels {
        val v = NativeAudio.nativeTakePeaks(live()) ?: return PeakLevels.SILENT
        return if (v.size >= 2) PeakLevels(v[0], v[1]) else PeakLevels.SILENT
    }

    /**
     * Integrated loudness (LUFS, ITU-R BS.1770) of [startMicros, endMicros) of a registered asset;
     * [endMicros] < 0 measures to the end. Decodes on the calling thread: call it off the main thread.
     * @throws AudioException when the media cannot be read or the measurement was cancelled.
     */
    fun measureLoudness(assetKey: Long, startMicros: Long, endMicros: Long): LoudnessResult = analysisLock.read {
        if (closing) throw EngineException("AudioPlaybackEngine is closing")
        val v = NativeAudio.nativeMeasureLoudness(live(), assetKey, startMicros, endMicros)
            ?: throw EngineException("Native loudness measurement unavailable")
        throwIfFailed(v[0].toInt(), "measureLoudness")
        LoudnessResult(lufs = v[1].takeIf { it > SILENCE_LUFS_FLOOR }, samplePeak = v[2])
    }

    /**
     * The noise profile (magnitude per STFT bin, [NOISE_PROFILE_BINS] values) of a quiet region of a
     * registered asset, for noise suppression. Off the main thread, like [measureLoudness].
     * @throws AudioException when the region is too short (about 90 ms minimum) or cannot be read.
     */
    fun measureNoiseProfile(assetKey: Long, startMicros: Long, endMicros: Long): FloatArray = analysisLock.read {
        if (closing) throw EngineException("AudioPlaybackEngine is closing")
        val v = NativeAudio.nativeMeasureNoiseProfile(live(), assetKey, startMicros, endMicros)
            ?: throw EngineException("Native noise profile measurement unavailable")
        check(v.size == NOISE_PROFILE_BINS + 1) { "unexpected noise profile size ${v.size}" }
        throwIfFailed(v[0].toInt(), "measureNoiseProfile")
        v.copyOfRange(1, v.size)
    }

    /** Stops a [measureLoudness] or [measureNoiseProfile] that is running on another thread. */
    fun cancelAnalysis() = analysisLock.read { NativeAudio.nativeCancelAnalysis(live()) }

    /** Offline mode only. Returns the playhead in samples after rendering [frames] stereo frames into [out]. */
    internal fun renderOffline(out: FloatArray, frames: Int): Long = NativeAudio.nativeRenderOffline(live(), out, frames)

    internal fun positionSamples(): Long = NativeAudio.nativePositionSamples(live())

    override fun close() {
        val h = handle
        if (h == 0L) return
        // A loudness or noise measurement may be decoding on another thread (it runs off the main thread). Freeing
        // the native engine under it would be a use-after-free, so stop it and wait for it to leave first.
        closing = true
        runCatching { NativeAudio.nativeCancelAnalysis(h) }
        analysisLock.write {
            if (handle == 0L) return
            handle = 0L
            NativeAudio.nativeStop(h)
            NativeAudio.nativeDestroy(h)
        }
    }

    /** Measurements hold the read side while they run; [close] takes the write side before it frees the engine. */
    private val analysisLock = ReentrantReadWriteLock()

    @Volatile private var closing = false

    private fun live(): Long {
        check(handle != 0L) { "AudioPlaybackEngine is closed" }
        return handle
    }

    private fun throwIfFailed(code: Int, what: String) {
        if (code != 0) throw AudioException(code, "$what failed: ${AudioErrorCode.fromValue(code)} ($code)")
    }

    companion object {
        /** STFT bins of a noise profile (1024-point frames): see `kDenoiseBins` in spectral_denoise.h. */
        const val NOISE_PROFILE_BINS = 513
        private const val SILENCE_LUFS_FLOOR = -990.0
        private const val STATS_FIELDS = 10
        private const val FAULT_STRIDE = 4
        private const val FAULT_UNDERRUN = 1
        private const val FAULT_DECODE = 2
    }
}
