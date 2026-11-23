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
import kotlinx.coroutines.currentCoroutineContext
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

    /** Paused after [elapsedMs] of recording: the timeline and the microphone are both stopped, so the take holds only what was recorded. */
    data class Paused(val elapsedMs: Long) : VoiceState

    /** The take is being closed and put on the timeline. */
    data object Finishing : VoiceState

    /** The take is on the timeline; the person can undo it or record again. Goes to [Idle] by itself after a while. */
    data object Added : VoiceState

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

    /** The end of the timeline in project frames; playback stopping there ends a take. */
    fun endFrame(): Long

    fun play()

    fun pause()

    /** Moves the playhead to [frame]. */
    fun seek(frame: Long)
}

/**
 * The voice-over: asks for the microphone once, counts in, records while the timeline plays, and hands the take to the editor when it
 * ends. Compose state, tested on the JVM with a fake [VoiceRecorder]. A take can be paused: the timeline and the microphone stop
 * together, so timeline time and take time stay contiguous, and resuming goes on from the playhead position it paused at. The play
 * button of the transport pauses and resumes the take too. A recording ends, and what was recorded is kept, when the person stops it,
 * when the timeline reaches its end, when the app leaves the screen, or at [MAX_TAKE_MS] of recorded (not paused) time.
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
    /** True when the timeline's sound reaches only the person (headphones); otherwise it is silent for the take, or the microphone would record it. */
    private val privateOutput: () -> Boolean = { true },
) {
    var state: VoiceState by mutableStateOf(VoiceState.Idle)
        private set

    private var job: Job? = null
    private var addedJob: Job? = null
    private var startFrame = 0L
    private var pausedFrame = 0L
    private var leadMs = 0L
    private var startedPlayback = false
    private var seenPlaying = false

    /** True while anything is going on, so the editor knows the button is busy. */
    val active: Boolean get() = state !is VoiceState.Idle && state !is VoiceState.Failed && state !is VoiceState.Added

    /** True while a take is on and the timeline's sound must not play: from the count-in to the end, without headphones. */
    val timelineSilenced: Boolean
        get() = (state is VoiceState.Counting || state is VoiceState.Recording || state is VoiceState.Paused) && !privateOutput()

    /** True when throwing the take away should ask first: it is longer than [CONFIRM_DISCARD_MS]. */
    val discardNeedsConfirmation: Boolean
        get() = when (val s = state) {
            is VoiceState.Recording -> s.elapsedMs > CONFIRM_DISCARD_MS
            is VoiceState.Paused -> s.elapsedMs > CONFIRM_DISCARD_MS
            else -> false
        }

    /** The record button. Asks for the microphone first if it is not allowed yet. */
    fun start() {
        if (active) return
        addedJob?.cancel()
        if (state is VoiceState.Added) state = VoiceState.Idle
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
        if (current !is VoiceState.Recording && current !is VoiceState.Paused) {
            // Still counting in: nothing was recorded, so this is a cancel.
            if (current is VoiceState.Counting) cancel()
            return
        }
        finish()
    }

    /** Stops the timeline and the microphone together; [resume] goes on from the same frame and appends to the same take. */
    fun pause() {
        if (state !is VoiceState.Recording) return
        job?.cancel()
        pausedFrame = playback.playheadFrame()
        recorder.pause()
        state = VoiceState.Paused(recorder.elapsedMs)
        if (playback.isPlaying) playback.pause()
    }

    /** Continues a paused take from the playhead position it paused at, even if the playhead was moved meanwhile. */
    fun resume() {
        if (state !is VoiceState.Paused) return
        if (playback.playheadFrame() != pausedFrame) {
            if (playback.isPlaying) playback.pause()
            playback.seek(pausedFrame)
        }
        recorder.resume()
        seenPlaying = playback.isPlaying
        state = VoiceState.Recording(recorder.elapsedMs, 0f)
        if (!playback.isPlaying) playback.play()
        job = scope.launch { tickLoop() }
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
     * The timeline started or stopped playing. While recording, once it has been seen playing, a stop at the end of the timeline ends
     * the take there and any other stop (the transport's pause button) pauses it; the start itself, which the controller asked for, is
     * not mistaken for a stop, and neither is a notification that lags behind the playback it describes. While paused, the transport's
     * play button resumes the take.
     */
    fun onPlaybackChanged(playing: Boolean) {
        when (state) {
            is VoiceState.Recording -> {
                if (playing) {
                    seenPlaying = true
                } else if (seenPlaying && !playback.isPlaying) {
                    val end = playback.endFrame()
                    if (end > 0 && playback.playheadFrame() >= end) finish() else pause()
                }
            }
            is VoiceState.Paused -> if (playing) resume()
            else -> Unit
        }
    }

    /** The screen is going away: what was recorded is kept, even when paused; a count-in is dropped. */
    fun onLeaving() {
        when (state) {
            is VoiceState.Recording, is VoiceState.Paused -> finish()
            is VoiceState.Counting, VoiceState.AskingPermission -> cancel()
            else -> Unit
        }
    }

    /** The person saw the failure. */
    fun dismissFailure() {
        if (state is VoiceState.Failed) state = VoiceState.Idle
    }

    /** The editor put the take on the timeline: undo and another take are offered for a while. */
    fun onPlaced() {
        if (state != VoiceState.Idle) return
        state = VoiceState.Added
        addedJob?.cancel()
        addedJob = scope.launch {
            delay(ADDED_SHOWN_MS)
            if (state == VoiceState.Added) state = VoiceState.Idle
        }
    }

    /**
     * "Record again" after a take was added: the take just made is taken back with [undo] and a new one starts from the frame it started
     * on, so a retake replaces the take instead of stacking a second voice on another lane that would play at the same time.
     */
    fun recordAgain(undo: () -> Unit) {
        if (state != VoiceState.Added) return
        val from = startFrame
        addedJob?.cancel()
        state = VoiceState.Idle
        undo()
        playback.seek(from)
        start()
    }

    /** The person is done with the confirmation, or acted on it. */
    fun dismissAdded() {
        addedJob?.cancel()
        if (state == VoiceState.Added) state = VoiceState.Idle
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
                tickLoop()
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

    /** Keeps the clock and the meter current while recording; ends the take at [MAX_TAKE_MS] of recorded time. */
    private suspend fun tickLoop() {
        while (currentCoroutineContext().isActive) {
            delay(tickMs)
            if (playback.isPlaying) seenPlaying = true
            val elapsed = recorder.elapsedMs
            if (elapsed >= MAX_TAKE_MS) {
                finish()
                return
            }
            state = VoiceState.Recording(elapsed, recorder.level)
        }
    }

    private fun finish() {
        if (state !is VoiceState.Recording && state !is VoiceState.Paused) return
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

        /** Discarding a take longer than this asks first. */
        const val CONFIRM_DISCARD_MS = 3_000L

        /** How long the "added" confirmation stays offered. */
        const val ADDED_SHOWN_MS = 10_000L
    }
}
