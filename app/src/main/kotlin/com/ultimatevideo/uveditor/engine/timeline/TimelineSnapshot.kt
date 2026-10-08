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
    /** What the block is, for its colour (wire version 8). */
    val kind: SnapshotClipKind = SnapshotClipKind.DEFAULT,
    /** The clip's embedded sound was detached: the canvas draws no waveform on it (flags bit 7, no version bump). */
    val audioDetached: Boolean = false,
    /** The clip is linked to another (a detached sound and its picture): the canvas marks it (flags bit 8). */
    val linked: Boolean = false,
)

/** What a clip block is, so the canvas can colour it; [DEFAULT] follows the lane type (video, audio, title). */
enum class SnapshotClipKind(val code: Int) { DEFAULT(0), IMAGE(1), STICKER(2), MULTICAM(3) }

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
 * A short text drawn on the block of the clip with key [clipKey] (a clip's name, a title's text, a sticker's name) or
 * of a marker. Any text: the canvas draws it from bitmaps the system font makes (accents, symbols, emoji), so it must be
 * valid UTF-16, have no control characters and fit in [MAX_BYTES] bytes of UTF-8 (see `ClipLabels`).
 */
data class SnapshotLabel(val clipKey: Long, val text: String) {
    init {
        require(text.none { it.code < 0x20 || it.code == 0x7F }) { "a label has a control character" }
        require(isWellFormed(text)) { "a label has an unpaired surrogate" }
        require(utf8Length(text) <= MAX_BYTES) { "a label is longer than $MAX_BYTES bytes of UTF-8" }
    }

    companion object {
        /** The longest label on the wire, in UTF-8 bytes (`kSnapshotMaxLabelBytesUtf8`). */
        const val MAX_BYTES = 96

        /** The most characters `ClipLabels` keeps (three UTF-8 bytes each at most for text outside emoji, so within [MAX_BYTES]). */
        const val MAX_CHARS = 32

        fun utf8Length(text: String): Int = text.toByteArray(Charsets.UTF_8).size

        fun isWellFormed(text: String): Boolean {
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (Character.isHighSurrogate(c)) {
                    if (i + 1 >= text.length || !Character.isLowSurrogate(text[i + 1])) return false
                    i += 2
                    continue
                }
                if (Character.isLowSurrogate(c)) return false
                i++
            }
            return true
        }

        /** A marker's name travels as a label under a negative key, which never clashes with a clip key; see `marker_style.h`. */
        private const val MARKER_KEY_BASE = -2L

        fun markerKey(markerIndex: Int): Long = MARKER_KEY_BASE - markerIndex

        /** The marker index a label key stands for, or null when it is a clip's key. */
        fun markerIndexOf(key: Long): Int? = if (key <= MARKER_KEY_BASE) (MARKER_KEY_BASE - key).toInt() else null
    }
}

/**
 * The sound shaping drawn on the clip with key [clipKey]: its fade ramps ([fadeInFrames], [fadeOutFrames], shaped by
 * [fadeShape] = a `FadeShape` code) and its volume curve [points]. An [editable] clip also shows the circles and dots to grab.
 * [baseDb] is the static gain, where the curve line sits while it has no points.
 */
data class SnapshotShaping(
    val clipKey: Long,
    val fadeInFrames: Long = 0,
    val fadeOutFrames: Long = 0,
    val fadeShape: Int = 0,
    val editable: Boolean = false,
    val baseDb: Float = 0f,
    val points: List<SnapshotCurvePoint> = emptyList(),
) {
    init {
        require(fadeInFrames >= 0 && fadeOutFrames >= 0) { "clip $clipKey has a negative fade" }
        require(fadeShape in 0..3) { "clip $clipKey has an invalid fade shape" }
        require(baseDb.isFinite()) { "clip $clipKey has a non-finite gain" }
        require(points.size <= MAX_POINTS) { "clip $clipKey has too many volume points" }
        require(points.zipWithNext().all { (a, b) -> a.frame < b.frame } && points.all { it.frame >= 0 && it.db.isFinite() }) {
            "clip $clipKey has unordered or invalid volume points"
        }
    }

    val flags: Int get() = fadeShape or (if (editable) EDITABLE_BIT else 0)

    companion object {
        const val EDITABLE_BIT = 4
        const val MAX_POINTS = 4096
    }
}

