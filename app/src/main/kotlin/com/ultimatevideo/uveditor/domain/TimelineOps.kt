package com.ultimatevideo.uveditor.domain

import com.ultimatevideo.uveditor.domain.captions.CaptionAnimator
import kotlin.math.abs

/**
 * Magnetic snapping: clip edges snap to other clip edges, frame 0, the playhead and [extraTargets]
 * (timeline markers, when the editor's marker snapping is on).
 */
data class Snap(val playhead: FrameIndex?, val thresholdFrames: Long, val extraTargets: List<FrameIndex> = emptyList())

enum class TrimEdge { START, END }

/** Pure timeline edits. Each returns a new [Timeline] or a typed [EditError]. */
object TimelineOps {

    private fun success(timeline: Timeline): EditResult<Timeline> = EditResult.Success(timeline)

    /** Inserts an empty [track] at [index] (clamped to the valid range). Track order is display order. */
    fun addTrack(timeline: Timeline, track: Track, index: Int): EditResult<Timeline> {
        if (timeline.track(track.id) != null) return failure(EditError.DuplicateTrackId(track.id))
        if (track.clips.isNotEmpty()) return failure(EditError.InvalidClip("a new track must be empty"))
        val at = index.coerceIn(0, timeline.tracks.size)
        return success(timeline.copy(tracks = timeline.tracks.toMutableList().apply { add(at, track) }))
    }

