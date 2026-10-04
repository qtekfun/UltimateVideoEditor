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
    /** The clip the inspector edits: with several clips selected it is outlined brighter than the others (wire version 6). */
    val primary: Boolean = false,
    /** The clip has effects, a blend mode or a mask; the canvas marks it. */
    val hasFx: Boolean = false,
    /** The clip's media file cannot be read; the canvas tints and hatches it. */
    val missing: Boolean = false,
)

/**
 * A transition drawn across the cut at [cutFrame] on [trackIndex], from `cutFrame - preFrames` to
 * `cutFrame + postFrames`.
 */
data class SnapshotTransition(val trackIndex: Int, val cutFrame: Long, val preFrames: Long, val postFrames: Long)

/** A keyframe marker on the clip with key [clipKey], [frame] frames after the clip's start. */
data class SnapshotKeyframe(val clipKey: Long, val frame: Long)

/**
 * A clip that is not a plain 1x forward span: it uses [sourceSpanFrames] source frames from its source
 * in-point, spread over its length (so the canvas can place waveforms and thumbnails, and label the
 * speed). [reverse] plays the range backwards; [freeze] holds one frame.
 */
data class SnapshotRetime(val clipKey: Long, val sourceSpanFrames: Long, val reverse: Boolean = false, val freeze: Boolean = false)

/**
 * A short text drawn on the block of the clip with key [clipKey] (a title's text, a sticker's name). The native
 * canvas can only draw printable ASCII in its tiny font, and at most [MAX_CHARS] characters (see `ClipLabels`).
 */
data class SnapshotLabel(val clipKey: Long, val text: String) {
    init {
        require(text.length <= MAX_CHARS) { "a label is longer than $MAX_CHARS characters" }
        require(text.all { it.code in 0x20..0x7E }) { "a label has a character that is not printable ASCII" }
    }

    companion object {
        const val MAX_CHARS = 24
    }
}

/**
 * A ruler marker at timeline [frame]; [beat] marks one found by beat detection rather than placed by hand.
 * [colorCode] is 0 for none or 1..6 for red, orange, yellow, green, blue, purple (the `MarkerColor` order plus one);
 * [hasNote] draws a small note indicator. Both travel in the marker's last wire word (see `marker_style.h`).
 */
data class SnapshotMarker(
    val frame: Long,
    val beat: Boolean = false,
    val colorCode: Int = 0,
    val hasNote: Boolean = false,
) {
    init {
        require(colorCode in 0..MAX_COLOR_CODE) { "marker colour code $colorCode is out of range" }
    }

    /** The marker's style word on the wire. */
    val extra: Int get() = colorCode or (if (hasNote) NOTE_BIT else 0)

    companion object {
        const val MAX_COLOR_CODE = 6
        const val NOTE_BIT = 8
    }
}

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
    val keyframes: List<SnapshotKeyframe> = emptyList(),
    val retimes: List<SnapshotRetime> = emptyList(),
    val markers: List<SnapshotMarker> = emptyList(),
    val labels: List<SnapshotLabel> = emptyList(),
) {
    init {
        for (marker in markers) require(marker.frame >= 0) { "a marker is before frame 0" }
        val clipKeys = clips.mapTo(HashSet()) { it.clipKey }
        for (label in labels) require(label.clipKey in clipKeys) { "a label references missing clip ${label.clipKey}" }
        require(labels.mapTo(HashSet()) { it.clipKey }.size == labels.size) { "a clip has two labels" }
        for (retime in retimes) {
            require(retime.clipKey in clipKeys) { "a retime references missing clip ${retime.clipKey}" }
            require(retime.sourceSpanFrames >= 1) { "clip ${retime.clipKey} has an empty source span" }
        }
        require(retimes.mapTo(HashSet()) { it.clipKey }.size == retimes.size) { "a clip has two retimes" }
        for (keyframe in keyframes) {
            require(keyframe.clipKey in clipKeys) { "a keyframe references missing clip ${keyframe.clipKey}" }
            require(keyframe.frame >= 0) { "a keyframe is before its clip's first frame" }
        }
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
            TRAILER_BYTES + transitions.size * TRANSITION_BYTES + KEYFRAME_TRAILER_BYTES + keyframes.size * KEYFRAME_BYTES +
            RETIME_TRAILER_BYTES + retimes.size * RETIME_BYTES + MARKER_TRAILER_BYTES + markers.size * MARKER_BYTES +
            LABEL_TRAILER_BYTES + labels.sumOf { LABEL_FIXED_BYTES + paddedLength(it.text) }
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
            buffer.putInt(
                (if (clip.selected) 1 else 0) or (if (clip.hasFx) 2 else 0) or (if (clip.missing) 4 else 0) or (if (clip.primary) 8 else 0),
            )
        }
        buffer.putInt(transitions.size)
        for (transition in transitions) {
            buffer.putInt(transition.trackIndex)
            buffer.putInt(0)
            buffer.putLong(transition.cutFrame)
            buffer.putLong(transition.preFrames)
            buffer.putLong(transition.postFrames)
        }
        buffer.putInt(keyframes.size)
        for (keyframe in keyframes) {
            buffer.putLong(keyframe.clipKey)
            buffer.putLong(keyframe.frame)
        }
        buffer.putInt(retimes.size)
        for (retime in retimes) {
            buffer.putLong(retime.clipKey)
            buffer.putLong(retime.sourceSpanFrames)
            buffer.putInt((if (retime.reverse) 1 else 0) or (if (retime.freeze) 2 else 0))
            buffer.putInt(0)
        }
        buffer.putInt(markers.size)
        for (marker in markers) {
            buffer.putLong(marker.frame)
            buffer.putInt(if (marker.beat) 1 else 0)
            buffer.putInt(marker.extra)
        }
        buffer.putInt(labels.size)
        for (label in labels) {
            buffer.putLong(label.clipKey)
            buffer.putInt(label.text.length)
            for (ch in label.text) buffer.put(ch.code.toByte())
            repeat(paddedLength(label.text) - label.text.length) { buffer.put(0) }
        }
        buffer.flip()
        return buffer
    }

    companion object {
        const val MAGIC = 0x53545655 // "UVTS"
        const val VERSION = 7
        const val HEADER_BYTES = 24
        const val TRACK_BYTES = 4
        const val CLIP_BYTES = 56

        /** The transition count that follows the clips. */
        const val TRAILER_BYTES = 4
        const val TRANSITION_BYTES = 32

        /** The keyframe count that follows the transitions (version 3). */
        const val KEYFRAME_TRAILER_BYTES = 4
        const val KEYFRAME_BYTES = 16

        /** The retime count that follows the keyframes (version 4). */
        const val RETIME_TRAILER_BYTES = 4
        const val RETIME_BYTES = 24

        /** The marker count that follows the retimes (version 5). */
        const val MARKER_TRAILER_BYTES = 4
        const val MARKER_BYTES = 16

        /** The label count that follows the markers (version 7); each label is [LABEL_FIXED_BYTES] plus its text padded to 4. */
        const val LABEL_TRAILER_BYTES = 4
        const val LABEL_FIXED_BYTES = 12

        /** Length of [text] padded with zeros to a multiple of 4, as it is written. */
        fun paddedLength(text: String): Int = (text.length + 3) / 4 * 4
    }
}
