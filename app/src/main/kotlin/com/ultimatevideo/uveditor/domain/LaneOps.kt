package com.ultimatevideo.uveditor.domain

/**
 * Lane-level edits, LumaFusion style: the lowest video lane is the base, every other video lane is an
 * overlay stacked above it, audio lanes sit below. Display order is track order, first is topmost.
 */
object LaneOps {

    /** True when dropping [clipId] at [start] on [trackId] would land on top of another clip there. */
    fun overlapsOnLane(timeline: Timeline, clipId: String, trackId: String, start: FrameIndex): Boolean {
        val clip = timeline.trackOfClip(clipId)?.clip(clipId) ?: return false
        val dest = timeline.track(trackId) ?: return false
        val end = start + clip.durationFrames
        return dest.clips.any { it.id != clipId && it.timelineStart < end && it.timelineEnd > start }
    }

    /**
     * Moves an overlay video clip to a brand-new overlay lane above every other video lane, in one
     * step. The base can never leave its lane.
     */
    fun moveToNewLane(timeline: Timeline, clipId: String, newStart: FrameIndex, snap: Snap? = null): EditResult<Timeline> {
        val source = timeline.trackOfClip(clipId) ?: return failure(EditError.ClipNotFound(clipId))
        val base = ClipDeletion.baseTrack(timeline) ?: return failure(EditError.NoBaseTrack)
        if (source.id == base.id) return failure(EditError.BaseClipCannotLeave(clipId))
        if (source.type != TrackType.VIDEO) return failure(EditError.TrackTypeMismatch(clipId, source.id))
        val newId = freshTrackId(timeline, "track-v")
        val top = timeline.tracks.indexOfFirst { it.type == TrackType.VIDEO }
        val withLane = when (val added = TimelineOps.addTrack(timeline, Track(newId, TrackType.VIDEO), top)) {
            is EditResult.Success -> added.value
            is EditResult.Failure -> return added
        }
        return TimelineOps.move(withLane, clipId, newStart, newId, snap)
    }

    /**
     * Drops a clip onto a lane, replacing whatever it covers there. On the base the footage under the
     * clip is replaced without changing the base's length (the base never gets a gap: a drop past its
     * end is placed at the end) and overlays above the replaced part are cleared like a deleted
     * range, without closing it. Elsewhere it is [TimelineOps.overwrite].
     */
    fun overwriteMove(timeline: Timeline, clipId: String, toTrackId: String, newStart: FrameIndex): EditResult<Timeline> {
        val source = timeline.trackOfClip(clipId) ?: return failure(EditError.ClipNotFound(clipId))
        val clip = checkNotNull(source.clip(clipId))
        val dest = timeline.track(toTrackId) ?: return failure(EditError.TrackNotFound(toTrackId))
        if (dest.type != source.type) return failure(EditError.TrackTypeMismatch(clipId, toTrackId))
        if (newStart < FrameIndex.ZERO) return failure(EditError.NegativeStart)
        val base = ClipDeletion.baseTrack(timeline)
        val onBase = base != null && dest.id == base.id
        if (onBase && source.id == base.id) return failure(EditError.InvalidClip("a base clip is reordered, not overwritten"))
        val without = timeline.withTrack(source.withClips(source.clips - clip))
        val start = if (onBase) minOf(newStart, checkNotNull(without.track(dest.id)).end) else newStart
        val placed = clip.copy(timelineStart = start)
        var current = when (val result = TimelineOps.overwrite(without, dest.id, placed)) {
            is EditResult.Success -> result.value
            is EditResult.Failure -> return result
        }
        if (onBase) {
            for (overlay in current.tracks.filter { it.id != dest.id }) {
                current = when (val cut = ClipDeletion.removeRange(current, overlay.id, start, placed.timelineEnd, closeGap = false)) {
                    is EditResult.Success -> cut.value
                    is EditResult.Failure -> return cut
                }
            }
        }
        return EditResult.Success(current)
    }

    /**
     * Puts a clip that is not on the timeline yet on a brand-new overlay lane above every video lane,
     * starting at [start]. Used when media is dragged in from the tray above the top lane.
     */
    fun addClipOnNewLane(timeline: Timeline, clip: Clip, start: FrameIndex): EditResult<Timeline> {
        if (start < FrameIndex.ZERO) return failure(EditError.NegativeStart)
        if (timeline.trackOfClip(clip.id) != null) return failure(EditError.DuplicateClipId(clip.id))
        val newId = freshTrackId(timeline, "track-v")
        val top = timeline.tracks.indexOfFirst { it.type == TrackType.VIDEO }.takeIf { it >= 0 } ?: 0
        val withLane = when (val added = TimelineOps.addTrack(timeline, Track(newId, TrackType.VIDEO), top)) {
            is EditResult.Success -> added.value
            is EditResult.Failure -> return added
        }
        return TimelineOps.overwrite(withLane, newId, clip.copy(timelineStart = start))
    }

