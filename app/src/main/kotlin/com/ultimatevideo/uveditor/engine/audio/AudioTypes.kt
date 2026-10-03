package com.ultimatevideo.uveditor.engine.audio

/** Native result codes. Values 1-8 mirror `uv::core::Status`; 100 is `uv::audio::kResultDeviceError`. */
enum class AudioErrorCode(val value: Int) {
    InvalidArgument(1),
    BadSnapshot(2),
    IoError(3),
    UnsupportedFormat(4),
    CodecError(5),
    NotInitialized(7),
    Cancelled(8),
    DeviceError(100),
    Unknown(-1),
    ;

    companion object {
        fun fromValue(value: Int): AudioErrorCode = entries.firstOrNull { it.value == value } ?: Unknown
    }
}

/** Failure reported synchronously by an audio engine call. */
class AudioException(val code: Int, message: String) : Exception(message) {
    val errorCode: AudioErrorCode get() = AudioErrorCode.fromValue(code)
}

/** Asynchronous problem raised on the audio or decode threads, delivered by polling. */
sealed interface AudioFault {
    /** [blocks] audio blocks were short of data since the last poll; [clipKey] is the last offender. */
    data class Underrun(val clipKey: Long, val blocks: Long) : AudioFault

    /** A clip could not be decoded; it plays silence until the playhead jumps back to retry. */
    data class Decode(val clipKey: Long, val error: AudioErrorCode) : AudioFault

    /** The output device failed or disconnected (the engine tries to reopen it). */
    data class Device(val error: AudioErrorCode) : AudioFault
}

data class AudioStats(
    val sampleRate: Int,
    val framesPerBurst: Int,
    val bufferSizeFrames: Int,
    val exclusive: Boolean,
    val lowLatency: Boolean,
    /** `oboe::AudioApi`: 0 unspecified, 1 OpenSL ES, 2 AAudio. */
    val api: Int,
    /** Estimated output latency in microseconds, null until the hardware reports a timestamp. */
    val latencyMicros: Long?,
    /** Device-side underruns, null when unsupported. */
    val xruns: Int?,
    /** Blocks where a clip's decoded data was not ready in time. */
    val underrunBlocks: Long,
    val deviceId: Int,
)
