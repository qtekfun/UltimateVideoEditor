package com.qtekfun.ultimatevideoeditor.domain

import kotlin.math.abs

/** A manual marker is placed by the user; a beat marker comes from beat detection and is replaced as a set. */
enum class MarkerKind { MANUAL, BEAT }

/** The colours a marker can be given; they only label it (a beat marker has none). */
enum class MarkerColor { RED, ORANGE, YELLOW, GREEN, BLUE, PURPLE }

/**
 * A point on the ruler, in project frames. A free marker (no [anchorClipId]) stays at its frame when clips are edited around it.
 * An anchored marker sticks to a clip: [offsetFrames] counts from the clip's timeline start and [frame] is always
 * `clip.timelineStart + offsetFrames` (kept in step by [MarkerAnchors.settle] after every edit, so the native side,
 * snapping, navigation and the exporters only ever read [frame]). See [MarkerAnchors].
 * [name], [note] and [color] are optional labels the user writes; they travel with the project and to EDL / FCPXML.
 * The name is short and is drawn on the ruler when there is room; the note is longer free text.
 */
data class Marker(
    val id: String,
    val frame: FrameIndex,
    val kind: MarkerKind = MarkerKind.MANUAL,
    val note: String? = null,
    val color: MarkerColor? = null,
    val name: String? = null,
    val anchorClipId: String? = null,
    val offsetFrames: Long = 0,
) {
    val isAnchored: Boolean get() = anchorClipId != null
}

/** Pure marker edits. Frames are unique across all markers and the list is always sorted by frame. */
object MarkerOps {

    fun violations(markers: List<Marker>): List<String> {
        val violations = mutableListOf<String>()
        val ids = mutableSetOf<String>()
        var previous: Marker? = null
        for (marker in markers) {
            if (!ids.add(marker.id)) violations += "duplicate marker id ${marker.id}"
            if (marker.frame < FrameIndex.ZERO) violations += "marker ${marker.id} is before frame 0"
            previous?.let {
                if (marker.frame < it.frame) violations += "markers are not sorted at ${marker.id}"
                if (marker.frame == it.frame) violations += "markers ${it.id} and ${marker.id} share a frame"
            }
            previous = marker
        }
        return violations
    }

    private fun normalised(markers: List<Marker>): List<Marker> = markers.sortedWith(compareBy({ it.frame }, { it.id }))

    fun add(timeline: Timeline, marker: Marker): EditResult<Timeline> {
        if (marker.frame < FrameIndex.ZERO) return EditResult.Failure(EditError.InvalidMarker("a marker cannot be before frame 0"))
        if (timeline.markers.any { it.id == marker.id }) return EditResult.Failure(EditError.InvalidMarker("duplicate marker id ${marker.id}"))
        if (timeline.markers.any { it.frame == marker.frame }) {
            return EditResult.Failure(EditError.InvalidMarker("there is already a marker at frame ${marker.frame.value}"))
        }
        MarkerAnchors.problem(timeline, marker)?.let { return EditResult.Failure(EditError.InvalidMarker(it)) }
        return EditResult.Success(timeline.copy(markers = normalised(timeline.markers + marker)))
    }

    const val MAX_NOTE_LENGTH = 200
    const val MAX_NAME_LENGTH = 40

    /** Sets the note and colour of a marker; a blank note clears it. The name is left as it is. */
    fun annotate(timeline: Timeline, markerId: String, note: String?, color: MarkerColor?): EditResult<Timeline> {
        val marker = timeline.markers.firstOrNull { it.id == markerId } ?: return EditResult.Failure(EditError.MarkerNotFound(markerId))
        return update(timeline, markerId, marker.name, note, color)
    }

