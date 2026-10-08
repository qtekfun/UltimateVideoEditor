package com.qtekfun.ultimatevideoeditor.domain

/**
 * Markers that stick to a clip (SPECS.md 5.41, DECISIONS.md "Markers stick to clips").
 *
 * An anchored [Marker] stores the id of a video-track clip and an offset in timeline frames from that clip's start; its
 * [Marker.frame] is the resolved position `clip.timelineStart + offsetFrames`. The resolved frame stays stored so that the native
 * canvas, snapping, navigation, beat tools and exporters read one number and need no knowledge of anchors.
 *
 * Like [ClipLinks.settle], [settle] works on the outcome of any edit instead of on each operation: it compares the timeline before
 * and after and moves every anchored marker with its clip. That is what makes move, ripple, insert, overwrite, trim, split, speed
 * change and lane moves carry their markers, as part of the same undo step, without a marker code path in every operation.
 *
 * Rules (all integer maths):
 * - Moved clips (any track, ripple, insert, reorder) keep the offset, so the marker moves with them.
 * - A head trim keeps the marker on the same picture: the offset shrinks by the frames cut off the head; a tail trim keeps the offset.
 *   A marker that ends up before the first or past the last frame clamps to the first / last frame of the clip, unless a clip that
 *   did not exist before now covers its frame (an overwrite), in which case the marker is freed at its frame.
 * - A speed change scales the offset with the clip: `offset * newLength / oldLength`, rounded down (the same floor as other
 *   offsets of the retime), then clamped into the clip.
 * - A split (also the right part an overwrite leaves) takes the markers that lie in its part, re-anchored with the offset inside that
 *   part; a marker exactly on the cut goes with the right part.
 * - A clip that is gone (deleted, replaced) frees its markers at the frame they were on. A free marker never moves again by itself.
 * - If following clips make two markers share a frame, the free marker keeps it and anchored ones are nudged: markers are handled
 *   in (frame, id) order, each moved to the next free frame of its clip (forward, then backward); if the clip has none, the marker is freed.
 */
object MarkerAnchors {

    /** [marker] without its anchor (the frame stays). */
    fun free(marker: Marker): Marker = if (marker.isAnchored || marker.offsetFrames != 0L) marker.copy(anchorClipId = null, offsetFrames = 0) else marker

    /**
     * The clip a marker at [frame] would stick to: the one spanning [frame] on the base track (the lowest video lane) if there is one,
     * else the topmost overlay video lane's clip spanning it, else null (a gap, or only audio / titles there).
     */
    fun targetAt(timeline: Timeline, frame: FrameIndex): Clip? {
        val base = ClipDeletion.baseTrack(timeline)
        base?.clips?.firstOrNull { frame >= it.timelineStart && frame < it.timelineEnd }?.let { return it }
        for (track in timeline.tracks) {
            if (track.type != TrackType.VIDEO || track.id == base?.id) continue
            track.clips.firstOrNull { frame >= it.timelineStart && frame < it.timelineEnd }?.let { return it }
        }
        return null
    }

    /** [marker] stuck to the clip under its frame by [targetAt], or free when there is none. */
    fun anchoredAt(timeline: Timeline, marker: Marker): Marker {
        val clip = targetAt(timeline, marker.frame) ?: return free(marker)
        return marker.copy(anchorClipId = clip.id, offsetFrames = marker.frame - clip.timelineStart)
    }

    /** Why [marker] cannot be added to [timeline] as it is, or null. Free markers are always fine. */
    fun problem(timeline: Timeline, marker: Marker): String? {
        val id = marker.anchorClipId ?: return if (marker.offsetFrames != 0L) "marker ${marker.id} has an offset but no clip" else null
        val clip = clipOf(timeline, id) ?: return "marker ${marker.id} sticks to missing clip $id"
        if (marker.offsetFrames < 0 || marker.offsetFrames >= clip.durationFrames) return "marker ${marker.id} is outside clip $id"
        if (marker.frame != clip.timelineStart + marker.offsetFrames) return "marker ${marker.id} is not at its clip's offset"
        return null
    }