    /**
     * Drops a clip that is not on the timeline yet onto [toTrackId], replacing whatever it covers.
     * On the base the footage under it is replaced (the base only grows if the clip runs past its end,
     * and a drop past the end is placed at the end, so it never gets a gap) and overlays above the
     * replaced part are cleared like a deleted range, without closing it. Elsewhere it is
     * [TimelineOps.overwrite].
     */
    fun overwriteNewClip(timeline: Timeline, clip: Clip, toTrackId: String, start: FrameIndex): EditResult<Timeline> {
        val dest = timeline.track(toTrackId) ?: return failure(EditError.TrackNotFound(toTrackId))
        if (start < FrameIndex.ZERO) return failure(EditError.NegativeStart)
        if (timeline.trackOfClip(clip.id) != null) return failure(EditError.DuplicateClipId(clip.id))
        val base = ClipDeletion.baseTrack(timeline)
        val onBase = base != null && dest.id == base.id
        val at = if (onBase) minOf(start, dest.end) else start
        val placed = clip.copy(timelineStart = at)
        var current = when (val result = TimelineOps.overwrite(timeline, dest.id, placed)) {
            is EditResult.Success -> result.value
            is EditResult.Failure -> return result
        }
        if (onBase) {
            for (overlay in current.tracks.filter { it.id != dest.id }) {
                current = when (val cut = ClipDeletion.removeRange(current, overlay.id, at, placed.timelineEnd, closeGap = false)) {
                    is EditResult.Success -> cut.value
                    is EditResult.Failure -> return cut
                }
            }
        }
        return EditResult.Success(current)
    }

    /**
     * Takes a clip off the base and puts it on an overlay lane ([toTrackId]) or on a brand new lane above
     * the others (null). It is a move: the base closes the gap the clip leaves, but no overlay is deleted
     * or shifted; the clip lands at [newStart] replacing whatever it covers on the overlay lane.
     */
    fun liftFromBase(timeline: Timeline, clipId: String, toTrackId: String?, newStart: FrameIndex): EditResult<Timeline> {
        val source = timeline.trackOfClip(clipId) ?: return failure(EditError.ClipNotFound(clipId))
        val base = ClipDeletion.baseTrack(timeline) ?: return failure(EditError.NoBaseTrack)
        if (source.id != base.id) return failure(EditError.InvalidClip("only a base clip can be lifted off the base"))
        if (newStart < FrameIndex.ZERO) return failure(EditError.NegativeStart)
        val clip = checkNotNull(source.clip(clipId))
        if (toTrackId != null) {
            val dest = timeline.track(toTrackId) ?: return failure(EditError.TrackNotFound(toTrackId))
            if (dest.id == base.id || dest.type != TrackType.VIDEO) return failure(EditError.TrackTypeMismatch(clipId, toTrackId))
        }
        var current = when (val closed = TimelineOps.rippleDelete(timeline, clipId)) {
            is EditResult.Success -> closed.value
            is EditResult.Failure -> return closed
        }
        val destId = toTrackId ?: freshTrackId(current, "track-v").also { newId ->
            val top = current.tracks.indexOfFirst { it.type == TrackType.VIDEO }
            current = when (val added = TimelineOps.addTrack(current, Track(newId, TrackType.VIDEO), top)) {
                is EditResult.Success -> added.value
                is EditResult.Failure -> return added
            }
        }
        return TimelineOps.overwrite(current, destId, clip.copy(timelineStart = newStart))
    }

    /**
     * Moves lane [trackId] one place up ([delta] -1) or down (+1) among the lanes of its kind.
     * Overlay video lanes reorder among themselves (their order is their stacking order); the base
     * stays the lowest video lane; audio and title lanes reorder among their own kind.
     */
    fun moveTrack(timeline: Timeline, trackId: String, delta: Int): EditResult<Timeline> {
        val track = timeline.track(trackId) ?: return failure(EditError.TrackNotFound(trackId))
        val base = ClipDeletion.baseTrack(timeline)
        if (base != null && track.id == base.id) return failure(EditError.BaseTrackCannotMove(trackId))
        val group = timeline.tracks.filter { it.type == track.type && it.id != base?.id }
        val at = group.indexOfFirst { it.id == trackId }
        val other = group.getOrNull(at + delta)?.takeIf { delta == -1 || delta == 1 }
            ?: return failure(EditError.TrackCannotMove(trackId, if (delta < 0) "already the top lane of its kind" else "already the lowest lane of its kind"))
        val tracks = timeline.tracks.toMutableList()
        val i = tracks.indexOfFirst { it.id == trackId }
        val j = tracks.indexOfFirst { it.id == other.id }
        tracks[i] = other
        tracks[j] = track
        return EditResult.Success(timeline.copy(tracks = tracks))
    }

    /**
     * Display indices (top first) of the lanes that reorder together with [trackId]: lanes of its own kind, never the
     * base. Empty for the base and for an unknown track.
     */
    fun laneGroup(timeline: Timeline, trackId: String): List<Int> {
        val track = timeline.track(trackId) ?: return emptyList()
        val baseId = ClipDeletion.baseTrack(timeline)?.id
        if (track.id == baseId) return emptyList()
        return timeline.tracks.indices.filter { timeline.tracks[it].type == track.type && timeline.tracks[it].id != baseId }
    }

