package com.ultimatevideo.uveditor.domain

import kotlin.math.abs

/** Magnetic snapping: clip edges snap to other clip edges, frame 0 and the playhead. */
data class Snap(val playhead: FrameIndex?, val thresholdFrames: Long)

enum class TrimEdge { START, END }

/** Pure timeline edits. Each returns a new [Timeline] or a typed [EditError]. */
object TimelineOps {

    private fun success(timeline: Timeline): EditResult<Timeline> = EditResult.Success(timeline)

    /** Inserts an empty [track] at [index] (clamped to the valid range). Track order is display order. */
    fun addTrack(timeline: Timeline, track: Track, index: Int): EditResult<Timeline> {
        if (timeline.track(track.id) != null) return failure(EditError.DuplicateTrackId(track.id))
        if (track.clips.isNotEmpty()) return failure(EditError.InvalidClip("a new track must be empty"))
        val at = index.coerceIn(0, timeline.tracks.size)
        return success(Timeline(timeline.tracks.toMutableList().apply { add(at, track) }))
    }

    /** Removes an empty track. */
    fun removeTrack(timeline: Timeline, trackId: String): EditResult<Timeline> {
        val track = timeline.track(trackId) ?: return failure(EditError.TrackNotFound(trackId))
        if (track.clips.isNotEmpty()) return failure(EditError.TrackNotEmpty(trackId))
        return success(Timeline(timeline.tracks.filter { it.id != trackId }))
    }

    /** Replaces the 2D transform of a clip. The clip keeps its place and source range. */
    fun setTransform(timeline: Timeline, clipId: String, transform: ClipTransform): EditResult<Timeline> {
        transform.problem()?.let { return failure(EditError.InvalidAppearance(it)) }
        return updateClip(timeline, clipId) { it.copy(transform = transform) }
    }

    /** Sets the audio gain of a clip in dB. */
    fun setGain(timeline: Timeline, clipId: String, gainDb: Double): EditResult<Timeline> {
        ClipGain.problem(gainDb)?.let { return failure(EditError.InvalidAppearance(it)) }
        return updateClip(timeline, clipId) { it.copy(gainDb = gainDb) }
    }

    /** Sets transform and gain together, so one edit changes both or neither. */
    fun setAppearance(timeline: Timeline, clipId: String, transform: ClipTransform, gainDb: Double): EditResult<Timeline> {
        transform.problem()?.let { return failure(EditError.InvalidAppearance(it)) }
        ClipGain.problem(gainDb)?.let { return failure(EditError.InvalidAppearance(it)) }
        return updateClip(timeline, clipId) { it.copy(transform = transform, gainDb = gainDb) }
    }

    private fun updateClip(timeline: Timeline, clipId: String, change: (Clip) -> Clip): EditResult<Timeline> {
        val track = timeline.trackOfClip(clipId) ?: return failure(EditError.ClipNotFound(clipId))
        val clip = track.clip(clipId) ?: return failure(EditError.ClipNotFound(clipId))
        return success(timeline.withTrack(track.withClips(track.clips.map { if (it.id == clip.id) change(it) else it })))
    }

    /** Splits the clip on [trackId] that strictly contains [at]; the right half gets [newClipId]. */
    fun split(timeline: Timeline, trackId: String, at: FrameIndex, newClipId: String): EditResult<Timeline> {
        val track = timeline.track(trackId) ?: return failure(EditError.TrackNotFound(trackId))
        if (timeline.trackOfClip(newClipId) != null) return failure(EditError.DuplicateClipId(newClipId))
        val clip = track.clips.firstOrNull { at > it.timelineStart && at < it.timelineEnd }
            ?: return failure(EditError.SplitOutsideClip)
        val offset = at - clip.timelineStart
        val left = clip.copy(sourceOut = clip.sourceIn + offset)
        val right = clip.copy(id = newClipId, timelineStart = at, sourceIn = clip.sourceIn + offset)
        return success(timeline.withTrack(track.withClips(track.clips - clip + left + right)))
    }