    fun violations(timeline: Timeline): List<String> = timeline.markers.mapNotNull { problem(timeline, it) }

    /** Opens a stored project: anchors to missing clips are dropped and the others are put at their clip's offset. */
    fun healed(timeline: Timeline): Timeline {
        if (timeline.markers.none { it.isAnchored || it.offsetFrames != 0L }) return timeline
        val clips = clipsById(timeline)
        val fixed = timeline.markers.map { m ->
            val clip = m.anchorClipId?.let { clips[it] }
            if (clip == null) free(m) else place(m, clip, m.offsetFrames.coerceIn(0, clip.durationFrames - 1))
        }
        return timeline.copy(markers = separated(timeline, fixed))
    }

    /** [after] with the anchored markers of [before] carried along by the clips they stick to. */
    fun settle(before: Timeline, after: Timeline): Timeline {
        if (after.markers.none { it.isAnchored } || before.tracks == after.tracks) return after
        val beforeClips = clipsById(before)
        val afterClips = clipsById(after)
        val beforeMarkers = before.markers.associateBy { it.id }
        val settled = after.markers.map { m ->
            if (!m.isAnchored) m else settleOne(before, after, beforeClips, afterClips, beforeMarkers[m.id], m)
        }
        return after.copy(markers = separated(after, settled))
    }

    private fun settleOne(
        before: Timeline,
        after: Timeline,
        beforeClips: Map<String, Clip>,
        afterClips: Map<String, Clip>,
        previous: Marker?,
        marker: Marker,
    ): Marker {
        val clipId = checkNotNull(marker.anchorClipId)
        val now = afterClips[clipId]
        // A marker the command itself created or re-anchored is placed by its own offset.
        if (previous == null || previous.anchorClipId != clipId || previous.offsetFrames != marker.offsetFrames) {
            return if (now == null) free(marker) else place(marker, now, marker.offsetFrames.coerceIn(0, now.durationFrames - 1))
        }
        val was = beforeClips[clipId] ?: return free(marker)
        val offset = previous.offsetFrames
        if (now == null) {
            // An overwrite that covers the clip's start leaves only its right part, under a new id.
            val tail = overwriteTail(before, after, was) ?: return free(marker)
            val inTail = offset - (was.durationFrames - tail.durationFrames)
            return if (inTail in 0 until tail.durationFrames) place(marker, tail, inTail) else free(marker)
        }
        val raw = rawOffset(was, now, offset)
        if (raw >= now.durationFrames) {
            tail(before, after, was, now)?.let { tail ->
                val inTail = offset - (was.durationFrames - tail.durationFrames)
                if (inTail in 0 until tail.durationFrames) return place(marker, tail, inTail)
            }
        }
        if (raw in 0 until now.durationFrames) return place(marker, now, raw)
        if (coveredByNewClip(before, after, now, previous.frame, now.timelineStart + raw)) return free(marker.copy(frame = previous.frame))
        return place(marker, now, raw.coerceIn(0, now.durationFrames - 1))
    }

    /** The "<id>~<new>" clip an overwrite leaves of [was] when the new clip covers its start, if any. */
    private fun overwriteTail(before: Timeline, after: Timeline, was: Clip): Clip? {
        val trackId = before.trackOfClip(was.id)?.id ?: return null
        return after.track(trackId)?.clips?.firstOrNull { c ->
            c.id.startsWith(was.id + "~") && before.trackOfClip(c.id) == null && c.timelineEnd == was.timelineEnd && c.durationFrames <= was.durationFrames
        }
    }

    /** Where the offset lands in the clip's new shape, before it is clamped. */
    private fun rawOffset(was: Clip, now: Clip, offset: Long): Long {
        val sameRange = now.sourceIn == was.sourceIn && now.sourceOut == was.sourceOut
        if (sameRange) {
            return if (now.durationFrames != was.durationFrames && was.durationFrames > 0) offset * now.durationFrames / was.durationFrames else offset
        }
        return offset - headCut(was, now)
    }

