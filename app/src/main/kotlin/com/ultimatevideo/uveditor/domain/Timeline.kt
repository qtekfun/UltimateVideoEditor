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

/** One spoken word of a caption, in clip frames (0 is the clip's first frame; may be negative or past the end after a trim). */
data class TitleWord(val text: String, val startFrame: Long, val endFrame: Long)

/** How a caption's words come in and out of emphasis over the clip. */
enum class TitleAnimation {
    NONE,

    /** All words show; the word being spoken is highlighted and slightly enlarged. */
    KARAOKE,

    /** Words appear one by one as they are spoken; the newest pops in highlighted. */
    POP_IN,

    /** Letters appear progressively across each word. */
    TYPEWRITER,
}

/**
 * Which moment of a caption animation to draw. Renderers set it per frame from `CaptionAnimator`;
 * it is never stored in a project. Indexes are into [TitleContent.words] and into its text.
 */
data class TitleLook(
    /** Words from this index on are hidden (-1 shows all). */
    val visibleWords: Int = ALL,
    /** Text characters from this index on are hidden (-1 shows all). */
    val visibleChars: Int = ALL,
    /** The word drawn highlighted (and at [activePercent]); -1 for none. */
    val activeWord: Int = NO_WORD,
    /** Size of the active word in percent of normal (100 = unchanged). */
    val activePercent: Int = 100,
) {
    val isFull: Boolean get() = this == FULL

    companion object {
        const val ALL = -1
        const val NO_WORD = -1
        val FULL = TitleLook()
    }
}

/**
 * Text payload of a title clip. Sizes are fractions of the project height so a title looks the
 * same at any export resolution. Placement, scale, rotation and opacity come from the clip's
 * [ClipTransform]; the text block is centred on the canvas before that transform.
 *
 * A caption can carry the timing of its [words] and an [animation]; the words must appear in
 * [text] in order, or the text is simply drawn as a static title.
 */
