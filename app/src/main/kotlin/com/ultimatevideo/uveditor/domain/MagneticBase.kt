package com.ultimatevideo.uveditor.domain

/**
 * Edits that treat the base track as a magnetic spine, LumaFusion style.
 *
 * The base track (see [ClipDeletion.baseTrack]) is contiguous from frame 0: no operation here can
 * leave a gap in it. Every other track is free-form but follows the base's time, so inserting,
 * reordering or trimming on the base moves or cuts the overlays that sat over the affected footage.
 * Edits that start on an overlay never touch the base.
 *
 * Rules, in short:
 * - Insert: the clip goes at the nearest clip boundary to the requested frame; later base clips and
 *   overlays starting at or after that boundary shift right. Overlays crossing it stay put.
 * - Reorder: overlays follow the footage they were over. Overlays are cut where the footage they
 *   sit on moved by different amounts and each piece travels with its footage.
 * - Trim: the following base clips ripple. Overlays after the trim point shift by the same delta;
 *   frames removed from the base are removed from overlays like a deletion.
 */
object MagneticBase {

    /** Reasons the base track is not a gap-free run starting at frame 0; empty when it is. */
    fun baseViolations(timeline: Timeline): List<String> {
        val base = ClipDeletion.baseTrack(timeline) ?: return emptyList()
        val violations = mutableListOf<String>()
        var cursor = FrameIndex.ZERO
        for (clip in base.clips) {
            if (clip.timelineStart != cursor) violations += "base clip ${clip.id} starts at ${clip.timelineStart.value}, expected ${cursor.value}"
            cursor = clip.timelineEnd
        }
        return violations
    }

    /**
     * Closes every gap in the base track (including a leading one). Frames of overlays that sat over
     * a gap are removed like a deletion so the overlays stay consistent with the shortened base.
     */
    fun closeGaps(timeline: Timeline): EditResult<Timeline> {
        val base = ClipDeletion.baseTrack(timeline) ?: return EditResult.Success(timeline)
        val gaps = mutableListOf<Pair<FrameIndex, FrameIndex>>()
        var cursor = FrameIndex.ZERO
        for (clip in base.clips) {
            if (clip.timelineStart > cursor) gaps += cursor to clip.timelineStart
            cursor = clip.timelineEnd
        }
        var current = timeline
        for ((from, to) in gaps.asReversed()) {
            current = when (val cut = removeFromOverlays(current, base.id, from, to)) {
                is EditResult.Success -> cut.value
                is EditResult.Failure -> return cut
            }
            current = shiftStartingAt(current, { it.id == base.id }, to, -(to - from))
        }
        return EditResult.Success(current.pruned())
    }

    /** Inserts [clip] into the base track at the clip boundary nearest [at], rippling everything after it. */
    fun insert(timeline: Timeline, clip: Clip, at: FrameIndex): EditResult<Timeline> {
        if (ClipDeletion.baseTrack(timeline) == null) return failure(EditError.NoBaseTrack)
        if (timeline.trackOfClip(clip.id) != null) return failure(EditError.DuplicateClipId(clip.id))
        if (clip.title != null) return failure(EditError.TrackTypeMismatch(clip.id, checkNotNull(ClipDeletion.baseTrack(timeline)).id))
        if (clip.durationFrames <= 0) return failure(EditError.InvalidClip("clip must have a positive duration"))
        val current = when (val closed = closeGaps(timeline)) {
            is EditResult.Success -> closed.value
            is EditResult.Failure -> return closed
        }
        val base = checkNotNull(ClipDeletion.baseTrack(current))
        val point = boundaryNear(base, at)
        var result = shiftStartingAt(current, { true }, point, clip.durationFrames)
        val shiftedBase = checkNotNull(result.track(base.id))
        result = result.withTrack(shiftedBase.withClips(shiftedBase.clips + clip.copy(timelineStart = point)))
        return EditResult.Success(result.pruned())
    }

    /**
     * Moves a clip. Within the base this is a magnetic reorder (the clip lands in the slot its
     * centre is over, never leaving a gap). From an overlay onto the base it is an insert. Between
     * overlays it is the free-form [TimelineOps.move]. A base clip cannot be moved to another track.
     */
    fun move(
        timeline: Timeline,
        clipId: String,
        newStart: FrameIndex,
        toTrackId: String? = null,
        snap: Snap? = null,
    ): EditResult<Timeline> {
        val source = timeline.trackOfClip(clipId) ?: return failure(EditError.ClipNotFound(clipId))
        val base = ClipDeletion.baseTrack(timeline)
        val destinationId = toTrackId ?: source.id
        if (base == null || (source.id != base.id && destinationId != base.id)) {
            return TimelineOps.move(timeline, clipId, newStart, toTrackId, snap)
        }
        if (newStart < FrameIndex.ZERO) return failure(EditError.NegativeStart)
        if (source.id == base.id) {
            if (destinationId != base.id) return failure(EditError.BaseClipCannotLeave(clipId))
            return reorder(timeline, clipId, newStart)
        }
        if (source.type != TrackType.VIDEO) return failure(EditError.TrackTypeMismatch(clipId, base.id))
        val clip = checkNotNull(source.clip(clipId))
        val without = timeline.withTrack(source.withClips(source.clips - clip))
        return insert(without, clip, newStart)
    }

