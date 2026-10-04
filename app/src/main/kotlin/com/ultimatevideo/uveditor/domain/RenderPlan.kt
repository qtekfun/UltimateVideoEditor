package com.ultimatevideo.uveditor.domain

enum class RenderKind { VIDEO, TITLE, AUDIO }

/**
 * One clip as the renderers see it. The preview, the exporter and the audio mixer all start from
 * this list, so a transition looks and sounds the same everywhere (SPECS.md 5.7).
 *
 * A transition is expressed by extending the two clips around the cut: the outgoing clip keeps
 * playing past its out point, the incoming clip starts early, and the incoming one fades in over
 * [crossfadeInFrames] on top of the other. Times are project frames; [sourceInFrame] is the source
 * frame shown at [startFrame] (it may be before the clip's in point for an incoming clip).
 */
data class RenderClip(
    val clipId: String,
    val trackId: String,
    val kind: RenderKind,
    /** 0 is the topmost visual track; audio-only clips have -1. */
    val layer: Int,
    /**
     * Decoder slot within a layer. Two clips of the same media that show at the same time (a
     * transition between cuts of one file) need separate decoders, so the incoming one gets lane 1.
     */
    val lane: Int,
    val assetId: String?,
    val title: TitleContent?,
    val startFrame: Long,
    val durationFrames: Long,
    val sourceInFrame: Long,
    val transform: ClipTransform,
    val gainDb: Double,
    /** Length of the fade-in of the incoming clip of a transition (0 when there is none). */
    val crossfadeInFrames: Long,
    /** Length of the fade-out of the outgoing clip's audio (0 when there is none). */
    val crossfadeOutFrames: Long,
    /** Animated pose of the clip; empty when [transform] is fixed. Frames are relative to [keyframeOriginFrame]. */
    val keyframes: List<Keyframe> = emptyList(),
    /** Project frame of the clip's own first frame (a transition can start [startFrame] earlier). */
    val keyframeOriginFrame: Long = startFrame,
    /** How the clip's frames map to its source when it is not a plain 1x forward span; null otherwise. */
    val retime: ClipRetime? = null,
    /** Effects, blend mode and mask of the clip; the compositor applies them in this order. */
    val fx: ClipFx = ClipFx.NONE,
    /** Set for a photo or sticker: a picture without a decoder, drawn like a title ([assetId] says which). */
    val still: StillKind? = null,
    /** The clip's colour space override, or null to use the asset's detected one. */
    val colorOverride: SourceColorSpace? = null,
    /** Pan, fade handles, EQ, noise suppression and normalisation of the clip's sound. */
    val audio: ClipAudio = ClipAudio.NONE,
    /** Keyframed parameters (effect values, volume, pan, EQ gains); frames are relative to [keyframeOriginFrame]. */
    val params: List<ParamTrack> = emptyList(),
    /** Smooth slow motion: frames of a slowed clip are interpolated between two source frames, see [sourceMixAt]. */
    val smooth: Boolean = false,
) {
    val endFrame: Long get() = startFrame + durationFrames

    /** The clip's effects at project [frame] with every animated value evaluated; [fx] itself when none is animated. */
    fun fxAt(frame: Long): ClipFx = fx.animatedAt(params, frame - keyframeOriginFrame)

    /** True when an effect value changes over the clip, so the picture needs a new look every frame. */
    val hasAnimatedFx: Boolean get() = params.any { ParamIds.parseFx(it.paramId) != null }

    fun covers(frame: Long): Boolean = frame >= startFrame && frame < endFrame

    /** The clip's pose at [frame], keyframes included, before any crossfade. */
    fun transformAt(frame: Long): ClipTransform = Keyframes.evaluate(keyframes, frame - keyframeOriginFrame, transform)

    /** Layer opacity at [frame]: the clip's own (animated) opacity times the crossfade ramp. */
    fun opacityAt(frame: Long): Double = transformAt(frame).opacity * CrossfadeCurve.progress(frame - startFrame, crossfadeInFrames)

    /** What the compositor draws at [frame]: the pose with the crossfade folded into the opacity. */
    fun appearanceAt(frame: Long): ClipTransform = transformAt(frame).let { it.copy(opacity = it.opacity * CrossfadeCurve.progress(frame - startFrame, crossfadeInFrames)) }

    /** Source frame shown at [frame] (unclamped; renderers clamp to the media). */
    fun sourceFrameAt(frame: Long): Long =
        retime?.sourceFrameAt(frame - keyframeOriginFrame) ?: (sourceInFrame + (frame - startFrame))

    /**
     * Where [frame] falls between two source frames. Without smooth slow motion, or on a clip that is not slowed, the
     * mix is 0 and only [SourceMix.frame] (the same as [sourceFrameAt]) is shown.
     */
    fun sourceMixAt(frame: Long): SourceMix {
        val r = retime
        if (!smooth || r == null) return SourceMix(sourceFrameAt(frame), sourceFrameAt(frame), 0)
        return r.mixAt(frame - keyframeOriginFrame)
    }

    /** True when the clip plays its source backwards. */
    val isReverse: Boolean get() = retime?.reverse == true
}

