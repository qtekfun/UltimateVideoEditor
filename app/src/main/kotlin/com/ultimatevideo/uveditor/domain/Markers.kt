package com.ultimatevideo.uveditor.domain

import kotlin.math.abs

/** A manual marker is placed by the user; a beat marker comes from beat detection and is replaced as a set. */
enum class MarkerKind { MANUAL, BEAT }

/** The colours a marker can be given; they only label it (a beat marker has none). */
enum class MarkerColor { RED, ORANGE, YELLOW, GREEN, BLUE, PURPLE }

/**
 * A point on the ruler, in project frames. Markers do not move when clips are edited around them.
 * [note] and [color] are optional labels the user writes; they travel with the project and to EDL / FCPXML.
 */
data class Marker(
    val id: String,
    val frame: FrameIndex,
    val kind: MarkerKind = MarkerKind.MANUAL,
    val note: String? = null,
    val color: MarkerColor? = null,
)

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
        return EditResult.Success(timeline.copy(markers = normalised(timeline.markers + marker)))
    }

    const val MAX_NOTE_LENGTH = 200

    /** Sets the note and colour of a marker; a blank note clears it. */
    fun annotate(timeline: Timeline, markerId: String, note: String?, color: MarkerColor?): EditResult<Timeline> {
        val marker = timeline.markers.firstOrNull { it.id == markerId } ?: return EditResult.Failure(EditError.MarkerNotFound(markerId))
        val cleaned = note?.trim()?.takeIf { it.isNotEmpty() }
        if (cleaned != null && cleaned.length > MAX_NOTE_LENGTH) {
            return EditResult.Failure(EditError.InvalidMarker("a marker note is at most $MAX_NOTE_LENGTH characters"))
        }
        if (marker.note == cleaned && marker.color == color) return EditResult.Success(timeline)
        return EditResult.Success(timeline.copy(markers = timeline.markers.map { if (it.id == markerId) it.copy(note = cleaned, color = color) else it }))
    }

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
            added += beat.copy(kind = MarkerKind.BEAT)
        }
        return EditResult.Success(timeline.copy(markers = normalised(survivors + added)))
    }

    /** The marker nearest to [frame] within [radius] frames, preferring the earlier one on a tie. */
    fun nearest(markers: List<Marker>, frame: FrameIndex, radius: Long): Marker? =
        markers.filter { abs(it.frame - frame) <= radius }.minWithOrNull(compareBy({ abs(it.frame - frame) }, { it.frame }))
}
