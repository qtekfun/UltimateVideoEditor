package com.qtekfun.ultimatevideoeditor.domain

/**
 * Deleting a clip with a base track and overlays, as in LumaFusion.
 *
 * The base track is the lowest video track (the last video track in display order). Deleting from
 * it closes the gap, and clips on the other lanes are anchored at their start (DECISIONS 2026-10-29):
 * a clip starting at or after the deleted range shifts left with the base, a clip starting earlier or
 * inside the range stays where it is and is never cut. A linked audio partner still goes with its
 * video clip ([ClipLinks.settle]). Deleting from any other track removes the clip and leaves its gap.
 */
object ClipDeletion {

    /** The track that guides the timeline: the lowest video track, or null when there is none. */
    fun baseTrack(timeline: Timeline): Track? = timeline.tracks.lastOrNull { it.type == TrackType.VIDEO }

    /**
     * With [otherLanesFollow] the frames of the deleted base clip are also removed from every other lane, which closes up
     * behind it (used by [AutoCut], where a removed silence must leave no trace above it either).
     */
    fun delete(timeline: Timeline, clipId: String, otherLanesFollow: Boolean = false): EditResult<Timeline> {
        val track = timeline.trackOfClip(clipId) ?: return EditResult.Failure(EditError.ClipNotFound(clipId))
        val clip = checkNotNull(track.clip(clipId))
        if (track.id != baseTrack(timeline)?.id) {
            return EditResult.Success(timeline.withTrack(track.withClips(track.clips - clip)).pruned())
        }
        val start = clip.timelineStart
        val end = clip.timelineEnd
        var current = when (val closed = TimelineOps.rippleDelete(timeline, clipId)) {
            is EditResult.Success -> closed.value
            is EditResult.Failure -> return closed
        }
        for (overlay in timeline.tracks.filter { it.id != track.id }) {
            val next = if (otherLanesFollow) removeRange(current, overlay.id, start, end) else shiftAnchored(current, overlay.id, start, end)
            current = when (val cut = next) {
                is EditResult.Success -> cut.value
                is EditResult.Failure -> return cut
            }
        }
        return EditResult.Success(current)
    }

    /**
     * Moves the clips of [trackId] that start at or after [end] left by the length of [start, end); the others keep their
     * place. A clip that stayed and now runs into a shifted clip is trimmed to end where that clip starts (the shifted
     * clip keeps its sync with the base), and dropped when a shifted clip covers its start.
     */
    private fun shiftAnchored(timeline: Timeline, trackId: String, start: FrameIndex, end: FrameIndex): EditResult<Timeline> {
        val track = checkNotNull(timeline.track(trackId))
        val length = end - start
        val shifted = track.clips.filter { it.timelineStart >= end }.map { it.copy(timelineStart = it.timelineStart - length) }
        if (shifted.isEmpty()) return EditResult.Success(timeline)
        val staying = track.clips.filter { it.timelineStart < end }
        var current = timeline.withTrack(track.withClips(staying))
        for (clip in staying) {
            val hits = shifted.filter { it.overlaps(clip) }
            if (hits.isEmpty()) continue
            val limit = hits.minOf { it.timelineStart }
            val next = if (limit <= clip.timelineStart) removeClip(current, trackId, clip.id) else TimelineOps.trim(current, clip.id, TrimEdge.END, limit)
            current = when (next) {
                is EditResult.Success -> next.value
                is EditResult.Failure -> return next
            }
        }
        val kept = checkNotNull(current.track(trackId))
        return EditResult.Success(current.withTrack(kept.withClips(kept.clips + shifted)).pruned())
    }

    /**
     * Removes the frames [start, end) from [trackId] and closes them: clips inside vanish, clips
     * overlapping an edge are trimmed, a clip spanning the whole range is cut in two, and
     * everything after the range moves left by its length. With [closeGap] false the range is only
     * cleared: what remains stays where it is.
     */
    internal fun removeRange(
        timeline: Timeline,
        trackId: String,
        start: FrameIndex,
        end: FrameIndex,
        closeGap: Boolean = true,
    ): EditResult<Timeline> {
        var current = timeline
        val length = end - start
        for (original in checkNotNull(timeline.track(trackId)).clips) {
            val clipStart = original.timelineStart
            val clipEnd = original.timelineEnd
            val result = when {
                clipEnd <= start || clipStart >= end -> EditResult.Success(current)
                clipStart >= start && clipEnd <= end -> removeClip(current, trackId, original.id)
                clipStart < start && clipEnd <= end -> TimelineOps.trim(current, original.id, TrimEdge.END, start)
                clipStart >= start -> TimelineOps.trim(current, original.id, TrimEdge.START, end)
                else -> cutMiddle(current, trackId, original.id, start, end)
            }
            current = when (result) {
                is EditResult.Success -> result.value
                is EditResult.Failure -> return result
            }
        }
        if (!closeGap) return EditResult.Success(current.pruned())
        val track = checkNotNull(current.track(trackId))
        val shifted = track.clips.map { if (it.timelineStart >= end) it.copy(timelineStart = it.timelineStart - length) else it }
        return EditResult.Success(current.withTrack(track.withClips(shifted)).pruned())
    }

    private fun removeClip(timeline: Timeline, trackId: String, clipId: String): EditResult<Timeline> {
        val track = checkNotNull(timeline.track(trackId))
        return EditResult.Success(timeline.withTrack(track.withClips(track.clips.filter { it.id != clipId })).pruned())
    }

    /** Splits a clip around [start, end) and drops the middle piece; the right piece stays at [end]. */
    private fun cutMiddle(timeline: Timeline, trackId: String, clipId: String, start: FrameIndex, end: FrameIndex): EditResult<Timeline> {
        val middleId = freshId(timeline, "$clipId~cut")
        val afterId = freshId(timeline, "$clipId~rest", middleId)
        var current = when (val first = TimelineOps.split(timeline, trackId, start, middleId)) {
            is EditResult.Success -> first.value
            is EditResult.Failure -> return first
        }
        current = when (val second = TimelineOps.split(current, trackId, end, afterId)) {
            is EditResult.Success -> second.value
            is EditResult.Failure -> return second
        }
        return removeClip(current, trackId, middleId)
    }

    private fun freshId(timeline: Timeline, base: String, vararg taken: String): String {
        var candidate = base
        var n = 1
        while (timeline.trackOfClip(candidate) != null || candidate in taken) candidate = "$base${n++}"
        return candidate
    }
}
