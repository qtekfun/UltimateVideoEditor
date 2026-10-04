package com.ultimatevideo.uveditor.domain

/** One copied clip: where it sat relative to the earliest copied clip, and the lane it came from. */
data class ClipboardEntry(
    val clip: Clip,
    val trackId: String,
    val trackType: TrackType,
    /** The clip came from the base track, so it pastes as an insert into the base. */
    val onBase: Boolean,
    /** Frames after the earliest copied clip's start. */
    val relativeStart: Long,
)

/**
 * Clips copied from the timeline with their layout and every attribute (effects, keyframes, speed,
 * transform, titles...), plus the transitions between copied clips. Pasting is [GroupOps.paste].
 */
data class Clipboard(val entries: List<ClipboardEntry>, val transitions: List<Transition>) {
    val isEmpty: Boolean get() = entries.isEmpty()

    /** The first copied clip, whose attributes "paste attributes" uses. */
    val primary: Clip? get() = entries.firstOrNull()?.clip

    companion object {
        /** Copies [ids]; null when none of them exists. */
        fun capture(timeline: Timeline, ids: Collection<String>): Clipboard? {
            val base = ClipDeletion.baseTrack(timeline)
            val found = ids.distinct().mapNotNull { id ->
                val track = timeline.trackOfClip(id) ?: return@mapNotNull null
                track to checkNotNull(track.clip(id))
            }
            if (found.isEmpty()) return null
            val earliest = found.minOf { it.second.timelineStart.value }
            val entries = found
                .sortedWith(compareBy({ it.second.timelineStart.value }, { timeline.tracks.indexOf(it.first) }))
                .map { (track, clip) ->
                    ClipboardEntry(
                        clip = clip,
                        trackId = track.id,
                        trackType = track.type,
                        onBase = base != null && track.id == base.id,
                        relativeStart = clip.timelineStart.value - earliest,
                    )
                }
            val copied = entries.mapTo(HashSet()) { it.clip.id }
            val transitions = timeline.transitions.filter { it.fromClipId in copied && it.toClipId in copied }
            return Clipboard(entries, transitions)
        }
    }
}

/** Pure helpers for choosing clips. The editor keeps the chosen ids; none of this changes the timeline. */
object ClipSelection {

    /** Every clip of lane [trackId]. */
    fun allInLane(timeline: Timeline, trackId: String): Set<String> =
        timeline.track(trackId)?.clips?.mapTo(LinkedHashSet()) { it.id } ?: emptySet()

    /** Every clip that has not ended at [playhead] yet, on [trackId] or, when null, on every lane. */
    fun fromPlayhead(timeline: Timeline, playhead: FrameIndex, trackId: String? = null): Set<String> {
        val ids = LinkedHashSet<String>()
        for (track in timeline.tracks) {
            if (trackId != null && track.id != trackId) continue
            for (clip in track.clips) if (clip.timelineEnd > playhead) ids += clip.id
        }
        return ids
    }

    /** Every clip overlapping the frames [from, until), on every lane. */
    fun inRange(timeline: Timeline, from: FrameIndex, until: FrameIndex): Set<String> {
        val ids = LinkedHashSet<String>()
        for (track in timeline.tracks) for (clip in track.clips) if (clip.timelineStart < until && clip.timelineEnd > from) ids += clip.id
        return ids
    }

    /** [ids] without the ones that no longer exist. */
    fun existing(timeline: Timeline, ids: Collection<String>): Set<String> =
        ids.filterTo(LinkedHashSet()) { timeline.trackOfClip(it) != null }

    /** True when every id is a base clip and they touch each other, so they can move as a run. */
    fun isContiguousBaseRun(timeline: Timeline, ids: Collection<String>): Boolean {
        val base = ClipDeletion.baseTrack(timeline) ?: return false
        if (ids.isEmpty()) return false
        val order = base.clips.map { it.id }
        val indices = ids.map { order.indexOf(it) }
        if (indices.any { it < 0 }) return false
        return indices.sorted().zipWithNext().all { (a, b) -> b == a + 1 }
    }
}