data class TitleContent(
    val text: String,
    val sizeFraction: Double = DEFAULT_SIZE_FRACTION,
    val colorArgb: Int = DEFAULT_COLOR_ARGB,
    val alignment: TitleAlignment = TitleAlignment.CENTER,
    val bold: Boolean = false,
    /** A dark outline around the glyphs so the text stays readable over any footage (captions use it). */
    val outline: Boolean = false,
    val words: List<TitleWord> = emptyList(),
    val animation: TitleAnimation = TitleAnimation.NONE,
    /** Colour of the emphasised word of an animation. */
    val highlightArgb: Int = DEFAULT_HIGHLIGHT_ARGB,
    /** Set by renderers per frame; stored projects always have the full look. */
    val look: TitleLook = TitleLook.FULL,
    /**
     * A multilayer title: text, shapes and pictures drawn bottom to top (see [TitleLayer]). When not
     * empty the title is drawn from these layers and the single-text fields above are ignored, except
     * [text], which mirrors the first text layer so lists and labels have something to show.
     */
    val layers: List<TitleLayer> = emptyList(),
) {
    /** True for a multilayer title. */
    val isLayered: Boolean get() = layers.isNotEmpty()

    fun problem(): String? = if (isLayered) layeredProblem() else plainProblem()

    private fun plainProblem(): String? = when {
        text.isBlank() -> "title text must not be blank"
        !(sizeFraction.isFinite() && sizeFraction in MIN_SIZE_FRACTION..MAX_SIZE_FRACTION) ->
            "title size must be between $MIN_SIZE_FRACTION and $MAX_SIZE_FRACTION of the canvas height"
        words.any { it.text.isBlank() || it.endFrame < it.startFrame } -> "caption words must have text and end after they start"
        words.zipWithNext().any { (a, b) -> b.startFrame < a.startFrame } -> "caption words must be in order"
        else -> null
    }

    private fun layeredProblem(): String? = when {
        layers.size > TitleLayers.MAX_LAYERS -> "a title can have at most ${TitleLayers.MAX_LAYERS} layers"
        else -> layers.firstNotNullOfOrNull { it.problem() }
    }

    /** The same title with every word [delta] frames earlier, for a clip whose start moved [delta] frames later. */
    fun shiftedBy(delta: Long): TitleContent =
        if (words.isEmpty() || delta == 0L) this else copy(words = words.map { it.copy(startFrame = it.startFrame - delta, endFrame = it.endFrame - delta) })

    /** This title as a cache key for its pixels: word timing does not change what is drawn. */
    fun withoutTiming(): TitleContent = if (words.isEmpty()) this else copy(words = words.map { it.copy(startFrame = 0, endFrame = 0) })

    companion object {
        const val MIN_SIZE_FRACTION = 0.01
        const val MAX_SIZE_FRACTION = 0.5
        const val DEFAULT_SIZE_FRACTION = 0.08
        const val DEFAULT_COLOR_ARGB = 0xFFFFFFFF.toInt()
        const val DEFAULT_HIGHLIGHT_ARGB = 0xFFFFE600.toInt()
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
 * What a still clip shows. A [PHOTO] clip's [Clip.assetId] is an image in the media library; a
 * [STICKER] clip's is the id of a built-in sticker (not in the library). Neither has a length of its
 * own, so like a title the source range is only the clip's length.
 */
enum class StillKind { PHOTO, STICKER }

/** Length a freshly added photo or sticker gets, in seconds. */
object Stills {
    const val DEFAULT_SECONDS = 5L
}

/**
 * A span of a source placed on a track. Source range is [sourceIn, sourceOut) in source frames;
 * the clip occupies [timelineStart, timelineEnd) on the timeline. Normally that is 1x speed and the
 * clip is as long as its range; [retimedFrames] makes it another length (speed = range / length),
 * [reverse] plays the range backwards and [speedRamp] shapes the speed over the clip. A one-frame
 * range held for a longer length is a freeze frame. [Clip.retime] is the mapping from timeline
 * frames to source frames that every renderer uses.
 * Title clips live on title tracks, have no [assetId] and carry [title] instead; their source
 * range is just the duration (sourceIn is 0). Still clips ([still]) live on video tracks and show
 * one picture for as long as they last; they too have no source length, only a range equal to
 * their duration, and can be trimmed or stretched freely.
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
    /**
     * Animated pose, in clip frames (0 is the clip's first frame). Empty means [transform] holds for
     * the whole clip; otherwise the keyframes drive the pose and [transform] is only what the clip
     * returns to when its last keyframe is removed.
     */
    val keyframes: List<Keyframe> = emptyList(),
    /** Length on the timeline when it differs from the source range's length; null means 1x speed. */
    val retimedFrames: Long? = null,
    /** Plays the source range backwards. */
    val reverse: Boolean = false,
    /** Relative speed over the clip, in clip frames; empty means constant speed. */
    val speedRamp: List<SpeedKey> = emptyList(),
    /** Effects, blend mode and mask of a video or title clip; neutral by default. */
    val fx: ClipFx = ClipFx.NONE,
    /** Set for photos and stickers; see [StillKind]. */
    val still: StillKind? = null,
    /**
     * How this clip's source is read when it differs from what the file says (a file without HDR
     * metadata that really is HLG, or the reverse). Null means the asset's own detected space.
     */
    val colorOverride: SourceColorSpace? = null,
    /** Pan, fade handles, EQ, noise suppression and loudness normalisation; neutral by default. */
    val audio: ClipAudio = ClipAudio.NONE,
) {
    /** True for a clip that plays media with a length of its own (not a title, photo or sticker). */
    val hasMedia: Boolean get() = title == null && still == null

    val durationFrames: Long get() = retimedFrames ?: (sourceOut - sourceIn)
    val timelineEnd: FrameIndex get() = timelineStart + durationFrames

    /** The pose [relativeFrame] frames after the clip's start. */
    fun transformAt(relativeFrame: Long): ClipTransform = Keyframes.evaluate(keyframes, relativeFrame, transform)

    /** The pose at the project frame [frame]. */
    fun transformAtProjectFrame(frame: FrameIndex): ClipTransform = transformAt(frame - timelineStart)

    fun overlaps(other: Clip): Boolean = timelineStart < other.timelineEnd && other.timelineStart < timelineEnd
}

/** Clips are kept sorted by start and never overlap; see [Timeline.invariantViolations]. */
data class Track(
    val id: String,
    val type: TrackType,
    val clips: List<Clip> = emptyList(),
    /** Volume, mute, solo, ducking role and bus compressor; neutral by default. */
    val audio: TrackAudio = TrackAudio.NONE,
) {
    val end: FrameIndex get() = clips.lastOrNull()?.timelineEnd ?: FrameIndex.ZERO

    fun clip(id: String): Clip? = clips.firstOrNull { it.id == id }

    internal fun withClips(newClips: List<Clip>): Track = copy(clips = newClips.sortedBy { it.timelineStart })
}

data class Timeline(
    val tracks: List<Track> = emptyList(),
    val transitions: List<Transition> = emptyList(),
    /** Ruler markers (manual and detected beats), sorted by frame with unique frames; see [MarkerOps]. */
    val markers: List<Marker> = emptyList(),
    /** Sidechain ducking of the MUSIC tracks by the VOICE tracks; null is off. */
    val ducking: Ducking? = null,
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
        if (to.hasMedia && to.retime.sourceFrameAt(-transition.preFrames) < 0) return "incoming clip has no media before its in point"
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
                if (clip.sourceOut <= clip.sourceIn) violations += "clip ${clip.id} has an empty source range"
                if (clip.retimedFrames != null && clip.retimedFrames == clip.sourceSpan) violations += "clip ${clip.id} stores its own length as a retime"
                if (!clip.hasMedia && clip.isRetimed) violations += "clip ${clip.id} has no media of its own but is retimed"
                if (clip.still != null) {
                    if (clip.title != null) violations += "clip ${clip.id} is both a title and a still"
                    if (clip.assetId == null) violations += "still clip ${clip.id} has no picture"
                    if (track.type != TrackType.VIDEO) violations += "still clip ${clip.id} is not on a video track"
                    if (clip.gainDb != 0.0) violations += "still clip ${clip.id} has an audio gain"
                }
                if (clip.speedRamp.isNotEmpty() && clip.durationFrames > 0) {
                    SpeedRamps.problem(clip.speedRamp, clip.durationFrames)?.let { violations += "clip ${clip.id} $it" }
                }
                if (clip.timelineStart < FrameIndex.ZERO) violations += "clip ${clip.id} starts before frame 0"
                if (clip.sourceIn < FrameIndex.ZERO) violations += "clip ${clip.id} has negative sourceIn"
                clip.transform.problem()?.let { violations += "clip ${clip.id} transform: $it" }
                Keyframes.problem(clip.keyframes, clip.durationFrames)?.let { violations += "clip ${clip.id} $it" }
                ClipGain.problem(clip.gainDb)?.let { violations += "clip ${clip.id} $it" }
                clip.fx.problem()?.let { violations += "clip ${clip.id} fx: $it" }
                if (track.type == TrackType.AUDIO && !clip.fx.isNeutral) violations += "audio clip ${clip.id} has visual effects"
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
        violations += MarkerOps.violations(markers)
        return violations
    }
}
