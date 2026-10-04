package com.ultimatevideo.uveditor.domain

/**
 * Edits of single-parameter keyframes (SPECS.md 9.5). Every function returns a new [Timeline] or a typed
 * [EditError]. Pose parameters (`pose.*`) edit the clip's joint pose [Keyframe]s: setting one component at a
 * frame keeps the pose the clip has there for all the others, and removing a key removes the whole keyframe.
 */
object ParamOps {

    private fun clipOf(timeline: Timeline, clipId: String): Clip? = timeline.trackOfClip(clipId)?.clip(clipId)

    private fun update(timeline: Timeline, clipId: String, change: (Clip) -> Clip): EditResult<Timeline> {
        val track = timeline.trackOfClip(clipId) ?: return failure(EditError.ClipNotFound(clipId))
        return EditResult.Success(timeline.withTrack(track.withClips(track.clips.map { if (it.id == clipId) change(it) else it })))
    }

    private fun spec(clip: Clip, paramId: String): ParamSpec? = clip.paramSpec(paramId)

    /** Adds [key] to the track of [paramId], or replaces the key at the same frame. */
    fun setKey(timeline: Timeline, clipId: String, paramId: String, key: ParamKey): EditResult<Timeline> {
        val clip = clipOf(timeline, clipId) ?: return failure(EditError.ClipNotFound(clipId))
        val spec = spec(clip, paramId) ?: return failure(EditError.InvalidKeyframe("$paramId cannot be animated on this clip"))
        if (key.frame < 0 || key.frame >= clip.durationFrames) return failure(EditError.InvalidKeyframe("the keyframe is outside the clip"))
        if (!(key.value.isFinite() && key.value in spec.min..spec.max)) {
            return failure(EditError.InvalidKeyframe("${spec.label} must be between ${spec.min} and ${spec.max}"))
        }
        key.out?.problem()?.let { return failure(EditError.InvalidKeyframe(it)) }
        key.inn?.problem()?.let { return failure(EditError.InvalidKeyframe(it)) }
        val pose = PoseParams.byId(paramId)
        return if (pose != null) {
            val track = timeline.trackOfClip(clipId)
            if (track?.type == TrackType.AUDIO) return failure(EditError.InvalidKeyframe("audio clips have no pose to animate"))
            val transform = pose.write(clip.transformAt(key.frame), key.value)
            transform.problem()?.let { return failure(EditError.InvalidKeyframe(it)) }
            val existing = Keyframes.at(clip.keyframes, key.frame)
            val keyframe = Keyframe(key.frame, transform, key.interpolation, key.out ?: existing?.out, key.inn ?: existing?.inn)
            update(timeline, clipId) { it.copy(keyframes = Keyframes.set(it.keyframes, keyframe)) }
        } else {
            update(timeline, clipId) { it.copy(params = ParamTracks.withTrack(it.params, paramId, ParamTracks.set(it.paramKeys(paramId), key))) }
        }
    }

    /**
     * Removes the key at [frame]. When it was the last key of a parameter, the parameter stops being animated
     * and keeps that key's value as its fixed value, so nothing jumps.
     */
    fun removeKey(timeline: Timeline, clipId: String, paramId: String, frame: Long): EditResult<Timeline> {
        val clip = clipOf(timeline, clipId) ?: return failure(EditError.ClipNotFound(clipId))
        if (spec(clip, paramId) == null) return failure(EditError.InvalidKeyframe("$paramId is not a parameter of this clip"))
        if (PoseParams.byId(paramId) != null) return TimelineOps.removeKeyframe(timeline, clipId, frame)
        val removed = ParamTracks.at(clip.paramKeys(paramId), frame) ?: return failure(EditError.KeyframeNotFound(frame))
        return update(timeline, clipId) { c ->
            val rest = c.paramKeys(paramId).filter { it.frame != frame }
            val withTrack = c.copy(params = ParamTracks.withTrack(c.params, paramId, rest))
            if (rest.isEmpty()) withTrack.withStaticParam(paramId, removed.value) else withTrack
        }
    }