    /**
     * Moves a clip to [newStart], optionally onto [toTrackId] (same track type). With [snap] the
     * start or end is pulled to the nearest target within the threshold; if the snapped position
     * collides, the exact requested position is tried before failing with [EditError.Overlap].
     */
    fun move(
        timeline: Timeline,
        clipId: String,
        newStart: FrameIndex,
        toTrackId: String? = null,
        snap: Snap? = null,
    ): EditResult<Timeline> {
        val source = timeline.trackOfClip(clipId) ?: return failure(EditError.ClipNotFound(clipId))
        val clip = source.clip(clipId) ?: return failure(EditError.ClipNotFound(clipId))
        val dest = timeline.track(toTrackId ?: source.id) ?: return failure(EditError.TrackNotFound(toTrackId ?: source.id))
        if (dest.type != source.type) return failure(EditError.TrackTypeMismatch(clipId, dest.id))
        if (newStart < FrameIndex.ZERO) return failure(EditError.NegativeStart)

        val candidates = listOfNotNull(snap?.let { snappedStart(timeline, clip, newStart, it) }, newStart).distinct()
        val others = dest.clips.filter { it.id != clipId }
        var blocking: String? = null
        for (start in candidates) {
            val moved = clip.copy(timelineStart = start)
            val hit = others.firstOrNull { it.overlaps(moved) }
            if (hit == null) {
                val withoutClip = timeline.withTrack(source.withClips(source.clips.filter { it.id != clipId }))
                val target = withoutClip.track(dest.id) ?: return failure(EditError.TrackNotFound(dest.id))
                return success(withoutClip.withTrack(target.withClips(target.clips + moved)))
            }
            blocking = hit.id
        }
        return failure(EditError.Overlap(checkNotNull(blocking)))
    }

    private fun snappedStart(timeline: Timeline, clip: Clip, requested: FrameIndex, snap: Snap): FrameIndex? {
        val targets = buildList {
            add(FrameIndex.ZERO)
            snap.playhead?.let { add(it) }
            for (track in timeline.tracks) {
                for (other in track.clips) {
                    if (other.id == clip.id) continue
                    add(other.timelineStart)
                    add(other.timelineEnd)
                }
            }
        }
        val requestedEnd = requested + clip.durationFrames
        var bestDelta: Long? = null
        for (target in targets) {
            for (delta in longArrayOf(target - requested, target - requestedEnd)) {
                val best = bestDelta
                if (abs(delta) <= snap.thresholdFrames && (best == null || abs(delta) < abs(best))) bestDelta = delta
            }
        }
        val delta = bestDelta ?: return null
        val snapped = requested + delta
        return if (snapped < FrameIndex.ZERO) null else snapped
    }

    /**
     * Places [clip] on [trackId], replacing whatever it overlaps: covered clips are removed, partly
     * covered ones are trimmed, and a clip spanning it is split (the right part gets the id
     * "<existingId>~<clipId>").
     */
    fun overwrite(timeline: Timeline, trackId: String, clip: Clip): EditResult<Timeline> {
        val track = timeline.track(trackId) ?: return failure(EditError.TrackNotFound(trackId))
        if (timeline.trackOfClip(clip.id) != null) return failure(EditError.DuplicateClipId(clip.id))
        if (clip.durationFrames <= 0) return failure(EditError.InvalidClip("non-positive duration"))
        if (clip.timelineStart < FrameIndex.ZERO || clip.sourceIn < FrameIndex.ZERO) {
            return failure(EditError.InvalidClip("negative start"))
        }
        clip.transform.problem()?.let { return failure(EditError.InvalidClip(it)) }
        ClipGain.problem(clip.gainDb)?.let { return failure(EditError.InvalidClip(it)) }
        val start = clip.timelineStart
        val end = clip.timelineEnd
        val result = mutableListOf<Clip>()
        for (existing in track.clips) {
            if (existing.timelineEnd <= start || existing.timelineStart >= end) {
                result += existing
                continue
            }
            if (existing.timelineStart < start) {
                result += existing.copy(sourceOut = existing.sourceIn + (start - existing.timelineStart))
            }
            if (existing.timelineEnd > end) {
                result += existing.copy(
                    id = "${existing.id}~${clip.id}",
                    timelineStart = end,
                    sourceIn = existing.sourceIn + (end - existing.timelineStart),
                )
            }
        }
        result += clip
        return success(timeline.withTrack(track.withClips(result)))
    }

