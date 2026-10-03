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

enum class TitleAlignment { LEFT, CENTER, RIGHT }

/**
 * Text payload of a title clip. Sizes are fractions of the project height so a title looks the
 * same at any export resolution. Placement, scale, rotation and opacity come from the clip's
 * [ClipTransform]; the text block is centred on the canvas before that transform.
 */
data class TitleContent(
    val text: String,
    val sizeFraction: Double = DEFAULT_SIZE_FRACTION,
    val colorArgb: Int = DEFAULT_COLOR_ARGB,
    val alignment: TitleAlignment = TitleAlignment.CENTER,
    val bold: Boolean = false,
    /** A dark outline around the glyphs so the text stays readable over any footage (captions use it). */
    val outline: Boolean = false,
) {
    fun problem(): String? = when {
        text.isBlank() -> "title text must not be blank"
        !(sizeFraction.isFinite() && sizeFraction in MIN_SIZE_FRACTION..MAX_SIZE_FRACTION) ->
            "title size must be between $MIN_SIZE_FRACTION and $MAX_SIZE_FRACTION of the canvas height"
        else -> null
    }

    companion object {
        const val MIN_SIZE_FRACTION = 0.01
        const val MAX_SIZE_FRACTION = 0.5
        const val DEFAULT_SIZE_FRACTION = 0.08
        const val DEFAULT_COLOR_ARGB = 0xFFFFFFFF.toInt()
    }
}

enum class TransitionType { CROSSFADE }

/**
 * A transition across the cut between two adjacent clips of one track, [fromClipId] ending exactly
 * where [toClipId] starts. It is centred on the cut: it runs from `cut - preFrames` to
 * `cut + postFrames` and consumes media beyond the trim points, so the clips do not move and the
 * project length does not change. Over the region the incoming clip fades in on top of the
 * outgoing one (audio uses an equal-power crossfade). The outgoing clip keeps playing past its
 * out point for [postFrames]; the incoming clip is already playing [preFrames] before its in point.
 */
data class Transition(
    val id: String,
    val fromClipId: String,
    val toClipId: String,
    val durationFrames: Long,
    val type: TransitionType = TransitionType.CROSSFADE,
) {
    val preFrames: Long get() = durationFrames / 2
    val postFrames: Long get() = durationFrames - preFrames

    companion object {
        const val MIN_DURATION_FRAMES = 2L
    }
}

/**
 * A span of a source placed on a track. Source range is [sourceIn, sourceOut) in source frames;
 * the clip occupies [timelineStart, timelineEnd) on the timeline at 1x speed.
 * Title clips live on title tracks, have no [assetId] and carry [title] instead; their source
 * range is just the duration (sourceIn is 0).
 */
data class Clip(
    val id: String,
    val assetId: String?,
    val timelineStart: FrameIndex,
    val sourceIn: FrameIndex,
    val sourceOut: FrameIndex,
    val transform: ClipTransform = ClipTransform.IDENTITY,
    val gainDb: Double = 0.0,
    val title: TitleContent? = null,
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

data class Timeline(
    val tracks: List<Track> = emptyList(),
    val transitions: List<Transition> = emptyList(),
) {
    fun track(id: String): Track? = tracks.firstOrNull { it.id == id }

    fun transition(id: String): Transition? = transitions.firstOrNull { it.id == id }

    /** The transition from [fromClipId] to [toClipId], if any. */
    fun transitionBetween(fromClipId: String, toClipId: String): Transition? =
        transitions.firstOrNull { it.fromClipId == fromClipId && it.toClipId == toClipId }

    /** Frame where the transition's incoming clip starts; the transition is centred on it. */
    fun cutOf(transition: Transition): FrameIndex? =
        trackOfClip(transition.toClipId)?.clip(transition.toClipId)?.timelineStart

    /** First and last+1 timeline frame covered by [transition], or null if its clips are gone. */
    fun regionOf(transition: Transition): LongRange? {
        val cut = cutOf(transition)?.value ?: return null
        return (cut - transition.preFrames) until (cut + transition.postFrames)
    }

    fun trackOfClip(clipId: String): Track? = tracks.firstOrNull { t -> t.clips.any { it.id == clipId } }

    internal fun withTrack(updated: Track): Timeline = copy(tracks = tracks.map { if (it.id == updated.id) updated else it })

    /** Reason [transition] cannot hold between its clips in this timeline, or null if it can. */
    fun transitionProblem(transition: Transition): String? {
        val fromTrack = trackOfClip(transition.fromClipId) ?: return "outgoing clip ${transition.fromClipId} not found"
        val toTrack = trackOfClip(transition.toClipId) ?: return "incoming clip ${transition.toClipId} not found"
        if (fromTrack.id != toTrack.id) return "transition clips must be on the same track"
        val from = checkNotNull(fromTrack.clip(transition.fromClipId))
        val to = checkNotNull(toTrack.clip(transition.toClipId))
        if (from.timelineEnd != to.timelineStart) return "transition clips must be adjacent"
        if (transition.durationFrames < Transition.MIN_DURATION_FRAMES) return "transition is too short"
        if (transition.preFrames > from.durationFrames) return "transition reaches past the start of the outgoing clip"
        if (transition.postFrames > to.durationFrames) return "transition reaches past the end of the incoming clip"
        if (to.title == null && transition.preFrames > to.sourceIn.value) return "incoming clip has no media before its in point"
        val next = transitions.firstOrNull { it.id != transition.id && it.fromClipId == to.id }
        if (next != null && transition.postFrames + next.preFrames > to.durationFrames) {
            return "transitions on clip ${to.id} overlap"
        }
        val previous = transitions.firstOrNull { it.id != transition.id && it.toClipId == from.id }
        if (previous != null && previous.postFrames + transition.preFrames > from.durationFrames) {
            return "transitions on clip ${from.id} overlap"
        }
        return null
    }

    /** Drops transitions that no longer hold after an edit changed clips around them. */
    internal fun pruned(): Timeline {
        var current = this
        while (true) {
            val broken = current.transitions.firstOrNull { current.transitionProblem(it) != null } ?: return current
            current = current.copy(transitions = current.transitions - broken)
        }
    }

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
                when {
                    track.type == TrackType.TITLE && clip.title == null -> violations += "clip ${clip.id} on a title track has no title"
                    track.type != TrackType.TITLE && clip.title != null -> violations += "clip ${clip.id} has a title but is not on a title track"
                }
                clip.title?.problem()?.let { violations += "clip ${clip.id} $it" }
                previous?.let { prev ->
                    if (clip.timelineStart < prev.timelineStart) violations += "track ${track.id} is not sorted at ${clip.id}"
                    if (clip.overlaps(prev)) violations += "clips ${prev.id} and ${clip.id} overlap on ${track.id}"
                }
                previous = clip
            }
        }
        val seenTransitionIds = mutableSetOf<String>()
        for (transition in transitions) {
            if (!seenTransitionIds.add(transition.id)) violations += "duplicate transition id ${transition.id}"
            transitionProblem(transition)?.let { violations += "transition ${transition.id}: $it" }
        }
        return violations
    }
}
