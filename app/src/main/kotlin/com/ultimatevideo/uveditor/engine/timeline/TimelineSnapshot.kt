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
 * Immutable view of the timeline sent to the native canvas. Deliberately independent of the
 * editing model so the engine boundary stays a plain data contract (see SPECS.md 5.2).
 * All time values are integer frames.
 */
data class TimelineSnapshot(
    val fpsNum: Int,
    val fpsDen: Int,
    val tracks: List<SnapshotTrackType>,
    val clips: List<SnapshotClip>,
) {
    init {
        require(fpsNum > 0 && fpsDen > 0) { "fps must be positive: $fpsNum/$fpsDen" }
        for (clip in clips) {
            require(clip.trackIndex in tracks.indices) { "clip ${clip.clipKey} references missing track ${clip.trackIndex}" }
            require(clip.durationFrames > 0) { "clip ${clip.clipKey} has non-positive duration" }
            require(clip.startFrame >= 0) { "clip ${clip.clipKey} starts before frame 0" }
            require(clip.sourceFpsNum > 0 && clip.sourceFpsDen > 0) { "clip ${clip.clipKey} has an invalid source fps" }
        }
    }

    /** Encodes into a direct little-endian buffer (layout documented in timeline_snapshot.h). */
    fun encode(): ByteBuffer {
        val size = HEADER_BYTES + tracks.size * TRACK_BYTES + clips.size * CLIP_BYTES
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
        buffer.flip()
        return buffer
    }

    companion object {
        const val MAGIC = 0x53545655 // "UVTS"
        const val VERSION = 1
        const val HEADER_BYTES = 24
        const val TRACK_BYTES = 4
        const val CLIP_BYTES = 56
    }
}