    /**
     * Where a lane dragged by its header lands while the finger is over display lane [hoverIndex]: the nearest lane of
     * its group (the base and lanes of other kinds are never targets). Null when the lane cannot move at all (it is the
     * base or the only lane of its kind).
     */
    fun laneDropTarget(timeline: Timeline, trackId: String, hoverIndex: Int): Int? {
        val group = laneGroup(timeline, trackId)
        if (group.size < 2) return null
        return group.minByOrNull { kotlin.math.abs(it - hoverIndex) }
    }

    /**
     * Moves lane [trackId] to the display slot [targetIndex] of its group, shifting the lanes in between by one place;
     * lanes of other kinds and the base keep their slots. [targetIndex] must be one of [laneGroup]'s slots.
     */
    fun moveTrackTo(timeline: Timeline, trackId: String, targetIndex: Int): EditResult<Timeline> {
        val track = timeline.track(trackId) ?: return failure(EditError.TrackNotFound(trackId))
        val base = ClipDeletion.baseTrack(timeline)
        if (base != null && track.id == base.id) return failure(EditError.BaseTrackCannotMove(trackId))
        val group = laneGroup(timeline, trackId)
        if (targetIndex !in group) return failure(EditError.TrackCannotMove(trackId, "lanes only move among lanes of their own kind"))
        val from = timeline.tracks.indexOfFirst { it.id == trackId }
        if (from == targetIndex) return EditResult.Success(timeline)
        val order = group.map { timeline.tracks[it] }.toMutableList()
        val moved = order.removeAt(group.indexOf(from))
        order.add(group.indexOf(targetIndex), moved)
        val tracks = timeline.tracks.toMutableList()
        group.forEachIndexed { position, slot -> tracks[slot] = order[position] }
        return EditResult.Success(timeline.copy(tracks = tracks))
    }

    /**
     * Drops a clip into the cut between two touching clips of an overlay, audio or title lane, opening room
     * for it: the clips after the cut shift right by the clip's length, only on that lane (nothing else
     * moves). A cut inside a clip, or a base lane (use the base insert there), is refused.
     */
    fun insertOnLane(timeline: Timeline, clipId: String, toTrackId: String, at: FrameIndex): EditResult<Timeline> {
        val source = timeline.trackOfClip(clipId) ?: return EditResult.Failure(EditError.ClipNotFound(clipId))
        val clip = checkNotNull(source.clip(clipId))
        val dest = timeline.track(toTrackId) ?: return EditResult.Failure(EditError.TrackNotFound(toTrackId))
        if (dest.type != source.type) return EditResult.Failure(EditError.TrackTypeMismatch(clipId, toTrackId))
        if (dest.id == ClipDeletion.baseTrack(timeline)?.id) {
            return EditResult.Failure(EditError.InvalidClip("the base is inserted into with the base insert"))
        }
        if (at < FrameIndex.ZERO) return EditResult.Failure(EditError.NegativeStart)
        val without = timeline.withTrack(source.withClips(source.clips - clip))
        val lane = checkNotNull(without.track(dest.id))
        lane.clips.firstOrNull { it.timelineStart < at && it.timelineEnd > at }?.let {
            return EditResult.Failure(EditError.Overlap(it.id))
        }
        val length = clip.durationFrames
        val shifted = lane.clips.map { if (it.timelineStart >= at) it.copy(timelineStart = it.timelineStart + length) else it }
        return EditResult.Success(without.withTrack(lane.withClips(shifted + clip.copy(timelineStart = at))).pruned())
    }

    /**
     * Drops a clip that is not on the timeline yet into the cut between two touching clips of an overlay,
     * audio or title lane: the clips after the cut shift right by the clip's length, only on that lane.
     * A cut inside a clip, or the base lane (use the base insert there), is refused.
     */
    fun insertNewOnLane(timeline: Timeline, clip: Clip, toTrackId: String, at: FrameIndex): EditResult<Timeline> {
        val lane = timeline.track(toTrackId) ?: return EditResult.Failure(EditError.TrackNotFound(toTrackId))
        if (lane.id == ClipDeletion.baseTrack(timeline)?.id) {
            return EditResult.Failure(EditError.InvalidClip("the base is inserted into with the base insert"))
        }
        if (at < FrameIndex.ZERO) return EditResult.Failure(EditError.NegativeStart)
        if (timeline.trackOfClip(clip.id) != null) return EditResult.Failure(EditError.DuplicateClipId(clip.id))
        lane.clips.firstOrNull { it.timelineStart < at && it.timelineEnd > at }?.let {
            return EditResult.Failure(EditError.Overlap(it.id))
        }
        val length = clip.durationFrames
        val shifted = lane.clips.map { if (it.timelineStart >= at) it.copy(timelineStart = it.timelineStart + length) else it }
        return EditResult.Success(timeline.withTrack(lane.withClips(shifted + clip.copy(timelineStart = at))).pruned())
    }

    private fun freshTrackId(timeline: Timeline, prefix: String): String =
        generateSequence(1) { it + 1 }.map { "$prefix$it" }.first { timeline.track(it) == null }
}