    /** Removes an empty track. */
    fun removeTrack(timeline: Timeline, trackId: String): EditResult<Timeline> {
        val track = timeline.track(trackId) ?: return failure(EditError.TrackNotFound(trackId))
        if (track.clips.isNotEmpty()) return failure(EditError.TrackNotEmpty(trackId))
        return success(timeline.copy(tracks = timeline.tracks.filter { it.id != trackId }))
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

    /** Replaces the audio tools (pan, fades, EQ, noise suppression, normalise) of a clip that has sound. */
    fun setClipAudio(timeline: Timeline, clipId: String, audio: ClipAudio): EditResult<Timeline> {
        val clip = timeline.trackOfClip(clipId)?.clip(clipId) ?: return failure(EditError.ClipNotFound(clipId))
        if (!clip.hasMedia) return failure(EditError.InvalidAudio("only clips with media have audio settings"))
        audio.problem(clip.durationFrames)?.let { return failure(EditError.InvalidAudio(it)) }
        return updateClip(timeline, clipId) { it.copy(audio = audio) }
    }

    /** Replaces the mixer settings of a track (volume, mute, solo, ducking role, bus compressor). */
    fun setTrackAudio(timeline: Timeline, trackId: String, audio: TrackAudio): EditResult<Timeline> {
        val track = timeline.track(trackId) ?: return failure(EditError.TrackNotFound(trackId))
        audio.problem()?.let { return failure(EditError.InvalidAudio(it)) }
        return success(timeline.withTrack(track.copy(audio = audio)))
    }

    /** Turns sidechain ducking on, changes it, or removes it (null). */
    fun setDucking(timeline: Timeline, ducking: Ducking?): EditResult<Timeline> {
        ducking?.problem()?.let { return failure(EditError.InvalidAudio(it)) }
        return success(timeline.copy(ducking = ducking))
    }

    /** Sets transform and gain together, so one edit changes both or neither. */
    fun setAppearance(timeline: Timeline, clipId: String, transform: ClipTransform, gainDb: Double): EditResult<Timeline> {
        transform.problem()?.let { return failure(EditError.InvalidAppearance(it)) }
        ClipGain.problem(gainDb)?.let { return failure(EditError.InvalidAppearance(it)) }
        return updateClip(timeline, clipId) { it.copy(transform = transform, gainDb = gainDb) }
    }

    // --- Effects, blend mode and mask -------------------------------------------------------

    private fun fxTarget(timeline: Timeline, clipId: String): EditResult<Clip> {
        val track = timeline.trackOfClip(clipId) ?: return failure(EditError.ClipNotFound(clipId))
        if (track.type == TrackType.AUDIO) return failure(EditError.InvalidEffect("audio clips have no picture to change"))
        return EditResult.Success(checkNotNull(track.clip(clipId)))
    }

    private fun updateFx(timeline: Timeline, clipId: String, change: (ClipFx) -> ClipFx): EditResult<Timeline> {
        when (val target = fxTarget(timeline, clipId)) {
            is EditResult.Failure -> return target
            is EditResult.Success -> {
                val next = change(target.value.fx)
                next.problem()?.let { return failure(EditError.InvalidEffect(it)) }
                return updateClip(timeline, clipId) { it.copy(fx = next) }
            }
        }
    }

    /** Appends [effect] to the clip's chain (it runs after the ones already there). */
    fun addEffect(timeline: Timeline, clipId: String, effect: Effect): EditResult<Timeline> =
        updateFx(timeline, clipId) { it.copy(effects = it.effects + effect) }

    fun removeEffect(timeline: Timeline, clipId: String, effectId: String): EditResult<Timeline> {
        val target = fxTarget(timeline, clipId)
        if (target is EditResult.Failure) return target
        if ((target as EditResult.Success).value.fx.effect(effectId) == null) return failure(EditError.EffectNotFound(effectId))
        return updateFx(timeline, clipId) { fx -> fx.copy(effects = fx.effects.filter { it.id != effectId }) }
    }

    /** Replaces the values of one effect; its type does not change. */
    fun setEffectValues(timeline: Timeline, clipId: String, effectId: String, values: List<Double>): EditResult<Timeline> {
        val target = fxTarget(timeline, clipId)
        if (target is EditResult.Failure) return target
        if ((target as EditResult.Success).value.fx.effect(effectId) == null) return failure(EditError.EffectNotFound(effectId))
        return updateFx(timeline, clipId) { fx ->
            fx.copy(effects = fx.effects.map { if (it.id == effectId) it.copy(values = values) else it })
        }
    }

    /** Replaces the curves of a colour grade ([curves] null or identity means no curves); the values stay. */
    fun setEffectCurves(timeline: Timeline, clipId: String, effectId: String, curves: GradeCurves?): EditResult<Timeline> {
        val target = fxTarget(timeline, clipId)
        if (target is EditResult.Failure) return target
        val effect = (target as EditResult.Success).value.fx.effect(effectId) ?: return failure(EditError.EffectNotFound(effectId))
        if (effect.type != EffectType.COLOR_GRADE) return failure(EditError.InvalidEffect("only a colour grade has curves"))
        val stored = curves?.takeUnless { it.isIdentity }
        return updateFx(timeline, clipId) { fx ->
            fx.copy(effects = fx.effects.map { if (it.id == effectId) it.copy(curves = stored) else it })
        }
    }

    /**
     * Sets a colour grade in one step: values and curves of the clip's grade [effectId] when it exists, else
     * a new grade effect with that id is appended. This is how a look is applied and a grade is pasted.
     */
    fun setGrade(timeline: Timeline, clipId: String, effectId: String, values: List<Double>, curves: GradeCurves?): EditResult<Timeline> {
        val target = fxTarget(timeline, clipId)
        if (target is EditResult.Failure) return target
        val fx = (target as EditResult.Success).value.fx
        val stored = curves?.takeUnless { it.isIdentity }
        val existing = fx.effect(effectId)
        if (existing != null && existing.type != EffectType.COLOR_GRADE) {
            return failure(EditError.InvalidEffect("effect $effectId is not a colour grade"))
        }
        return updateFx(timeline, clipId) {
            if (existing == null) {
                it.copy(effects = it.effects + Effect(effectId, EffectType.COLOR_GRADE, values, stored))
            } else {
                it.copy(effects = it.effects.map { e -> if (e.id == effectId) e.copy(values = values, curves = stored) else e })
            }
        }
    }

    /** Moves an effect to [toIndex] in the chain (clamped), which changes how effects combine. */
    fun moveEffect(timeline: Timeline, clipId: String, effectId: String, toIndex: Int): EditResult<Timeline> {
        val target = fxTarget(timeline, clipId)
        if (target is EditResult.Failure) return target
        val effects = (target as EditResult.Success).value.fx.effects
        val effect = effects.firstOrNull { it.id == effectId } ?: return failure(EditError.EffectNotFound(effectId))
        val rest = effects.filter { it.id != effectId }
        val reordered = rest.toMutableList().apply { add(toIndex.coerceIn(0, rest.size), effect) }
        return updateFx(timeline, clipId) { it.copy(effects = reordered) }
    }

    /** Reads the clip's source as [space], or as its file says when null. Only clips that play a video file have a source colour. */
    fun setColorOverride(timeline: Timeline, clipId: String, space: SourceColorSpace?): EditResult<Timeline> {
        val clip = timeline.trackOfClip(clipId)?.clip(clipId) ?: return failure(EditError.ClipNotFound(clipId))
        if (space != null && (!clip.hasMedia || timeline.trackOfClip(clipId)?.type != TrackType.VIDEO)) {
            return failure(EditError.InvalidClip("only a video clip has a source colour space"))
        }
        return updateClip(timeline, clipId) { it.copy(colorOverride = space) }
    }

    fun setBlendMode(timeline: Timeline, clipId: String, mode: BlendMode): EditResult<Timeline> =
        updateFx(timeline, clipId) { it.copy(blendMode = mode) }

    /** Sets the mask, or removes it when [mask] is null. */
    fun setMask(timeline: Timeline, clipId: String, mask: ClipMask?): EditResult<Timeline> =
        updateFx(timeline, clipId) { it.copy(mask = mask) }

    /** Replaces the whole look at once (an inspector session commits this as one step). */
    fun setFx(timeline: Timeline, clipId: String, fx: ClipFx): EditResult<Timeline> = updateFx(timeline, clipId) { fx }

    /** Back to a plain clip: no effects, normal blend, no mask. */
    fun clearFx(timeline: Timeline, clipId: String): EditResult<Timeline> =
        updateFx(timeline, clipId) { ClipFx.NONE }

    /** Adds a keyframe at [keyframe].frame (clip frames), or replaces the one already there. */
    fun setKeyframe(timeline: Timeline, clipId: String, keyframe: Keyframe): EditResult<Timeline> {
        val clip = timeline.trackOfClip(clipId)?.clip(clipId) ?: return failure(EditError.ClipNotFound(clipId))
        if (timeline.trackOfClip(clipId)?.type == TrackType.AUDIO) return failure(EditError.InvalidKeyframe("audio clips have nothing to animate"))
        if (keyframe.frame < 0 || keyframe.frame >= clip.durationFrames) {
            return failure(EditError.InvalidKeyframe("the keyframe is outside the clip"))
        }
        keyframe.transform.problem()?.let { return failure(EditError.InvalidKeyframe(it)) }
        return updateClip(timeline, clipId) { it.copy(keyframes = Keyframes.set(it.keyframes, keyframe)) }
    }

    /**
     * Removes the keyframe at [frame]. When it was the last one the clip stops being animated and
     * keeps that keyframe's pose as its fixed transform, so nothing jumps.
     */
    fun removeKeyframe(timeline: Timeline, clipId: String, frame: Long): EditResult<Timeline> {
        val clip = timeline.trackOfClip(clipId)?.clip(clipId) ?: return failure(EditError.ClipNotFound(clipId))
        val removed = Keyframes.at(clip.keyframes, frame) ?: return failure(EditError.KeyframeNotFound(frame))
        return updateClip(timeline, clipId) { c ->
            val rest = c.keyframes.filter { it.frame != frame }
            c.copy(keyframes = rest, transform = if (rest.isEmpty()) removed.transform else c.transform)
        }
    }

    /** Moves a keyframe in time; a keyframe already at [toFrame] is replaced. */
    fun moveKeyframe(timeline: Timeline, clipId: String, fromFrame: Long, toFrame: Long): EditResult<Timeline> {
        val clip = timeline.trackOfClip(clipId)?.clip(clipId) ?: return failure(EditError.ClipNotFound(clipId))
        val key = Keyframes.at(clip.keyframes, fromFrame) ?: return failure(EditError.KeyframeNotFound(fromFrame))
        if (toFrame < 0 || toFrame >= clip.durationFrames) return failure(EditError.InvalidKeyframe("the keyframe is outside the clip"))
        return updateClip(timeline, clipId) {
            it.copy(keyframes = Keyframes.set(it.keyframes.filter { k -> k.frame != fromFrame }, key.copy(frame = toFrame)))
        }
    }

    /** Removes every keyframe. The clip keeps its current fixed [Clip.transform]. */
    fun clearKeyframes(timeline: Timeline, clipId: String): EditResult<Timeline> {
        if (timeline.trackOfClip(clipId)?.clip(clipId) == null) return failure(EditError.ClipNotFound(clipId))
        return updateClip(timeline, clipId) { it.copy(keyframes = emptyList()) }
    }

    /** Changes how the animation moves from the keyframe at [frame] to the next one. */
    fun setKeyframeInterpolation(timeline: Timeline, clipId: String, frame: Long, interpolation: Interpolation): EditResult<Timeline> {
        val clip = timeline.trackOfClip(clipId)?.clip(clipId) ?: return failure(EditError.ClipNotFound(clipId))
        val key = Keyframes.at(clip.keyframes, frame) ?: return failure(EditError.KeyframeNotFound(frame))
        return updateClip(timeline, clipId) { it.copy(keyframes = Keyframes.set(it.keyframes, key.copy(interpolation = interpolation))) }
    }

    /**
     * Rescales every clip position (and keyframe position) for a canvas that changed from
     * [oldWidth]x[oldHeight] to [newWidth]x[newHeight], so clips keep their relative place.
     */
    fun remapCanvas(timeline: Timeline, oldWidth: Int, oldHeight: Int, newWidth: Int, newHeight: Int): EditResult<Timeline> {
        if (oldWidth <= 0 || oldHeight <= 0 || newWidth <= 0 || newHeight <= 0) {
            return failure(EditError.InvalidAppearance("canvas sizes must be positive"))
        }
        val xRatio = newWidth.toDouble() / oldWidth
        val yRatio = newHeight.toDouble() / oldHeight
        val tracks = timeline.tracks.map { track ->
            track.copy(
                clips = track.clips.map {
                    it.copy(transform = it.transform.remapped(xRatio, yRatio), keyframes = Keyframes.remapped(it.keyframes, xRatio, yRatio))
                },
            )
        }
        return success(timeline.copy(tracks = tracks))
    }

    /**
     * Plays the clip at [num]/[den] times normal speed (0.1x to 8x): the clip keeps its source range
     * and its length becomes `range / speed`, rounded to whole frames. Animation keyframes and the
     * speed ramp are stretched with it. Getting longer must not run into the next clip unless
     * [ripple] is set, which moves every later clip on the track by the change (closing the gap when
     * the clip gets shorter).
     */
    fun setSpeed(timeline: Timeline, clipId: String, num: Long, den: Long, ripple: Boolean = false): EditResult<Timeline> {
        SpeedLimits.problem(num, den)?.let { return failure(EditError.InvalidSpeed(it)) }
        val track = timeline.trackOfClip(clipId) ?: return failure(EditError.ClipNotFound(clipId))
        val clip = track.clip(clipId) ?: return failure(EditError.ClipNotFound(clipId))
        if (!clip.hasMedia) return failure(EditError.InvalidSpeed("a title, photo or sticker has no media to speed up or slow down"))
        val span = clip.sourceSpan
        if (span == 1L) return failure(EditError.InvalidSpeed("a one-frame clip has no speed; change its length instead"))
        val newDuration = maxOf(1L, Math.floorDiv(span * den + num / 2, num))
        if (newDuration == clip.durationFrames) return success(timeline)
        val delta = newDuration - clip.durationFrames
        val updated = clip.copy(
            retimedFrames = newDuration.takeIf { it != span },
            keyframes = Keyframes.scaled(clip.keyframes, clip.durationFrames, newDuration),
            speedRamp = SpeedRamps.scaled(clip.speedRamp, clip.durationFrames, newDuration),
        )
        val others = track.clips.filter { it.id != clipId }
        val result = if (ripple) {
            others.map { if (it.timelineStart >= clip.timelineEnd) it.copy(timelineStart = it.timelineStart + delta) else it } + updated
        } else {
            others.firstOrNull { it.overlaps(updated) }?.let { return failure(EditError.Overlap(it.id)) }
            others + updated
        }
        return success(timeline.withTrack(track.withClips(result)).pruned())
    }

    /** Plays the clip's source range backwards (or forwards again). Position, length and animation stay. */
    fun setReverse(timeline: Timeline, clipId: String, reverse: Boolean): EditResult<Timeline> {
        val clip = timeline.trackOfClip(clipId)?.clip(clipId) ?: return failure(EditError.ClipNotFound(clipId))
        if (!clip.hasMedia) return failure(EditError.InvalidSpeed("a title, photo or sticker has no media to reverse"))
        return success(updateTimeline(timeline, clipId) { it.copy(reverse = reverse) }.pruned())
    }

    /**
     * Shapes the speed over the clip with [ramp] (empty removes it). The clip keeps its range and
     * length: only how the speed is spread over them changes.
     */
    fun setSpeedRamp(timeline: Timeline, clipId: String, ramp: List<SpeedKey>): EditResult<Timeline> {
        val clip = timeline.trackOfClip(clipId)?.clip(clipId) ?: return failure(EditError.ClipNotFound(clipId))
        if (!clip.hasMedia) return failure(EditError.InvalidSpeed("a title, photo or sticker has no media to ramp"))
        if (ramp.isNotEmpty() && clip.sourceSpan == 1L) return failure(EditError.InvalidSpeed("a one-frame clip has no speed to ramp"))
        SpeedRamps.problem(ramp, clip.durationFrames)?.let { return failure(EditError.InvalidSpeed(it)) }
        return success(updateTimeline(timeline, clipId) { it.copy(speedRamp = ramp) }.pruned())
    }

    /**
     * Holds the frame under [at] for [durationFrames]: the video clip there is split at [at] (unless
     * that is its first frame) and a still of that frame is put in between, as clip [freezeClipId]
     * (silent, since a single frame has no sound). The second half becomes [rightClipId] and it and
     * every later clip on the track move later by [durationFrames].
     */
    fun freezeFrame(
        timeline: Timeline,
        trackId: String,
        at: FrameIndex,
        durationFrames: Long,
        freezeClipId: String,
        rightClipId: String,
    ): EditResult<Timeline> {
        val track = timeline.track(trackId) ?: return failure(EditError.TrackNotFound(trackId))
        if (track.type != TrackType.VIDEO) return failure(EditError.InvalidClip("only video clips can be frozen"))
        if (durationFrames <= 0) return failure(EditError.InvalidClip("a freeze frame needs a positive length"))
        if (timeline.trackOfClip(freezeClipId) != null) return failure(EditError.DuplicateClipId(freezeClipId))
        val clip = track.clips.firstOrNull { at >= it.timelineStart && at < it.timelineEnd } ?: return failure(EditError.SplitOutsideClip)
        if (clip.assetId == null || !clip.hasMedia) return failure(EditError.InvalidClip("only clips with media can be frozen"))
        val offset = at - clip.timelineStart
        val splitting = offset > 0
        if (splitting && (freezeClipId == rightClipId || timeline.trackOfClip(rightClipId) != null)) {
            return failure(EditError.DuplicateClipId(rightClipId))
        }
        val frame = clip.retime.sourceFrameAt(offset)
        val still = Clip(
            id = freezeClipId,
            assetId = clip.assetId,
            timelineStart = at,
            sourceIn = FrameIndex(frame),
            sourceOut = FrameIndex(frame + 1),
            transform = clip.transformAt(offset),
            gainDb = clip.gainDb,
            fx = clip.fx,
            retimedFrames = durationFrames.takeIf { it != 1L },
        )
        val later = clip.cropped(offset, clip.durationFrames).copy(timelineStart = at + durationFrames)
        val right = if (splitting) later.withoutFadeIn().copy(id = rightClipId) else later
        val kept = track.clips.mapNotNull { other ->
            when {
                other.id == clip.id -> if (splitting) clip.cropped(0, offset).withoutFadeOut() else null
                other.timelineStart >= clip.timelineEnd -> other.copy(timelineStart = other.timelineStart + durationFrames)
                else -> other
            }
        }
        val carried = if (splitting) {
            timeline.transitions.map { if (it.fromClipId == clip.id) it.copy(fromClipId = rightClipId) else it }
        } else {
            timeline.transitions
        }
        return success(timeline.copy(transitions = carried).withTrack(track.withClips(kept + still + right)).pruned())
    }

    private fun updateTimeline(timeline: Timeline, clipId: String, change: (Clip) -> Clip): Timeline {
        val track = checkNotNull(timeline.trackOfClip(clipId))
        return timeline.withTrack(track.withClips(track.clips.map { if (it.id == clipId) change(it) else it }))
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
        // A fade handle stays on the side that holds the clip's own start or end; a cut inside it must not restart it.
        val left = clip.cropped(0, offset).withoutFadeOut()
        val rightRaw = clip.cropped(offset, clip.durationFrames).withoutFadeIn().copy(id = newClipId, timelineStart = at)
        // A title, photo or sticker has no media length, so [cropped] gives both halves a range starting at 0.
        val right = rightRaw
        // The right half is the one now adjacent to whatever followed the clip, so it inherits the
        // outgoing transition; the left half keeps the incoming one.
        val carried = timeline.transitions.map { if (it.fromClipId == clip.id) it.copy(fromClipId = newClipId) else it }
        return success(timeline.copy(transitions = carried).withTrack(track.withClips(track.clips - clip + left + right)).pruned())
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
                return success(withoutClip.withTrack(target.withClips(target.clips + moved)).pruned())
            }
            blocking = hit.id
        }
        return failure(EditError.Overlap(checkNotNull(blocking)))
    }

    private fun snappedStart(timeline: Timeline, clip: Clip, requested: FrameIndex, snap: Snap): FrameIndex? {
        val targets = buildList {
            add(FrameIndex.ZERO)
            snap.playhead?.let { add(it) }
            addAll(snap.extraTargets)
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
        Keyframes.problem(clip.keyframes, clip.durationFrames)?.let { return failure(EditError.InvalidClip(it)) }
        if (clip.sourceOut <= clip.sourceIn) return failure(EditError.InvalidClip("empty source range"))
        if (clip.retimedFrames != null && clip.retimedFrames == clip.sourceSpan) return failure(EditError.InvalidClip("a clip that is as long as its range is not retimed"))
        SpeedRamps.problem(clip.speedRamp, clip.durationFrames)?.let { return failure(EditError.InvalidClip(it)) }
        clip.fx.problem()?.let { return failure(EditError.InvalidClip(it)) }
        if (track.type == TrackType.TITLE && clip.title == null) return failure(EditError.InvalidClip("a title track only holds titles"))
        if (track.type != TrackType.TITLE && clip.title != null) return failure(EditError.InvalidClip("titles belong on a title track"))
        clip.title?.problem()?.let { return failure(EditError.InvalidClip(it)) }
        if (clip.still != null && (track.type != TrackType.VIDEO || clip.assetId == null || clip.title != null)) {
            return failure(EditError.InvalidClip("a photo or sticker belongs on a video track"))
        }
        val start = clip.timelineStart
        val end = clip.timelineEnd
        val result = mutableListOf<Clip>()
        for (existing in track.clips) {
            if (existing.timelineEnd <= start || existing.timelineStart >= end) {
                result += existing
                continue
            }
            if (existing.timelineStart < start) {
                val keptFrames = start - existing.timelineStart
                result += existing.cropped(0, keptFrames).withoutFadeOut()
            }
            if (existing.timelineEnd > end) {
                val cutFrames = end - existing.timelineStart
                result += existing.cropped(cutFrames, existing.durationFrames).withoutFadeIn().copy(id = "${existing.id}~${clip.id}", timelineStart = end)
            }
        }
        result += clip
        return success(timeline.withTrack(track.withClips(result)).pruned())
    }

    /** Removes a clip and shifts every later clip on its track left by the clip's duration. */
    fun rippleDelete(timeline: Timeline, clipId: String): EditResult<Timeline> {
        val track = timeline.trackOfClip(clipId) ?: return failure(EditError.ClipNotFound(clipId))
        val clip = track.clip(clipId) ?: return failure(EditError.ClipNotFound(clipId))
        val shifted = track.clips.filter { it.id != clipId }.map {
            if (it.timelineStart >= clip.timelineEnd) it.copy(timelineStart = it.timelineStart - clip.durationFrames) else it
        }
        return success(timeline.withTrack(track.withClips(shifted)).pruned())
    }

    /** Snaps a clip to the end of the clip before it on its track (frame 0 if first), closing the gap. */
    fun rippleAppend(timeline: Timeline, clipId: String): EditResult<Timeline> {
        val track = timeline.trackOfClip(clipId) ?: return failure(EditError.ClipNotFound(clipId))
        val clip = track.clip(clipId) ?: return failure(EditError.ClipNotFound(clipId))
        val previous = track.clips.lastOrNull { it.id != clipId && it.timelineStart < clip.timelineStart }
        val target = previous?.timelineEnd ?: FrameIndex.ZERO
        if (target == clip.timelineStart) return success(timeline)
        if (target > clip.timelineStart) return failure(EditError.Overlap(checkNotNull(previous).id))
        return success(
            timeline.withTrack(track.withClips(track.clips.map { if (it.id == clipId) it.copy(timelineStart = target) else it })).pruned(),
        )
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
        val newDuration = when (edge) {
            TrimEdge.START -> clip.durationFrames - (frame - clip.timelineStart)
            TrimEdge.END -> clip.durationFrames + (frame - clip.timelineEnd)
        }
        if (newDuration <= 0) return failure(EditError.InvalidTrim("clip would be empty"))
        val trimmed = when (edge) {
            TrimEdge.START -> clip.cropped(frame - clip.timelineStart, clip.durationFrames).copy(timelineStart = frame)
            TrimEdge.END -> clip.cropped(0, newDuration)
        }
        if (trimmed.durationFrames <= 0) return failure(EditError.InvalidTrim("clip would be empty"))
        if (trimmed.timelineStart < FrameIndex.ZERO) return failure(EditError.NegativeStart)
        // A title, photo or sticker has no source length: its range is only its length (see [cropped]), so it can grow freely.
        val result = trimmed
        if (result.sourceIn < FrameIndex.ZERO) return failure(EditError.SourceOutOfRange)
        if (sourceLength != null && clip.hasMedia && result.sourceOut.value > sourceLength) return failure(EditError.SourceOutOfRange)
        others.firstOrNull { it.overlaps(result) }?.let { return failure(EditError.Overlap(it.id)) }
        return success(timeline.withTrack(track.withClips(track.clips.map { if (it.id == clipId) result else it })).pruned())
    }

    /** Replaces the text content of a title clip. */
    fun setTitle(timeline: Timeline, clipId: String, title: TitleContent): EditResult<Timeline> {
        title.problem()?.let { return failure(EditError.InvalidAppearance(it)) }
        val clip = timeline.trackOfClip(clipId)?.clip(clipId) ?: return failure(EditError.ClipNotFound(clipId))
        val old = clip.title ?: return failure(EditError.NotATitle(clipId))
        // New text no longer matches the stored word timing: an animated caption gets evenly spaced words.
        val resolved = if (title.text != old.text && title.words == old.words && old.words.isNotEmpty()) {
            title.copy(words = if (title.animation == TitleAnimation.NONE) emptyList() else CaptionAnimator.synthesizeWords(title.text, clip.durationFrames))
        } else {
            title
        }
        return updateClip(timeline, clipId) { it.copy(title = resolved) }
    }

    /**
     * Adds [transition] across the cut it names. [outgoingSourceLength] is the length in source
     * frames of the outgoing clip's media (null for titles): the transition runs past that clip's
     * out point and needs that much media left.
     */
    fun addTransition(timeline: Timeline, transition: Transition, outgoingSourceLength: Long? = null): EditResult<Timeline> {
        if (timeline.transition(transition.id) != null) return failure(EditError.DuplicateTransitionId(transition.id))
        if (timeline.transitionBetween(transition.fromClipId, transition.toClipId) != null) {
            return failure(EditError.InvalidTransition("these clips already have a transition"))
        }
        val candidate = timeline.copy(transitions = timeline.transitions + transition)
        candidate.transitionProblem(transition)?.let { return failure(EditError.InvalidTransition(it)) }
        outgoingHandleProblem(candidate, transition, outgoingSourceLength)?.let { return failure(EditError.InvalidTransition(it)) }
        return success(candidate)
    }

    /** Changes the length of an existing transition, under the same limits as [addTransition]. */
    fun setTransitionDuration(
        timeline: Timeline,
        transitionId: String,
        durationFrames: Long,
        outgoingSourceLength: Long? = null,
    ): EditResult<Timeline> {
        val existing = timeline.transition(transitionId) ?: return failure(EditError.TransitionNotFound(transitionId))
        val changed = existing.copy(durationFrames = durationFrames)
        val candidate = timeline.copy(transitions = timeline.transitions.map { if (it.id == transitionId) changed else it })
        candidate.transitionProblem(changed)?.let { return failure(EditError.InvalidTransition(it)) }
        outgoingHandleProblem(candidate, changed, outgoingSourceLength)?.let { return failure(EditError.InvalidTransition(it)) }
        return success(candidate)
    }

    fun removeTransition(timeline: Timeline, transitionId: String): EditResult<Timeline> {
        if (timeline.transition(transitionId) == null) return failure(EditError.TransitionNotFound(transitionId))
        return success(timeline.copy(transitions = timeline.transitions.filter { it.id != transitionId }))
    }

    /**
     * The longest transition that fits across the cut from [fromClipId] to [toClipId] right now
     * (ignoring any transition already there), or 0 when not even the shortest one does. Longer
     * transitions only ever need more room, so this is a binary search.
     */
    fun maxTransitionFrames(timeline: Timeline, fromClipId: String, toClipId: String, outgoingSourceLength: Long? = null): Long {
        val others = timeline.copy(transitions = timeline.transitions.filterNot { it.fromClipId == fromClipId && it.toClipId == toClipId })
        val from = timeline.trackOfClip(fromClipId)?.clip(fromClipId) ?: return 0
        val to = timeline.trackOfClip(toClipId)?.clip(toClipId) ?: return 0
        fun fits(duration: Long): Boolean {
            val probe = Transition("probe", fromClipId, toClipId, duration)
            val candidate = others.copy(transitions = others.transitions + probe)
            return candidate.transitionProblem(probe) == null && outgoingHandleProblem(candidate, probe, outgoingSourceLength) == null
        }
        var high = from.durationFrames + to.durationFrames  // no transition can be longer than both clips together
        if (high < Transition.MIN_DURATION_FRAMES || !fits(Transition.MIN_DURATION_FRAMES)) return 0
        var low = Transition.MIN_DURATION_FRAMES  // fits
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (fits(mid)) low = mid else high = mid - 1
        }
        return low
    }

    private fun outgoingHandleProblem(timeline: Timeline, transition: Transition, sourceLength: Long?): String? {
        if (sourceLength == null) return null
        val from = timeline.trackOfClip(transition.fromClipId)?.clip(transition.fromClipId) ?: return null
        if (!from.hasMedia) return null
        val lastNeeded = from.retime.sourceFrameAt(from.durationFrames - 1 + transition.postFrames)
        return if (lastNeeded >= sourceLength || lastNeeded < 0) "outgoing clip has no media after its out point" else null
    }
}
