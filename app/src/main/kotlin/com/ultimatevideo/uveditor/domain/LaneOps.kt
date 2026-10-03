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
     * Takes a clip off the base and puts it on an overlay lane ([toTrackId]) or on a brand new lane above
     * the others (null). The base closes the gap the clip leaves and the overlays follow it, exactly like
     * a delete; the clip then lands at [newStart] replacing whatever it covers on the overlay.
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
        var current = when (val closed = ClipDeletion.delete(timeline, clipId)) {
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

    private fun freshTrackId(timeline: Timeline, prefix: String): String =
        generateSequence(1) { it + 1 }.map { "$prefix$it" }.first { timeline.track(it) == null }
}