    /** Sets the name, note and colour of a marker in one step; a blank name or note clears it. */
    fun update(timeline: Timeline, markerId: String, name: String?, note: String?, color: MarkerColor?): EditResult<Timeline> {
        val marker = timeline.markers.firstOrNull { it.id == markerId } ?: return EditResult.Failure(EditError.MarkerNotFound(markerId))
        val cleanedNote = note?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanedNote != null && cleanedNote.length > MAX_NOTE_LENGTH) {
            return EditResult.Failure(EditError.InvalidMarker("a marker note is at most $MAX_NOTE_LENGTH characters"))
        }
        val cleanedName = name?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanedName != null && cleanedName.length > MAX_NAME_LENGTH) {
            return EditResult.Failure(EditError.InvalidMarker("a marker name is at most $MAX_NAME_LENGTH characters"))
        }
        if (marker.name == cleanedName && marker.note == cleanedNote && marker.color == color) return EditResult.Success(timeline)
        return EditResult.Success(
            timeline.copy(markers = timeline.markers.map { if (it.id == markerId) it.copy(name = cleanedName, note = cleanedNote, color = color) else it }),
        )
    }

    /**
     * Moves a marker to [frame]. Frames stay unique: landing on another marker's frame is refused. An anchored marker is
     * anchored again at [frame] by the creation rule (base clip, else topmost overlay clip); with no clip there it becomes free.
     */
    fun move(timeline: Timeline, markerId: String, frame: FrameIndex): EditResult<Timeline> {
        val marker = timeline.markers.firstOrNull { it.id == markerId } ?: return EditResult.Failure(EditError.MarkerNotFound(markerId))
        if (frame < FrameIndex.ZERO) return EditResult.Failure(EditError.InvalidMarker("a marker cannot be before frame 0"))
        val moved = if (marker.isAnchored) MarkerAnchors.anchoredAt(timeline, marker.copy(frame = frame)) else marker.copy(frame = frame)
        if (moved == marker) return EditResult.Success(timeline)
        if (timeline.markers.any { it.id != markerId && it.frame == frame }) {
            return EditResult.Failure(EditError.InvalidMarker("there is already a marker at frame ${frame.value}"))
        }
        return EditResult.Success(timeline.copy(markers = normalised(timeline.markers.map { if (it.id == markerId) moved else it })))
    }

    /** The last marker strictly before [frame], or null. [markers] must be sorted by frame. */
    fun previous(markers: List<Marker>, frame: FrameIndex): Marker? = markers.lastOrNull { it.frame < frame }

    /** The first marker strictly after [frame], or null. [markers] must be sorted by frame. */
    fun next(markers: List<Marker>, frame: FrameIndex): Marker? = markers.firstOrNull { it.frame > frame }

    fun remove(timeline: Timeline, markerId: String): EditResult<Timeline> {
        if (timeline.markers.none { it.id == markerId }) return EditResult.Failure(EditError.MarkerNotFound(markerId))
        return EditResult.Success(timeline.copy(markers = timeline.markers.filter { it.id != markerId }))
    }

    /**
     * Replaces the beat markers inside [from, until) with [beats] (kinds are forced to [MarkerKind.BEAT]).
     * Manual markers and beats outside the range stay, and a new beat that lands on an existing
     * marker's frame is dropped. With an empty list the range's beats are cleared. By default the
     * range is the whole timeline.
     */
    fun setBeats(
        timeline: Timeline,
        beats: List<Marker>,
        from: FrameIndex = FrameIndex.ZERO,
        until: FrameIndex = FrameIndex(Long.MAX_VALUE),
    ): EditResult<Timeline> {
        val survivors = timeline.markers.filterNot { it.kind == MarkerKind.BEAT && it.frame >= from && it.frame < until }
        val takenFrames = survivors.mapTo(HashSet()) { it.frame }
        val ids = survivors.mapTo(HashSet()) { it.id }
        val added = mutableListOf<Marker>()
        for (beat in beats) {
            if (beat.frame < FrameIndex.ZERO) return EditResult.Failure(EditError.InvalidMarker("a beat cannot be before frame 0"))
            if (!takenFrames.add(beat.frame)) continue
            if (!ids.add(beat.id)) return EditResult.Failure(EditError.InvalidMarker("duplicate marker id ${beat.id}"))
            // Beats come from audio analysis and are replaced as a set, so they are always free.
            added += MarkerAnchors.free(beat).copy(kind = MarkerKind.BEAT)
        }
        return EditResult.Success(timeline.copy(markers = normalised(survivors + added)))
    }

    /**
     * Sticks the marker to the clip under its frame ([stick]) or frees it. Sticking needs a video clip at the marker's frame
     * (the base track's first, else the topmost overlay's); a beat marker is always free.
     */
    fun setStick(timeline: Timeline, markerId: String, stick: Boolean): EditResult<Timeline> {
        val marker = timeline.markers.firstOrNull { it.id == markerId } ?: return EditResult.Failure(EditError.MarkerNotFound(markerId))
        val changed = if (stick) {
            if (marker.kind == MarkerKind.BEAT) return EditResult.Failure(EditError.InvalidMarker("a beat marker cannot stick to a clip"))
            val anchored = MarkerAnchors.anchoredAt(timeline, marker)
            if (!anchored.isAnchored) return EditResult.Failure(EditError.InvalidMarker("there is no video clip at frame ${marker.frame.value} to stick to"))
            anchored
        } else {
            MarkerAnchors.free(marker)
        }
        if (changed == marker) return EditResult.Success(timeline)
        return EditResult.Success(timeline.copy(markers = timeline.markers.map { if (it.id == markerId) changed else it }))
    }

    /** The marker nearest to [frame] within [radius] frames, preferring the earlier one on a tie. */
    fun nearest(markers: List<Marker>, frame: FrameIndex, radius: Long): Marker? =
        markers.filter { abs(it.frame - frame) <= radius }.minWithOrNull(compareBy({ abs(it.frame - frame) }, { it.frame }))
}
