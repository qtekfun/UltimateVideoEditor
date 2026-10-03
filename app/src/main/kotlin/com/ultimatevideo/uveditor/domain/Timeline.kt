package com.ultimatevideo.uveditor.domain

enum class TrackType { VIDEO, AUDIO, TITLE }

/**
 * 2D placement of a video clip on the project canvas. The clip's frame is first fitted
 * ("contain") into the canvas; this transform is applied on top, about the clip's centre.
 *
 * - [positionX]/[positionY]: offset of the centre in project canvas pixels, +x right, +y down.
 * - [scaleX]/[scaleY]: multipliers on the fitted size; must be positive.
 * - [rotationDegrees]: clockwise, any finite value.
 * - [opacity]: 0 (transparent) to 1 (opaque).
 */
data class ClipTransform(
    val positionX: Double = 0.0,
    val positionY: Double = 0.0,
    val scaleX: Double = 1.0,
    val scaleY: Double = 1.0,
    val rotationDegrees: Double = 0.0,
    val opacity: Double = 1.0,
) {
    val isIdentity: Boolean get() = this == IDENTITY

    /** Reason this transform cannot be rendered, or null when it is valid. */
    fun problem(): String? = when {
        !(positionX.isFinite() && positionY.isFinite()) -> "position must be finite"
        !(scaleX.isFinite() && scaleY.isFinite() && scaleX > 0.0 && scaleY > 0.0) -> "scale must be positive"
        !rotationDegrees.isFinite() -> "rotation must be finite"
        !(opacity.isFinite() && opacity in 0.0..1.0) -> "opacity must be between 0 and 1"
        else -> null
    }

    companion object {
        val IDENTITY = ClipTransform()
        const val MIN_SCALE = 0.05
        const val MAX_SCALE = 20.0
    }
}

/** Per-clip audio gain limits in dB (the mixer accepts the same range). */
object ClipGain {
    const val MIN_DB = -96.0
    const val MAX_DB = 24.0

    fun problem(gainDb: Double): String? =
        if (gainDb.isFinite() && gainDb in MIN_DB..MAX_DB) null else "gain must be between $MIN_DB and $MAX_DB dB"
}

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
    val transform: ClipTransform = ClipTransform.IDENTITY,
    val gainDb: Double = 0.0,
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
                clip.transform.problem()?.let { violations += "clip ${clip.id} transform: $it" }
                ClipGain.problem(clip.gainDb)?.let { violations += "clip ${clip.id} $it" }
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