    /** Moves a key in time; a key already at [toFrame] is replaced. */
    fun moveKey(timeline: Timeline, clipId: String, paramId: String, fromFrame: Long, toFrame: Long): EditResult<Timeline> {
        val clip = clipOf(timeline, clipId) ?: return failure(EditError.ClipNotFound(clipId))
        if (spec(clip, paramId) == null) return failure(EditError.InvalidKeyframe("$paramId is not a parameter of this clip"))
        if (toFrame < 0 || toFrame >= clip.durationFrames) return failure(EditError.InvalidKeyframe("the keyframe is outside the clip"))
        if (PoseParams.byId(paramId) != null) return TimelineOps.moveKeyframe(timeline, clipId, fromFrame, toFrame)
        val key = ParamTracks.at(clip.paramKeys(paramId), fromFrame) ?: return failure(EditError.KeyframeNotFound(fromFrame))
        return update(timeline, clipId) { c ->
            val rest = c.paramKeys(paramId).filter { it.frame != fromFrame }
            c.copy(params = ParamTracks.withTrack(c.params, paramId, ParamTracks.set(rest, key.copy(frame = toFrame))))
        }
    }

    /** Stops animating [paramId]; the parameter goes back to its fixed value (the one its controls hold). */
    fun clearTrack(timeline: Timeline, clipId: String, paramId: String): EditResult<Timeline> {
        val clip = clipOf(timeline, clipId) ?: return failure(EditError.ClipNotFound(clipId))
        if (spec(clip, paramId) == null) return failure(EditError.InvalidKeyframe("$paramId is not a parameter of this clip"))
        if (PoseParams.byId(paramId) != null) return TimelineOps.clearKeyframes(timeline, clipId)
        return update(timeline, clipId) { it.copy(params = ParamTracks.withTrack(it.params, paramId, emptyList())) }
    }

    /**
     * Pastes [keys] (frames relative to the clip's start, already positioned by the caller) onto [paramId],
     * replacing keys at the same frames and keeping the rest. The values are clamped to the parameter's range
     * so keys copied from a parameter with a different range still land.
     */
    fun pasteKeys(timeline: Timeline, clipId: String, paramId: String, keys: List<ParamKey>): EditResult<Timeline> {
        val clip = clipOf(timeline, clipId) ?: return failure(EditError.ClipNotFound(clipId))
        val spec = spec(clip, paramId) ?: return failure(EditError.InvalidKeyframe("$paramId cannot be animated on this clip"))
        if (keys.isEmpty()) return EditResult.Success(timeline)
        var current = timeline
        for (key in keys.sortedBy { it.frame }) {
            val clamped = key.copy(value = key.value.coerceIn(spec.min, spec.max))
            current = when (val result = setKey(current, clipId, paramId, clamped)) {
                is EditResult.Success -> result.value
                is EditResult.Failure -> return result
            }
        }
        return EditResult.Success(current)
    }

    /** Sets the interpolation (and Bezier handles) of the key at [frame] without touching its value. */
    fun setKeyShape(
        timeline: Timeline,
        clipId: String,
        paramId: String,
        frame: Long,
        interpolation: Interpolation,
        out: BezierHandle? = null,
        inn: BezierHandle? = null,
    ): EditResult<Timeline> {
        val clip = clipOf(timeline, clipId) ?: return failure(EditError.ClipNotFound(clipId))
        if (spec(clip, paramId) == null) return failure(EditError.InvalidKeyframe("$paramId is not a parameter of this clip"))
        val handlesOk = listOfNotNull(out, inn).all { it.problem() == null }
        if (!handlesOk) return failure(EditError.InvalidKeyframe("invalid Bezier handle"))
        if (PoseParams.byId(paramId) != null) {
            val key = Keyframes.at(clip.keyframes, frame) ?: return failure(EditError.KeyframeNotFound(frame))
            return update(timeline, clipId) { it.copy(keyframes = Keyframes.set(it.keyframes, key.copy(interpolation = interpolation, out = out, inn = inn))) }
        }
        val key = ParamTracks.at(clip.paramKeys(paramId), frame) ?: return failure(EditError.KeyframeNotFound(frame))
        return update(timeline, clipId) { c ->
            c.copy(params = ParamTracks.withTrack(c.params, paramId, ParamTracks.set(c.paramKeys(paramId), key.copy(interpolation = interpolation, out = out, inn = inn))))
        }
    }

