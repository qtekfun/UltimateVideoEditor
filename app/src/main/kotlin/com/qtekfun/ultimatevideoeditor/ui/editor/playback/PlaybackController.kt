package com.qtekfun.ultimatevideoeditor.ui.editor.playback

import com.qtekfun.ultimatevideoeditor.R
import com.qtekfun.ultimatevideoeditor.domain.FrameIndex
import com.qtekfun.ultimatevideoeditor.ui.editor.*
import com.qtekfun.ultimatevideoeditor.ui.text.UiText
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The transport: play, pause, seek and the playhead. The audio device is the master clock when [playbackOutput] is set; the system
 * clock ([nanoClock]) is the fallback. Everything that moves the playhead or must stop playback before editing goes through here.
 */
internal class PlaybackController(
    host: EditorHost,
    private val nanoClock: () -> Long,
    private val audioFocus: PlaybackAudioFocus,
) : EditorHost by host {

    private var playJob: Job? = null

    /** Set by the screen once the audio engine is up. Until then the transport uses the system clock. */
    var playbackOutput: PlaybackOutput? = null

    fun setPlayhead(frame: Long) {
        val clamped = frame.coerceAtLeast(0)
        reduce { copy(playhead = FrameIndex(clamped)) }
        if (!state.value.isPlaying) playbackOutput?.seek(clamped)
    }

    /**
     * Moves the playhead in real time while playing. This is only the transport clock: it does not
     * drive video or audio output yet. Elapsed time is converted to frames with integer math.
     */
    fun togglePlay() {
        if (state.value.isPlaying) pausePlayback() else startPlayback()
    }

    private fun timelineEnd(): Long = history.timeline.tracks.maxOfOrNull { it.end }?.value ?: 0

    private fun startPlayback() {
        val end = timelineEnd()
        if (end <= 0) {
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_add_a_clip_to_play)))
            return
        }
        val fps = state.value.fps
        val from = state.value.playhead.value.takeIf { it < end } ?: 0
        val startedAt = nanoClock()
        reduce { copy(isPlaying = true, playhead = FrameIndex(from)) }
        audioFocus.request(onLost = ::pausePlayback)
        playbackOutput?.play(from)
        playJob?.cancel()
        playJob = scope.launch {
            while (true) {
                delay(PLAY_TICK_MILLIS)
                // The audio device is the master clock; the system clock is the fallback. Never
                // go backwards: the heard position can lag the start frame by the device latency.
                val heard = playbackOutput?.heardFrame()
                val elapsedMicros = (nanoClock() - startedAt) / NANOS_PER_MICRO
                val frame = maxOf(from, heard ?: (from + fps.microsToFrames(elapsedMicros)))
                // Re-read the end each tick: the timeline can be edited while playing.
                val currentEnd = timelineEnd()
                if (frame >= currentEnd) {
                    reduce { copy(playhead = FrameIndex(currentEnd), isPlaying = false) }
                    playbackOutput?.pause()
                    audioFocus.abandon()
                    return@launch
                }
                reduce { copy(playhead = FrameIndex(frame)) }
            }
        }
    }

    /** Clip starts and ends on every track, plus the timeline start. */
    private fun editPoints(): List<Long> = buildList {
        add(0L)
        for (track in history.timeline.tracks) {
            for (clip in track.clips) {
                add(clip.timelineStart.value)
                add(clip.timelineEnd.value)
            }
        }
        if (state.value.snapToMarkers) history.timeline.markers.forEach { add(it.frame.value) }
    }

    fun previousEditPoint(): Long = editPoints().filter { it < state.value.playhead.value }.maxOrNull() ?: 0

    fun nextEditPoint(): Long {
        val playhead = state.value.playhead.value
        return editPoints().filter { it > playhead }.minOrNull() ?: playhead
    }

    fun seekTo(frame: Long) {
        val wasPlaying = state.value.isPlaying
        pausePlayback()
        setPlayhead(frame)
        if (wasPlaying) startPlayback()
    }

    /**
     * Go to a clip boundary: playback stops at that moment (it does not resume, unlike [seekTo]) and the playhead and the
     * output land exactly on [frame]. Pressing it with no boundary left in that direction still stops playback.
     */
    fun jumpTo(frame: Long) {
        pausePlayback()
        setPlayhead(frame)
    }

    /**
     * The user swiped the timeline while it was playing: stop where the playhead is on screen (no jump to the audio clock,
     * which can be a frame or two ahead of the last tick) and park the output there, so the preview re-anchors on that frame.
     * Playing again is the user's Play press. Nothing happens when paused.
     */
    fun scrubStarted() {
        if (!state.value.isPlaying) return
        pausePlayback()
        playbackOutput?.seek(state.value.playhead.value)
    }

    fun pausePlayback() {
        playJob?.cancel()
        playJob = null
        if (state.value.isPlaying) {
            reduce { copy(isPlaying = false) }
            playbackOutput?.pause()
        }
        audioFocus.abandon()
    }
}