    /** Trims a clip edge. On the base the following clips ripple and overlays follow; elsewhere it is [TimelineOps.trim]. */
    fun trim(
        timeline: Timeline,
        clipId: String,
        edge: TrimEdge,
        frame: FrameIndex,
        sourceLength: Long? = null,
    ): EditResult<Timeline> {
        val track = timeline.trackOfClip(clipId) ?: return failure(EditError.ClipNotFound(clipId))
        val base = ClipDeletion.baseTrack(timeline)
        if (base == null || track.id != base.id) return TimelineOps.trim(timeline, clipId, edge, frame, sourceLength)

        val current = when (val closed = closeGaps(timeline)) {
            is EditResult.Success -> closed.value
            is EditResult.Failure -> return closed
        }
        val currentBase = checkNotNull(current.track(base.id))
        val clip = checkNotNull(currentBase.clip(clipId))
        // Trim on a copy of the track holding only this clip: neighbours are handled by the ripple.
        val isolated = current.withTrack(currentBase.withClips(listOf(clip)))
        val trimmed = when (val result = TimelineOps.trim(isolated, clipId, edge, frame, sourceLength)) {
            is EditResult.Success -> checkNotNull(result.value.trackOfClip(clipId)?.clip(clipId))
            is EditResult.Failure -> return result
        }
        val oldStart = clip.timelineStart
        val oldEnd = clip.timelineEnd
        // The clip keeps its start: magnetic base, so only its length changes.
        val placed = trimmed.copy(timelineStart = oldStart)
        val delta = placed.timelineEnd - oldEnd
        if (delta == 0L) return EditResult.Success(timeline)

        var result = current
        if (delta < 0) {
            // Shortened: the removed frames are [removedFrom, removedTo) on the base's timeline.
            val removedFrom = if (edge == TrimEdge.END) placed.timelineEnd else oldStart
            val removedTo = removedFrom + (-delta)
            result = when (val cut = removeFromOverlays(result, base.id, removedFrom, removedTo)) {
                is EditResult.Success -> cut.value
                is EditResult.Failure -> return cut
            }
            result = shiftStartingAt(result, { it.id == base.id }, oldEnd, delta)
        } else {
            // Lengthened: the new frames open up at the end, or in front of the old footage.
            val at = if (edge == TrimEdge.END) oldEnd else oldStart
            result = shiftStartingAt(result, { it.id != base.id }, at, delta)
            result = shiftStartingAt(result, { it.id == base.id }, oldEnd, delta)
        }
        val updated = checkNotNull(result.track(base.id))
        return EditResult.Success(result.withTrack(updated.withClips(updated.clips.map { if (it.id == clipId) placed else it })).pruned())
    }

    /**
     * Changes a clip's speed with ripple. On the base the following base clips shift by the change in
     * length and the overlays follow like they do for a trim of its end: a shorter clip removes the
     * freed frames from every other track, a longer one opens the same room after it. Elsewhere it is
     * [TimelineOps.setSpeed] with ripple on that lane only.
     */
    fun setSpeed(timeline: Timeline, clipId: String, num: Long, den: Long): EditResult<Timeline> {
        val track = timeline.trackOfClip(clipId) ?: return failure(EditError.ClipNotFound(clipId))
        val base = ClipDeletion.baseTrack(timeline)
        if (base == null || track.id != base.id) return TimelineOps.setSpeed(timeline, clipId, num, den, ripple = true)
        val before = checkNotNull(track.clip(clipId))
        var result = when (val retimed = TimelineOps.setSpeed(timeline, clipId, num, den, ripple = true)) {
            is EditResult.Success -> retimed.value
            is EditResult.Failure -> return retimed
        }
        val after = checkNotNull(result.trackOfClip(clipId)?.clip(clipId))
        val delta = after.durationFrames - before.durationFrames
        if (delta == 0L) return EditResult.Success(result)
        result = if (delta < 0) {
            when (val cut = removeFromOverlays(result, base.id, after.timelineEnd, before.timelineEnd)) {
                is EditResult.Success -> cut.value
                is EditResult.Failure -> return cut
            }
        } else {
            shiftStartingAt(result, { it.id != base.id }, before.timelineEnd, delta)
        }
        return EditResult.Success(result.pruned())
    }