    private const val EPSILON = 1e-9

    /** Writes [value] of [paramId] at [frame] as a key that keeps the shape of the key already there. */
    private fun keyAt(timeline: Timeline, clipId: String, paramId: String, frame: Long, value: Double): EditResult<Timeline> {
        val clip = clipOf(timeline, clipId) ?: return failure(EditError.ClipNotFound(clipId))
        val existing = ParamTracks.at(clip.paramKeys(paramId), frame)
        return setKey(timeline, clipId, paramId, ParamKey(frame, value, existing?.interpolation ?: Interpolation.LINEAR, existing?.out, existing?.inn))
    }

    /**
     * Commits [edited], the effects the controls produced from what [Clip.displayedAt] showed at [frame]. An
     * effect value that is keyframed and changed becomes a key at [frame] (its fixed value stays the base); every
     * other value, and everything that is not a value (effects added or removed, curves, mask, blend), is taken
     * from [edited]. With a null [frame] (the playhead is outside the clip) keyframed values are left as they are.
     */
    fun setFxAt(timeline: Timeline, clipId: String, edited: ClipFx, frame: Long?): EditResult<Timeline> {
        val clip = clipOf(timeline, clipId) ?: return failure(EditError.ClipNotFound(clipId))
        val shown = if (frame == null) clip.fx else clip.fxAt(frame)
        val keys = ArrayList<Pair<String, Double>>()
        val effects = edited.effects.map { effect ->
            val before = clip.fx.effect(effect.id)
            if (before == null || before.type != effect.type || effect.values.size != before.values.size) return@map effect
            val shownValues = shown.effect(effect.id)?.values ?: before.values
            val values = effect.values.mapIndexed { i, v ->
                val id = ParamIds.fx(effect.id, i)
                if (ParamTracks.track(clip.params, id) == null) return@mapIndexed v
                if (frame != null && kotlin.math.abs(v - shownValues[i]) > EPSILON) keys += id to v
                before.values[i]
            }
            effect.copy(values = values)
        }
        var current = when (val result = TimelineOps.setFx(timeline, clipId, edited.copy(effects = effects))) {
            is EditResult.Success -> result.value
            is EditResult.Failure -> return result
        }
        for ((id, value) in keys) {
            current = when (val result = keyAt(current, clipId, id, checkNotNull(frame), value)) {
                is EditResult.Success -> result.value
                is EditResult.Failure -> return result
            }
        }
        return EditResult.Success(current)
    }

    /** Like [setFxAt] for the clip's audio block: pan and EQ band gains that are keyframed and changed become keys. */
    fun setClipAudioAt(timeline: Timeline, clipId: String, edited: ClipAudio, frame: Long?): EditResult<Timeline> {
        val clip = clipOf(timeline, clipId) ?: return failure(EditError.ClipNotFound(clipId))
        val shown = if (frame == null) clip.audio else clip.displayedAt(frame).audio
        val keys = ArrayList<Pair<String, Double>>()
        var audio = edited
        if (ParamTracks.track(clip.params, ParamIds.PAN) != null) {
            if (frame != null && kotlin.math.abs(edited.pan - shown.pan) > EPSILON) keys += ParamIds.PAN to edited.pan
            audio = audio.copy(pan = clip.audio.pan)
        }
        val bands = audio.eq.bands.mapIndexed { i, band ->
            val id = ParamIds.eqGain(i)
            if (ParamTracks.track(clip.params, id) == null) return@mapIndexed band
            val shownGain = shown.eq.bands.getOrNull(i)?.gainDb ?: band.gainDb
            if (frame != null && kotlin.math.abs(band.gainDb - shownGain) > EPSILON) keys += id to band.gainDb
            band.copy(gainDb = clip.audio.eq.bands.getOrNull(i)?.gainDb ?: band.gainDb)
        }
        audio = audio.copy(eq = audio.eq.copy(bands = bands))
        var current = when (val result = TimelineOps.setClipAudio(timeline, clipId, audio)) {
            is EditResult.Success -> result.value
            is EditResult.Failure -> return result
        }
        for ((id, value) in keys) {
            current = when (val result = keyAt(current, clipId, id, checkNotNull(frame), value)) {
                is EditResult.Success -> result.value
                is EditResult.Failure -> return result
            }
        }
        return EditResult.Success(current)
    }

