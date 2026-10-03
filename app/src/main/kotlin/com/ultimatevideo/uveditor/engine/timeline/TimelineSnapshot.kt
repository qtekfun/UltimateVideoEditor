package com.ultimatevideo.uveditor.engine.timeline

import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class SnapshotTrackType(val code: Int) { VIDEO(0), AUDIO(1), TITLE(2) }

data class SnapshotClip(
    val clipKey: Long,
    val trackIndex: Int,
    /** Key used to look up waveforms; -1 when the clip has no media (titles). */
    val assetKey: Long,
    val startFrame: Long,
    val durationFrames: Long,
    val sourceInFrame: Long,
    val sourceFpsNum: Int,
    val sourceFpsDen: Int,
    val selected: Boolean = false,
)

/**
 * A transition drawn across the cut at [cutFrame] on [trackIndex], from `cutFrame - preFrames` to
 * `cutFrame + postFrames`.
 */
data class SnapshotTransition(val trackIndex: Int, val cutFrame: Long, val preFrames: Long, val postFrames: Long)

/**
 * Immutable view of the timeline sent to the native canvas. Deliberately independent of the
 * editing model so the engine boundary stays a plain data contract (see SPECS.md 5.2).
 * All time values are integer frames.
 */
data class TimelineSnapshot(
    val fpsNum: Int,
    val fpsDen: Int,
    val tracks: List<SnapshotTrackType>,
    val clips: List<SnapshotClip>,
    val transitions: List<SnapshotTransition> = emptyList(),
) {
    init {
        require(fpsNum > 0 && fpsDen > 0) { "fps must be positive: $fpsNum/$fpsDen" }
        for (clip in clips) {
            require(clip.trackIndex in tracks.indices) { "clip ${clip.clipKey} references missing track ${clip.trackIndex}" }
            require(clip.durationFrames > 0) { "clip ${clip.clipKey} has non-positive duration" }
            require(clip.startFrame >= 0) { "clip ${clip.clipKey} starts before frame 0" }
            require(clip.sourceFpsNum > 0 && clip.sourceFpsDen > 0) { "clip ${clip.clipKey} has an invalid source fps" }
        }
        for (transition in transitions) {
            require(transition.trackIndex in tracks.indices) { "a transition references missing track ${transition.trackIndex}" }
            require(transition.cutFrame >= 0 && transition.preFrames >= 0 && transition.postFrames >= 0) {
                "a transition has a negative extent"
            }
        }
    }

    /** Encodes into a direct little-endian buffer (layout documented in timeline_snapshot.h). */
    fun encode(): ByteBuffer {
        val size = HEADER_BYTES + tracks.size * TRACK_BYTES + clips.size * CLIP_BYTES +
            TRAILER_BYTES + transitions.size * TRANSITION_BYTES
        val buffer = ByteBuffer.allocateDirect(size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(MAGIC)
        buffer.putInt(VERSION)
        buffer.putInt(fpsNum)
        buffer.putInt(fpsDen)
        buffer.putInt(tracks.size)
        buffer.putInt(clips.size)
        for (type in tracks) buffer.putInt(type.code)
        for (clip in clips) {
            buffer.putLong(clip.clipKey)
            buffer.putInt(clip.trackIndex)
            buffer.putLong(clip.assetKey)
            buffer.putLong(clip.startFrame)
            buffer.putLong(clip.durationFrames)
            buffer.putLong(clip.sourceInFrame)
            buffer.putInt(clip.sourceFpsNum)
            buffer.putInt(clip.sourceFpsDen)
            buffer.putInt(if (clip.selected) 1 else 0)
        }
        buffer.putInt(transitions.size)
        for (transition in transitions) {
            buffer.putInt(transition.trackIndex)
            buffer.putInt(0)
            buffer.putLong(transition.cutFrame)
            buffer.putLong(transition.preFrames)
            buffer.putLong(transition.postFrames)
        }
        buffer.flip()
        return buffer
    }

    companion object {
        const val MAGIC = 0x53545655 // "UVTS"
        const val VERSION = 2
        const val HEADER_BYTES = 24
        const val TRACK_BYTES = 4
        const val CLIP_BYTES = 56

        /** The transition count that follows the clips. */
        const val TRAILER_BYTES = 4
        const val TRANSITION_BYTES = 32
    }
}
