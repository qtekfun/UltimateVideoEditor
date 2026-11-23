package com.qtekfun.ultimatevideoeditor.data.voice

/** Why a recording could not start or went wrong. */
enum class VoiceProblem {
    /** The microphone permission is not granted. */
    NO_PERMISSION,

    /** Another app (a call, a recorder) is using the microphone, or it cannot be opened. */
    MIC_BUSY,

    /** The phone ran out of room to write the take. */
    DISK,

    /** Anything else; the take is discarded. */
    FAILED,
}

class VoiceException(val problem: VoiceProblem, cause: Throwable? = null) : Exception(problem.name, cause)

/** A finished take: where it is and how long it lasts. */
data class VoiceRecording(val uri: String, val durationMs: Long)

/**
 * The microphone behind the voice-over, writing a take to a file of its own. An interface so the recording flow is tested without a
 * microphone; [AndroidVoiceRecorder] is the real one. Only one recording runs at a time.
 */
interface VoiceRecorder {
    /** Opens the microphone and returns once it delivers sound. @throws VoiceException */
    suspend fun start()

    /** The loudest recent sound, 0..1, for the meter; 0 when nothing is recording. */
    val level: Float

    /** How much has been recorded so far, in milliseconds. */
    val elapsedMs: Long

    /**
     * Stops adding sound to the take until [resume]: [elapsedMs] holds still and [level] reads 0. The take stays one file, so what is
     * recorded before and after the pause is contiguous. Safe to call when nothing records or it is already paused.
     */
    fun pause()

    /** Goes on adding to the same take after [pause]. */
    fun resume()

    /** Ends the recording and returns the take, or null when nothing was captured. @throws VoiceException */
    suspend fun stop(): VoiceRecording?

    /** Ends the recording and deletes the file. Safe to call when nothing is running. */
    fun cancel()
}

/** Records nothing and always fails; used where no microphone is wired (tests of other features). */
object NoVoiceRecorder : VoiceRecorder {
    override suspend fun start() = throw VoiceException(VoiceProblem.FAILED)

    override val level: Float = 0f

    override val elapsedMs: Long = 0

    override suspend fun stop(): VoiceRecording? = null

    override fun pause() = Unit

    override fun resume() = Unit

    override fun cancel() = Unit
}