/**
 * Shapes of a crossfade over `d` frames. Progress at frame `k` of the fade is `(k + 0.5) / d`, so it
 * never starts at exactly 0 or ends at exactly 1 and is symmetric around the middle. Video uses it
 * as opacity of the incoming clip; audio uses equal-power gains `sin`/`cos` of it. The native
 * exporter and mixer implement the same formulas (`render/crossfade_math.h`).
 */
object CrossfadeCurve {
    fun progress(k: Long, d: Long): Double = if (d <= 0L || k >= d) 1.0 else (k.coerceAtLeast(0L) + 0.5) / d

    fun fadeInGain(k: Long, d: Long): Double = kotlin.math.sin(progress(k, d) * Math.PI / 2.0)

    fun fadeOutGain(k: Long, d: Long): Double = kotlin.math.cos(progress(k, d) * Math.PI / 2.0)
}

/** Every clip of the timeline, with transitions folded in. Order is track order, then start. */
fun Timeline.renderClips(): List<RenderClip> {
    // Visual tracks count from the top; the first one drawn last, so it ends up on top.
    var nextLayer = 0
    val result = ArrayList<RenderClip>()
    for (track in tracks) {
        val layer = if (track.type == TrackType.AUDIO) -1 else nextLayer++
        val lanes = HashMap<String, Int>()
        for (clip in track.clips) {
            val incoming = transitions.firstOrNull { it.toClipId == clip.id }
            val outgoing = transitions.firstOrNull { it.fromClipId == clip.id }
            val pre = incoming?.preFrames ?: 0L
            val post = outgoing?.postFrames ?: 0L
            val previous = incoming?.let { track.clip(it.fromClipId) }
            val lane = if (previous != null && previous.assetId != null && previous.assetId == clip.assetId) {
                1 - (lanes[previous.id] ?: 0)
            } else {
                0
            }
            lanes[clip.id] = lane
            result += RenderClip(
                clipId = clip.id,
                trackId = track.id,
                kind = when (track.type) {
                    TrackType.VIDEO -> RenderKind.VIDEO
                    TrackType.TITLE -> RenderKind.TITLE
                    TrackType.AUDIO -> RenderKind.AUDIO
                },
                layer = layer,
                lane = lane,
                assetId = clip.assetId,
                title = clip.title,
                startFrame = clip.timelineStart.value - pre,
                durationFrames = clip.durationFrames + pre + post,
                sourceInFrame = when {
                    !clip.hasMedia -> 0L
                    clip.isRetimed -> clip.retime.sourceFrameAt(-pre)
                    else -> clip.sourceIn.value - pre
                },
                transform = clip.transform,
                gainDb = clip.gainDb,
                crossfadeInFrames = incoming?.durationFrames ?: 0L,
                crossfadeOutFrames = outgoing?.durationFrames ?: 0L,
                keyframes = clip.keyframes,
                keyframeOriginFrame = clip.timelineStart.value,
                retime = if (clip.hasMedia && clip.isRetimed) clip.retime else null,
                fx = clip.stabilise?.takeIf { clip.hasMedia && clip.assetId != null && track.type == TrackType.VIDEO }
                    ?.let { clip.fx.copy(stabKey = StabKey.of(checkNotNull(clip.assetId), it)) }
                    ?: clip.fx,
                colorOverride = clip.colorOverride,
                still = clip.still,
                audio = clip.audio,
                params = clip.params,
                smooth = clip.smoothSlowMo && clip.hasMedia,
            )
        }
    }
    return result
}

/**
 * The visual clips (video and titles) showing at [frame], bottom first, so drawing them in order
 * leaves the topmost track on top. Within a track the later-starting clip is above the earlier one,
 * which is what puts the incoming clip of a transition over the outgoing one.
 */
fun visualClipsAt(clips: List<RenderClip>, frame: Long): List<RenderClip> =
    clips.filter { it.kind != RenderKind.AUDIO && it.covers(frame) }
        .sortedWith(compareByDescending<RenderClip> { it.layer }.thenBy { it.startFrame })