/** A point of a volume curve: [db] at [frame] frames after the clip's start. */
data class SnapshotCurvePoint(val frame: Long, val db: Float)

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
    /** Fades and volume curves to draw, at most one per clip (wire version 9). */
    val shaping: List<SnapshotShaping> = emptyList(),
    /**
     * Per-lane flags for the lane headers, one entry per track (or empty for none): [TRACK_MUTED] and [TRACK_SOLO] mark
     * audio lanes. They travel in the high bits of the track's type word, so there is no version bump.
     */
    val trackFlags: List<Int> = emptyList(),
) {
    init {
        require(trackFlags.isEmpty() || trackFlags.size == tracks.size) { "trackFlags must have one entry per track or none" }
        for (flags in trackFlags) require(flags in 0..(TRACK_MUTED or TRACK_SOLO)) { "unknown lane flags $flags" }
        for (marker in markers) require(marker.frame >= 0) { "a marker is before frame 0" }
        val clipKeys = clips.mapTo(HashSet()) { it.clipKey }
        for (label in labels) {
            val markerIndex = SnapshotLabel.markerIndexOf(label.clipKey)
            if (markerIndex != null) {
                require(markerIndex < markers.size) { "a label references missing marker $markerIndex" }
            } else {
                require(label.clipKey in clipKeys) { "a label references missing clip ${label.clipKey}" }
            }
        }
        require(labels.mapTo(HashSet()) { it.clipKey }.size == labels.size) { "a clip has two labels" }
        for (shape in shaping) require(shape.clipKey in clipKeys) { "sound shaping references missing clip ${shape.clipKey}" }
        require(shaping.mapTo(HashSet()) { it.clipKey }.size == shaping.size) { "a clip has two sound shapings" }
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
            LABEL_TRAILER_BYTES + labels.sumOf { LABEL_FIXED_BYTES + paddedLength(it.text) } +
            SHAPING_TRAILER_BYTES + shaping.sumOf { SHAPING_BYTES + it.points.size * SHAPING_POINT_BYTES }
        val buffer = ByteBuffer.allocateDirect(size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(MAGIC)
        buffer.putInt(VERSION)
        buffer.putInt(fpsNum)
        buffer.putInt(fpsDen)
        buffer.putInt(tracks.size)
        buffer.putInt(clips.size)
        tracks.forEachIndexed { index, type -> buffer.putInt(type.code or (trackFlags.getOrElse(index) { 0 } shl TRACK_FLAGS_SHIFT)) }
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
                (if (clip.selected) 1 else 0) or (if (clip.hasFx) 2 else 0) or (if (clip.missing) 4 else 0) or (if (clip.primary) 8 else 0) or
                    (clip.kind.code shl CLIP_KIND_SHIFT) or (if (clip.audioDetached) AUDIO_DETACHED_BIT else 0) or (if (clip.linked) LINKED_BIT else 0),
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
            val bytes = label.text.toByteArray(Charsets.UTF_8)
            buffer.putInt(bytes.size)
            buffer.put(bytes)
            repeat(paddedLength(label.text) - bytes.size) { buffer.put(0) }
        }
        buffer.putInt(shaping.size)
        for (s in shaping) {
            buffer.putLong(s.clipKey)
            buffer.putInt(s.fadeInFrames.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            buffer.putInt(s.fadeOutFrames.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            buffer.putInt(s.flags)
            buffer.putInt(s.points.size)
            buffer.putFloat(s.baseDb)
            buffer.putInt(0)
            for (p in s.points) {
                buffer.putLong(p.frame)
                buffer.putFloat(p.db)
                buffer.putInt(0)
            }
        }
        buffer.flip()
        return buffer
    }

    companion object {
        const val MAGIC = 0x53545655 // "UVTS"
        const val VERSION = 9
        const val HEADER_BYTES = 24
        const val TRACK_BYTES = 4

        /** Lane flags in [trackFlags]; on the wire they sit above the type's low byte (see `timeline_snapshot.h`). */
        const val TRACK_MUTED = 1
        const val TRACK_SOLO = 2
        const val TRACK_FLAGS_SHIFT = 8
        const val CLIP_BYTES = 56

        /** The clip kind sits in bits 4..6 of a clip's flags word (wire version 8). */
        const val CLIP_KIND_SHIFT = 4
        const val AUDIO_DETACHED_BIT = 1 shl 7
        const val LINKED_BIT = 1 shl 8

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

        /** The shaping count that follows the labels (version 9); each entry is [SHAPING_BYTES] plus [SHAPING_POINT_BYTES] per point. */
        const val SHAPING_TRAILER_BYTES = 4
        const val SHAPING_BYTES = 32
        const val SHAPING_POINT_BYTES = 16

        /** Length of [text] in UTF-8 padded with zeros to a multiple of 4, as it is written. */
        fun paddedLength(text: String): Int = (SnapshotLabel.utf8Length(text) + 3) / 4 * 4
    }
}