    // region reorder

    private fun reorder(timeline: Timeline, clipId: String, newStart: FrameIndex): EditResult<Timeline> {
        val current = when (val closed = closeGaps(timeline)) {
            is EditResult.Success -> closed.value
            is EditResult.Failure -> return closed
        }
        val base = checkNotNull(ClipDeletion.baseTrack(current))
        val clip = checkNotNull(base.clip(clipId))
        val others = base.clips.filter { it.id != clipId }
        val centre = newStart.value + clip.durationFrames / 2
        val slot = others.count { it.timelineStart.value + it.durationFrames / 2 < centre }
        if (slot == base.clips.indexOf(clip)) return EditResult.Success(timeline)

        val order = others.toMutableList().apply { add(slot, clip) }
        var cursor = FrameIndex.ZERO
        val segments = mutableListOf<Segment>()
        val newStarts = mutableMapOf<String, FrameIndex>()
        for (member in order) {
            newStarts[member.id] = cursor
            cursor += member.durationFrames
        }
        for (member in base.clips) {
            segments += Segment(member.timelineStart, member.timelineEnd, newStarts.getValue(member.id) - member.timelineStart)
        }

        var result = current
        // Cut overlays where the footage under them moves by different amounts, then move each piece.
        for (track in current.tracks.filter { it.id != base.id }) {
            result = when (val cut = cutAtSegmentChanges(result, track.id, segments)) {
                is EditResult.Success -> cut.value
                is EditResult.Failure -> return cut
            }
        }
        for (track in result.tracks.filter { it.id != base.id }) {
            val moved = track.clips.map { it.copy(timelineStart = it.timelineStart + deltaAt(segments, it.timelineStart)) }
            result = result.withTrack(track.withClips(moved))
        }
        val reordered = base.clips.map { it.copy(timelineStart = newStarts.getValue(it.id)) }
        return EditResult.Success(result.withTrack(base.withClips(reordered)).pruned())
    }

    private class Segment(val start: FrameIndex, val end: FrameIndex, val delta: Long)

    private fun deltaAt(segments: List<Segment>, frame: FrameIndex): Long =
        segments.firstOrNull { frame >= it.start && frame < it.end }?.delta ?: 0L

    private fun cutAtSegmentChanges(timeline: Timeline, trackId: String, segments: List<Segment>): EditResult<Timeline> {
        var current = timeline
        val boundaries = segments.flatMap { listOf(it.start, it.end) }.distinct().sortedBy { it.value }
        for (original in checkNotNull(timeline.track(trackId)).clips) {
            for (boundary in boundaries) {
                if (boundary <= original.timelineStart || boundary >= original.timelineEnd) continue
                if (deltaAt(segments, boundary - 1) == deltaAt(segments, boundary)) continue
                val newId = freshId(current, "${original.id}~m")
                current = when (val split = TimelineOps.split(current, trackId, boundary, newId)) {
                    is EditResult.Success -> split.value
                    is EditResult.Failure -> return split
                }
            }
        }
        return EditResult.Success(current)
    }

    // endregion

    private fun boundaryNear(base: Track, at: FrameIndex): FrameIndex {
        val containing = base.clips.firstOrNull { at >= it.timelineStart && at < it.timelineEnd } ?: return base.end
        if (at == containing.timelineStart) return containing.timelineStart
        val toStart = at - containing.timelineStart
        val toEnd = containing.timelineEnd - at
        return if (toStart < toEnd) containing.timelineStart else containing.timelineEnd
    }

    private fun removeFromOverlays(timeline: Timeline, baseId: String, start: FrameIndex, end: FrameIndex): EditResult<Timeline> {
        var current = timeline
        for (track in timeline.tracks.filter { it.id != baseId }) {
            current = when (val cut = ClipDeletion.removeRange(current, track.id, start, end)) {
                is EditResult.Success -> cut.value
                is EditResult.Failure -> return cut
            }
        }
        return EditResult.Success(current)
    }

    /** Shifts by [delta] every clip starting at or after [at] on the tracks matching [onTrack]. */
    private fun shiftStartingAt(timeline: Timeline, onTrack: (Track) -> Boolean, at: FrameIndex, delta: Long): Timeline {
        var current = timeline
        for (track in timeline.tracks.filter(onTrack)) {
            if (track.clips.none { it.timelineStart >= at }) continue
            current = current.withTrack(track.withClips(track.clips.map { if (it.timelineStart >= at) it.copy(timelineStart = it.timelineStart + delta) else it }))
        }
        return current
    }

    private fun freshId(timeline: Timeline, base: String): String {
        var n = 1
        var candidate = "$base$n"
        while (timeline.trackOfClip(candidate) != null) candidate = "$base${++n}"
        return candidate
    }
}
