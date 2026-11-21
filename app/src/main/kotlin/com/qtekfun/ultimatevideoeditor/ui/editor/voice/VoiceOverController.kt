package com.qtekfun.ultimatevideoeditor.ui.editor.voice

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.qtekfun.ultimatevideoeditor.data.voice.VoiceException
import com.qtekfun.ultimatevideoeditor.data.voice.VoiceProblem
import com.qtekfun.ultimatevideoeditor.data.voice.VoiceRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What the voice-over is doing. */
sealed interface VoiceState {
    data object Idle : VoiceState

    /** Waiting for the permission the person was just asked for. */
    data object AskingPermission : VoiceState

    /** The count-in before recording: [secondsLeft] to go (3, 2, 1). */
    data class Counting(val secondsLeft: Int) : VoiceState

    /** Recording: how long, and how loud (0..1). */
    data class Recording(val elapsedMs: Long, val level: Float) : VoiceState

    /** The take is being closed and put on the timeline. */
    data object Finishing : VoiceState

    /** The last attempt failed; [Idle] again after the person acknowledges it. */
    data class Failed(val problem: VoiceProblem) : VoiceState
}

/** A take to put on the timeline: [startFrame] is where the timeline started to play, [trimMs] how much of the file's start to cut. */
data class VoiceTake(val uri: String, val startFrame: Long, val trimMs: Long)

/** What the controller needs from the editor's playback. */
interface VoicePlayback {
    val isPlaying: Boolean

    /** The playhead in project frames. */
    fun playheadFrame(): Long

    fun play()

    fun pause()
}

/**
 * The voice-over: asks for the microphone once, counts in, records while the timeline plays, and hands the take to the editor when it
 * ends. Compose state, tested on the JVM with a fake [VoiceRecorder]. A recording ends when the person stops it, when the timeline
 * stops by itself (its end, or the play button), when the app leaves the screen, or at [MAX_TAKE_MS]; what was recorded is then kept.
 */
@Stable
class VoiceOverController(
    private val scope: CoroutineScope,
    private val recorder: VoiceRecorder,
    private val playback: VoicePlayback,
    private val hasPermission: () -> Boolean,
    private val requestPermission: () -> Unit,
    private val onTake: (VoiceTake) -> Unit,
    private val now: () -> Long,
    private val countInSeconds: Int = 3,
    private val tickMs: Long = 50,
    /** What the phone adds between the voice and the file, in milliseconds; 0 until it is measured on a device. */
    private val latencyMs: Long = 0,
) {
    var state: VoiceState by mutableStateOf(VoiceState.Idle)
        private set

    private var job: Job? = null
    private var startFrame = 0L
    private var leadMs = 0L
    private var startedPlayback = false
    private var seenPlaying = false

    /** True while anything is going on, so the editor knows the button is busy. */
    val active: Boolean get() = state !is VoiceState.Idle && state !is VoiceState.Failed

    /** The record button. Asks for the microphone first if it is not allowed yet. */
    fun start() {
        if (active) return
        if (!hasPermission()) {
            state = VoiceState.AskingPermission
            requestPermission()
            return
        }
        begin()
    }

    /** The answer to the permission question. */
    fun onPermissionResult(granted: Boolean) {
        if (state != VoiceState.AskingPermission) return
        if (granted) begin() else state = VoiceState.Failed(VoiceProblem.NO_PERMISSION)
    }

    /** Stops and keeps the take. */
    fun stop() {
        val current = state
        if (current !is VoiceState.Recording) {
            // Still counting in: nothing was recorded, so this is a cancel.
            if (current is VoiceState.Counting) cancel()
            return
        }
        finish()
    }

    /** Stops and throws the take away. */
    fun cancel() {
        job?.cancel()
        recorder.cancel()
        if (startedPlayback) playback.pause()
        startedPlayback = false
        state = VoiceState.Idle
    }

    /**
     * The timeline started or stopped playing. While recording, once it has been seen playing, a stop (its end, or the play button) ends
     * the take there; the start itself, which the controller asked for, is not mistaken for a stop.
     */
    fun onPlaybackChanged(playing: Boolean) {
        if (state !is VoiceState.Recording) return
        if (playing) seenPlaying = true else if (seenPlaying) finish()
    }

    /** The screen is going away: what was recorded is kept, a count-in is dropped. */
    fun onLeaving() {
        when (state) {
            is VoiceState.Recording -> finish()
            is VoiceState.Counting, VoiceState.AskingPermission -> cancel()
            else -> Unit
        }
    }

    /** The person saw the failure. */
    fun dismissFailure() {
        if (state is VoiceState.Failed) state = VoiceState.Idle
    }

    private fun begin() {
        if (playback.isPlaying) playback.pause()
        job = scope.launch {
            try {
                for (left in countInSeconds downTo 1) {
                    state = VoiceState.Counting(left)
                    delay(1000)
                }
                val micStarted = now()
                recorder.start()
                // The microphone is running; the timeline starts now. What the microphone heard before this is not part of the take's time.
                startFrame = playback.playheadFrame()
                leadMs = (now() - micStarted).coerceAtLeast(0)
                seenPlaying = false
                playback.play()
                startedPlayback = true
                state = VoiceState.Recording(0, 0f)
                while (isActive) {
                    delay(tickMs)
                    val elapsed = recorder.elapsedMs
                    if (elapsed >= MAX_TAKE_MS) {
                        finish()
                        return@launch
                    }
                    state = VoiceState.Recording(elapsed, recorder.level)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: VoiceException) {
                recorder.cancel()
                if (startedPlayback) playback.pause()
                startedPlayback = false
                state = VoiceState.Failed(e.problem)
            }
        }
    }

    private fun finish() {
        if (state !is VoiceState.Recording) return
        job?.cancel()
        state = VoiceState.Finishing
        playback.pause()
        startedPlayback = false
        scope.launch {
            try {
                val take = recorder.stop()
                state = VoiceState.Idle
                if (take != null && take.durationMs > 0) onTake(VoiceTake(take.uri, startFrame, leadMs + latencyMs))
            } catch (e: CancellationException) {
                throw e
            } catch (e: VoiceException) {
                state = VoiceState.Failed(e.problem)
            }
        }
    }

    companion object {
        /** A take ends by itself after this long: a safeguard for the phone's storage and for a take someone forgot. */
        const val MAX_TAKE_MS = 20L * 60 * 1000
    }
}
