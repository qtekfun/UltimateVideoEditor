package com.ultimatevideo.uveditor.domain

enum class TrackType { VIDEO, AUDIO, TITLE }

/**
 * A span of a source placed on a track. Source range is [sourceIn, sourceOut) in source frames;
 * the clip occupies [timelineStart, timelineEnd) on the timeline at 1x speed.
 * Title clips have no [assetId].
 */
data class Clip(
    val id: String,
    val assetId: String?,
    val timelineStart: FrameIndex,
    val sourceIn: FrameIndex,
    val sourceOut: FrameIndex,
) {
    val durationFrames: Long get() = sourceOut - sourceIn
    val timelineEnd: FrameIndex get() = timelineStart + durationFrames

    fun overlaps(other: Clip): Boolean = timelineStart < other.timelineEnd && other.timelineStart < timelineEnd
}

/** Clips are kept sorted by start and never overlap; see [Timeline.invariantViolations]. */
data class Track(
    val id: String,
    val type: TrackType,
    val clips: List<Clip> = emptyList(),
) {
    val end: FrameIndex get() = clips.lastOrNull()?.timelineEnd ?: FrameIndex.ZERO

    fun clip(id: String): Clip? = clips.firstOrNull { it.id == id }

    internal fun withClips(newClips: List<Clip>): Track = copy(clips = newClips.sortedBy { it.timelineStart })
}

data class Timeline(val tracks: List<Track> = emptyList()) {
    fun track(id: String): Track? = tracks.firstOrNull { it.id == id }

    fun trackOfClip(clipId: String): Track? = tracks.firstOrNull { t -> t.clips.any { it.id == clipId } }

    internal fun withTrack(updated: Track): Timeline = copy(tracks = tracks.map { if (it.id == updated.id) updated else it })

    /** Human-readable list of broken invariants; empty when the timeline is valid. */
    fun invariantViolations(): List<String> {
        val violations = mutableListOf<String>()
        val seenClipIds = mutableSetOf<String>()
        val seenTrackIds = mutableSetOf<String>()
        for (track in tracks) {
            if (!seenTrackIds.add(track.id)) violations += "duplicate track id ${track.id}"
            var previous: Clip? = null
            for (clip in track.clips) {
                if (!seenClipIds.add(clip.id)) violations += "duplicate clip id ${clip.id}"
                if (clip.durationFrames <= 0) violations += "clip ${clip.id} has non-positive duration"
                if (clip.timelineStart < FrameIndex.ZERO) violations += "clip ${clip.id} starts before frame 0"
                if (clip.sourceIn < FrameIndex.ZERO) violations += "clip ${clip.id} has negative sourceIn"
                previous?.let { prev ->
                    if (clip.timelineStart < prev.timelineStart) violations += "track ${track.id} is not sorted at ${clip.id}"
                    if (clip.overlaps(prev)) violations += "clips ${prev.id} and ${clip.id} overlap on ${track.id}"
                }
                previous = clip
            }
        }
        return violations
    }
}