    /** Timeline frames cut off the start of the picture (negative when the clip grew at its head). */
    private fun headCut(was: Clip, now: Clip): Long {
        if (!was.hasMedia) return if (now.timelineEnd == was.timelineEnd && now.durationFrames != was.durationFrames) was.durationFrames - now.durationFrames else 0
        val span = was.sourceSpan
        if (span <= 0) return 0
        val sourceCut = if (was.reverse) was.sourceOut - now.sourceOut else now.sourceIn - was.sourceIn
        return sourceCut * was.durationFrames / span
    }

    /** The right part a split or an overwrite left of [was] (a clip that is new, on the same track and ends where [was] ended), if any. */
    private fun tail(before: Timeline, after: Timeline, was: Clip, now: Clip): Clip? {
        if (now.timelineStart != was.timelineStart) return null
        val track = after.trackOfClip(now.id) ?: return null
        return track.clips
            .filter { c ->
                c.id != now.id && before.trackOfClip(c.id) == null && c.assetId == was.assetId && c.hasMedia == was.hasMedia &&
                    c.timelineStart >= now.timelineEnd && c.timelineEnd == was.timelineEnd && c.durationFrames <= was.durationFrames - now.durationFrames
            }
            .minByOrNull { it.timelineStart }
    }

    private fun coveredByNewClip(before: Timeline, after: Timeline, now: Clip, first: FrameIndex, second: FrameIndex): Boolean {
        val track = after.trackOfClip(now.id) ?: return false
        return track.clips.any { c -> c.id != now.id && before.trackOfClip(c.id) == null && (first >= c.timelineStart && first < c.timelineEnd) || (second >= c.timelineStart && second < c.timelineEnd) }
    }

    private fun place(marker: Marker, clip: Clip, offset: Long): Marker =
        marker.copy(anchorClipId = clip.id, offsetFrames = offset, frame = clip.timelineStart + offset)

    /** Makes the frames unique again after clips moved. Free markers keep theirs; see the class comment for the nudging order. */
    private fun separated(timeline: Timeline, markers: List<Marker>): List<Marker> {
        val clips = clipsById(timeline)
        val taken = HashSet<FrameIndex>()
        markers.filter { !it.isAnchored }.forEach { taken += it.frame }
        val result = ArrayList<Marker>(markers.size)
        result += markers.filter { !it.isAnchored }
        for (m in markers.filter { it.isAnchored }.sortedWith(compareBy({ it.frame }, { it.id }))) {
            val clip = checkNotNull(clips[m.anchorClipId])
            if (taken.add(m.frame)) {
                result += m
                continue
            }
            val forward = (m.frame.value + 1 until clip.timelineEnd.value).map { FrameIndex(it) }
            val backward = (m.frame.value - 1 downTo clip.timelineStart.value).map { FrameIndex(it) }
            val slot = (forward + backward).firstOrNull { it !in taken }
            if (slot != null) {
                taken += slot
                result += place(m, clip, slot - clip.timelineStart)
            } else {
                var f = clip.timelineEnd
                while (f in taken) f += 1
                taken += f
                result += free(m.copy(frame = f))
            }
        }
        return result.sortedWith(compareBy({ it.frame }, { it.id }))
    }

    private fun clipsById(timeline: Timeline): Map<String, Clip> = HashMap<String, Clip>().also { map ->
        for (track in timeline.tracks) for (clip in track.clips) map[clip.id] = clip
    }

    private fun clipOf(timeline: Timeline, id: String): Clip? = timeline.trackOfClip(id)?.clip(id)
}

/** The outcome of an edit with its anchored markers carried along by their clips (see [MarkerAnchors.settle]). */
internal fun EditResult<Timeline>.markersFrom(before: Timeline): EditResult<Timeline> = when (this) {
    is EditResult.Failure -> this
    is EditResult.Success -> EditResult.Success(MarkerAnchors.settle(before, value))
}
