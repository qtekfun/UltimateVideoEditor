package com.qtekfun.ultimatevideoeditor.domain

/**
 * Deleting a clip with a base track and overlays, as in LumaFusion.
 *
 * The base track is the lowest video track (the last video track in display order). Deleting from
 * it closes the gap on the base only: the lanes above and the audio lanes are neither cut nor moved
 * (the owner's rule, DECISIONS 2026-10-29). A linked audio partner still goes with its video clip
 * ([ClipLinks.settle]). Deleting from any other track removes the clip and leaves its gap.
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
        if (!otherLanesFollow) return EditResult.Success(current)
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