    /** Sets the volume: a key at [frame] when the volume is keyframed (and [frame] is known), else the fixed gain. */
    fun setGainAt(timeline: Timeline, clipId: String, gainDb: Double, frame: Long?): EditResult<Timeline> {
        val clip = clipOf(timeline, clipId) ?: return failure(EditError.ClipNotFound(clipId))
        if (ParamTracks.track(clip.params, ParamIds.GAIN_DB) == null) return TimelineOps.setGain(timeline, clipId, gainDb)
        if (frame == null) return EditResult.Success(timeline)
        return keyAt(timeline, clipId, ParamIds.GAIN_DB, frame, gainDb)
    }
}

/**
 * The clip as its controls should show it [frame] frames after its start: effect values, volume, pan and EQ band
 * gains evaluated from their tracks, everything else as stored. [this] when [frame] is null or nothing is animated.
 */
fun Clip.displayedAt(frame: Long?): Clip {
    if (frame == null || params.isEmpty()) return this
    val bands = audio.eq.bands.mapIndexed { i, band -> band.copy(gainDb = paramValueAt(ParamIds.eqGain(i), frame) ?: band.gainDb) }
    return copy(
        fx = fxAt(frame),
        gainDb = paramValueAt(ParamIds.GAIN_DB, frame) ?: gainDb,
        audio = audio.copy(pan = paramValueAt(ParamIds.PAN, frame) ?: audio.pan, eq = audio.eq.copy(bands = bands)),
    )
}

/** This clip with the fixed value of [paramId] set to [value]; the clip when the parameter does not exist. */
internal fun Clip.withStaticParam(paramId: String, value: Double): Clip {
    PoseParams.byId(paramId)?.let { return copy(transform = it.write(transform, value)) }
    ParamIds.parseFx(paramId)?.let { (effectId, index) ->
        return copy(
            fx = fx.copy(
                effects = fx.effects.map { e ->
                    if (e.id == effectId && index in e.values.indices) e.copy(values = e.values.toMutableList().also { it[index] = value }) else e
                },
            ),
        )
    }
    return when (paramId) {
        ParamIds.GAIN_DB -> copy(gainDb = value)
        ParamIds.PAN -> copy(audio = audio.copy(pan = value))
        else -> ParamIds.parseEqBand(paramId)?.let { band ->
            if (band !in audio.eq.bands.indices) this else copy(
                audio = audio.copy(eq = audio.eq.copy(bands = audio.eq.bands.mapIndexed { i, b -> if (i == band) b.copy(gainDb = value) else b })),
            )
        } ?: this
    }
}

/**
 * This clip without the parameter tracks that no longer refer to anything (an effect that was removed, a value
 * index that does not exist), so editing the effect chain never leaves dangling animation behind.
 */
internal fun Clip.withoutDanglingParams(): Clip {
    if (params.isEmpty()) return this
    val kept = params.filter { !ParamIds.isPose(it.paramId) && paramSpec(it.paramId) != null }
    return if (kept.size == params.size) this else copy(params = kept)
}
