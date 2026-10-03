package com.ultimatevideo.uveditor.domain

/**
 * Deleting a clip with a base track and overlays, as in LumaFusion.
 *
 * The base track is the lowest video track (the last video track in display order). It is the
 * guide: deleting from it closes the gap, and every other track follows that edit so overlays never
 * end up pointing at footage that no longer exists. Deleting from any other track simply removes
 * the clip and leaves its gap, so neighbours keep their place.
 */
object ClipDeletion {

    /** The track that guides the timeline: the lowest video track, or null when there is none. */
    fun baseTrack(timeline: Timeline): Track? = timeline.tracks.lastOrNull { it.type == TrackType.VIDEO }

    fun delete(timeline: Timeline, clipId: String): EditResult<Timeline> {
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
            current = when (val cut = removeRange(current, overlay.id, start, end)) {
                is EditResult.Success -> cut.value
                is EditResult.Failure -> return cut
            }
        }
        return EditResult.Success(current)
    }

    /**
     * Removes the frames [start, end) from [trackId] and closes them: clips inside vanish, clips
     * overlapping an edge are trimmed, a clip spanning the whole range is cut in two, and
     * everything after the range moves left by its length.
     */
    private fun removeRange(timeline: Timeline, trackId: String, start: FrameIndex, end: FrameIndex): EditResult<Timeline> {
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
