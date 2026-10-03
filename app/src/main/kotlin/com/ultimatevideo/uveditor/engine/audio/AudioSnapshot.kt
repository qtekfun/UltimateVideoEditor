package com.ultimatevideo.uveditor.engine.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One audio clip as the engine needs it. Times are integer frames: [startFrame] and
 * [durationFrames] in project frames, [sourceInFrame] in the asset's native frames at
 * [sourceFpsNum]/[sourceFpsDen].
 */
data class AudioClipSpec(
    val clipKey: Long,
    val assetKey: Long,
    val startFrame: Long,
    val durationFrames: Long,
    val sourceInFrame: Long,
    val sourceFpsNum: Int,
    val sourceFpsDen: Int,
    val gainDb: Float = 0f,
) {
    init {
        require(startFrame in 0..MAX_FRAME) { "clip $clipKey has an invalid start $startFrame" }
        require(durationFrames in 1..MAX_FRAME) { "clip $clipKey has an invalid duration $durationFrames" }
        require(sourceInFrame in 0..MAX_FRAME) { "clip $clipKey has an invalid source in-point $sourceInFrame" }
        require(sourceFpsNum > 0 && sourceFpsDen > 0) { "clip $clipKey has an invalid source fps" }
        require(gainDb.isFinite() && gainDb in MIN_GAIN_DB..MAX_GAIN_DB) { "clip $clipKey gain $gainDb dB is out of range" }
    }

    companion object {
        const val MAX_FRAME: Long = 1L shl 40
        const val MIN_GAIN_DB = -96f
        const val MAX_GAIN_DB = 24f
    }
}

/**
 * Immutable audio view of the timeline sent to the native mixer. Independent of the editing
 * model (see SPECS.md 5.2). Clips on different tracks may overlap; they are summed.
 */
data class AudioSnapshot(
    val fpsNum: Int,
    val fpsDen: Int,
    val clips: List<AudioClipSpec>,
) {
    init {
        require(fpsNum > 0 && fpsDen > 0) { "fps must be positive: $fpsNum/$fpsDen" }
        require(clips.map { it.clipKey }.toSet().size == clips.size) { "clip keys must be unique" }
    }

    /** Encodes into a direct little-endian buffer (layout documented in audio_snapshot.h). */
    fun encode(): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(HEADER_BYTES + clips.size * CLIP_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(MAGIC)
        buffer.putInt(VERSION)
        buffer.putInt(fpsNum)
        buffer.putInt(fpsDen)
        buffer.putInt(clips.size)
        for (clip in clips) {
            buffer.putLong(clip.clipKey)
            buffer.putLong(clip.assetKey)
            buffer.putLong(clip.startFrame)
            buffer.putLong(clip.durationFrames)
            buffer.putLong(clip.sourceInFrame)
            buffer.putInt(clip.sourceFpsNum)
            buffer.putInt(clip.sourceFpsDen)
            buffer.putFloat(clip.gainDb)
            buffer.putInt(0)
        }
        buffer.flip()
        return buffer
    }

    companion object {
        const val MAGIC = 0x53415655 // "UVAS"
        const val VERSION = 1
        const val HEADER_BYTES = 20
        const val CLIP_BYTES = 56
    }
}