    /** Removes a clip and shifts every later clip on its track left by the clip's duration. */
    fun rippleDelete(timeline: Timeline, clipId: String): EditResult<Timeline> {
        val track = timeline.trackOfClip(clipId) ?: return failure(EditError.ClipNotFound(clipId))
        val clip = track.clip(clipId) ?: return failure(EditError.ClipNotFound(clipId))
        val shifted = track.clips.filter { it.id != clipId }.map {
            if (it.timelineStart >= clip.timelineEnd) it.copy(timelineStart = it.timelineStart - clip.durationFrames) else it
        }
        return success(timeline.withTrack(track.withClips(shifted)))
    }

    /** Snaps a clip to the end of the clip before it on its track (frame 0 if first), closing the gap. */
    fun rippleAppend(timeline: Timeline, clipId: String): EditResult<Timeline> {
        val track = timeline.trackOfClip(clipId) ?: return failure(EditError.ClipNotFound(clipId))
        val clip = track.clip(clipId) ?: return failure(EditError.ClipNotFound(clipId))
        val previous = track.clips.lastOrNull { it.id != clipId && it.timelineStart < clip.timelineStart }
        val target = previous?.timelineEnd ?: FrameIndex.ZERO
        if (target == clip.timelineStart) return success(timeline)
        if (target > clip.timelineStart) return failure(EditError.Overlap(checkNotNull(previous).id))
        return success(timeline.withTrack(track.withClips(track.clips.map { if (it.id == clipId) it.copy(timelineStart = target) else it })))
    }

    /**
     * Moves one edge of a clip. START: [frame] is the new timeline start (source in follows).
     * END: [frame] is the new exclusive timeline end (source out follows, bounded by [sourceLength]
     * when known). Bounded by neighbours; the opposite edge never moves.
     */
    fun trim(
        timeline: Timeline,
        clipId: String,
        edge: TrimEdge,
        frame: FrameIndex,
        sourceLength: Long? = null,
    ): EditResult<Timeline> {
        val track = timeline.trackOfClip(clipId) ?: return failure(EditError.ClipNotFound(clipId))
        val clip = track.clip(clipId) ?: return failure(EditError.ClipNotFound(clipId))
        val others = track.clips.filter { it.id != clipId }
        val trimmed = when (edge) {
            TrimEdge.START -> {
                val delta = frame - clip.timelineStart
                clip.copy(timelineStart = frame, sourceIn = clip.sourceIn + delta)
            }
            TrimEdge.END -> clip.copy(sourceOut = clip.sourceOut + (frame - clip.timelineEnd))
        }
        if (trimmed.durationFrames <= 0) return failure(EditError.InvalidTrim("clip would be empty"))
        if (trimmed.timelineStart < FrameIndex.ZERO) return failure(EditError.NegativeStart)
        if (trimmed.sourceIn < FrameIndex.ZERO) return failure(EditError.SourceOutOfRange)
        if (sourceLength != null && trimmed.sourceOut.value > sourceLength) return failure(EditError.SourceOutOfRange)
        others.firstOrNull { it.overlaps(trimmed) }?.let { return failure(EditError.Overlap(it.id)) }
        return success(timeline.withTrack(track.withClips(track.clips.map { if (it.id == clipId) trimmed else it })))
    }
}
