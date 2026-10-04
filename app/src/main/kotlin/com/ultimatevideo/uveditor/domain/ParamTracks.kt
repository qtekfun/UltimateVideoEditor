package com.ultimatevideo.uveditor.domain

/**
 * A keyframe of one animated parameter: [value] at [frame] clip frames after the clip's start, and how it
 * moves to the next key ([interpolation], with [out] / [inn] shaping a Bezier segment, see [BezierHandle]).
 */
data class ParamKey(
    val frame: Long,
    val value: Double,
    val interpolation: Interpolation = Interpolation.LINEAR,
    val out: BezierHandle? = null,
    val inn: BezierHandle? = null,
)

/** All the keyframes of one parameter of a clip, by increasing frame. [paramId] is built by [ParamIds]. */
data class ParamTrack(val paramId: String, val keys: List<ParamKey>)

/** A parameter's label and the range its values must stay in. */
data class ParamSpec(val id: String, val label: String, val min: Double, val max: Double)

/**
 * Names of the animatable parameters of a clip. They are plain strings so projects stay readable and
 * old builds ignore tracks they do not know:
 * - `fx.<effectId>.<valueIndex>`: a value of one of the clip's effects (colour grade included);
 * - `audio.gainDb`, `audio.pan`, `audio.eq.<band>.gainDb`: the clip's volume, balance and EQ band gains;
 * - `audio.voice.<sliderIndex>`: a slider of the clip's voice effect (index into [VoicePreset.sliders]);
 * - `pose.positionX`, `pose.positionY`, `pose.scaleX`, `pose.scaleY`, `pose.rotation`, `pose.opacity`: the
 *   pose, stored as the clip's joint [Keyframe]s and shown per parameter ([PoseParams]).
 */
object ParamIds {
    const val GAIN_DB = "audio.gainDb"
    const val PAN = "audio.pan"

    fun fx(effectId: String, valueIndex: Int) = "fx.$effectId.$valueIndex"

    fun eqGain(band: Int) = "audio.eq.$band.gainDb"

    fun voice(sliderIndex: Int) = "audio.voice.$sliderIndex"

    /** The slider index of an `audio.voice.` parameter, or null. */
    fun parseVoice(id: String): Int? =
        if (id.startsWith("audio.voice.")) id.removePrefix("audio.voice.").toIntOrNull()?.takeIf { it >= 0 } else null

    /** (effect id, value index) of an `fx.` parameter, or null. Effect ids may contain dots. */
    fun parseFx(id: String): Pair<String, Int>? {
        if (!id.startsWith("fx.")) return null
        val rest = id.removePrefix("fx.")
        val dot = rest.lastIndexOf('.')
        if (dot <= 0) return null
        val index = rest.substring(dot + 1).toIntOrNull() ?: return null
        return rest.substring(0, dot) to index
    }

    fun parseEqBand(id: String): Int? {
        if (!id.startsWith("audio.eq.") || !id.endsWith(".gainDb")) return null
        return id.removePrefix("audio.eq.").removeSuffix(".gainDb").toIntOrNull()
    }

    fun isPose(id: String) = id.startsWith("pose.")
}

private const val POSE_POSITION_LIMIT = 100_000.0
private const val POSE_ROTATION_LIMIT = 100_000.0

/** The pose parameters, their ranges and how each reads and writes a [ClipTransform]. */
enum class PoseParams(val id: String, val label: String, val min: Double, val max: Double) {
    POSITION_X("pose.positionX", "Position X", -POSE_POSITION_LIMIT, POSE_POSITION_LIMIT),
    POSITION_Y("pose.positionY", "Position Y", -POSE_POSITION_LIMIT, POSE_POSITION_LIMIT),
    SCALE_X("pose.scaleX", "Scale X", ClipTransform.MIN_SCALE, ClipTransform.MAX_SCALE),
    SCALE_Y("pose.scaleY", "Scale Y", ClipTransform.MIN_SCALE, ClipTransform.MAX_SCALE),
    ROTATION("pose.rotation", "Rotation", -POSE_ROTATION_LIMIT, POSE_ROTATION_LIMIT),
    OPACITY("pose.opacity", "Opacity", 0.0, 1.0),
    ;

    fun read(t: ClipTransform): Double = when (this) {
        POSITION_X -> t.positionX
        POSITION_Y -> t.positionY
        SCALE_X -> t.scaleX
        SCALE_Y -> t.scaleY
        ROTATION -> t.rotationDegrees
        OPACITY -> t.opacity
    }

    fun write(t: ClipTransform, value: Double): ClipTransform = when (this) {
        POSITION_X -> t.copy(positionX = value)
        POSITION_Y -> t.copy(positionY = value)
        SCALE_X -> t.copy(scaleX = value)
        SCALE_Y -> t.copy(scaleY = value)
        ROTATION -> t.copy(rotationDegrees = value)
        OPACITY -> t.copy(opacity = value)
    }

    companion object {
        fun byId(id: String): PoseParams? = entries.firstOrNull { it.id == id }
    }
}

/** Keyframe maths for parameter tracks, on integer clip frames; mirrors [Keyframes] for single values. */
object ParamTracks {

    /** Reason [keys] cannot belong to a clip of [durationFrames] frames whose values must lie in [spec]'s range, or null. */
    fun problem(spec: ParamSpec, keys: List<ParamKey>, durationFrames: Long): String? {
        if (keys.isEmpty()) return "a parameter track needs at least one keyframe"
        var previous = -1L
        for (key in keys) {
            if (key.frame < 0 || key.frame >= durationFrames) return "${spec.label} keyframe at ${key.frame} is outside the clip"
            if (key.frame <= previous) return "${spec.label} keyframes must be in strictly increasing order"
            if (!(key.value.isFinite() && key.value in spec.min..spec.max)) {
                return "${spec.label} at ${key.frame} must be between ${spec.min} and ${spec.max}"
            }
            key.out?.problem()?.let { return "${spec.label} at ${key.frame}: $it" }
            key.inn?.problem()?.let { return "${spec.label} at ${key.frame}: $it" }
            previous = key.frame
        }
        return null
    }

    /** The value at [frame]; [base] when there are no keys. Before the first key the first value holds, after the last the last. */
    fun evaluate(keys: List<ParamKey>, frame: Long, base: Double): Double {
        if (keys.isEmpty()) return base
        val first = keys.first()
        if (frame <= first.frame) return first.value
        val last = keys.last()
        if (frame >= last.frame) return last.value
        var index = 0
        while (keys[index + 1].frame <= frame) index++
        val from = keys[index]
        val to = keys[index + 1]
        if (from.frame == frame) return from.value
        val t = (frame - from.frame).toDouble() / (to.frame - from.frame).toDouble()
        val w = Keyframes.weight(from.interpolation, t, from.out, to.inn)
        return from.value + (to.value - from.value) * w
    }

    /**
     * The track as the audio mixer wants it: (frame, value) points that it interpolates linearly per sample.
     * A linear segment needs only its two keys; an ease or Bezier segment gets one point per frame (so the
     * mixer reproduces the curve at every frame); a hold keeps the earlier value until the last frame before
     * the next key. Frames strictly increase.
     */
    fun audioPoints(keys: List<ParamKey>): List<Pair<Long, Double>> {
        val points = ArrayList<Pair<Long, Double>>(keys.size)
        for ((i, key) in keys.withIndex()) {
            points += key.frame to key.value
            val next = keys.getOrNull(i + 1) ?: continue
            when (key.interpolation) {
                Interpolation.LINEAR -> Unit
                Interpolation.HOLD -> if (next.frame - 1 > key.frame) points += (next.frame - 1) to key.value
                Interpolation.EASE, Interpolation.BEZIER ->
                    for (frame in key.frame + 1 until next.frame) points += frame to evaluate(keys, frame, key.value)
            }
        }
        return points
    }

    fun at(keys: List<ParamKey>, frame: Long): ParamKey? = keys.firstOrNull { it.frame == frame }

    /** [keys] with [key] added, or replacing the key at the same frame. */
    fun set(keys: List<ParamKey>, key: ParamKey): List<ParamKey> = (keys.filter { it.frame != key.frame } + key).sortedBy { it.frame }

    fun previousFrame(keys: List<ParamKey>, frame: Long): Long? = keys.lastOrNull { it.frame < frame }?.frame

    fun nextFrame(keys: List<ParamKey>, frame: Long): Long? = keys.firstOrNull { it.frame > frame }?.frame

    /** The track [id] among [tracks], or null. */
    fun track(tracks: List<ParamTrack>, id: String): ParamTrack? = tracks.firstOrNull { it.paramId == id }

    /** [tracks] with the track [id] replaced by [keys]; an empty list removes it. Track order is kept, new tracks go last. */
    fun withTrack(tracks: List<ParamTrack>, id: String, keys: List<ParamKey>): List<ParamTrack> = when {
        keys.isEmpty() -> tracks.filter { it.paramId != id }
        tracks.any { it.paramId == id } -> tracks.map { if (it.paramId == id) it.copy(keys = keys) else it }
        else -> tracks + ParamTrack(id, keys)
    }

    /**
     * Re-bases [keys] onto the range [from, to) of the old clip's frames (frame 0 of the new clip is old frame
     * `from`; `from` may be negative and `to` past the end when a clip grows). The animation over the kept range
     * is unchanged: when keys outside the range shaped it, a key holding the value there is added at the new
     * start and/or end. A segment cut in the middle keeps its mode over what remains, so only an ease or a
     * Bezier changes shape slightly; linear and hold segments are exact. Returns empty if the range holds no
     * information (never for a non-empty [keys]).
     */
    fun cropped(keys: List<ParamKey>, from: Long, to: Long): List<ParamKey> {
        if (keys.isEmpty()) return keys
        val length = to - from
        val kept = keys.filter { it.frame in from until to }.map { it.copy(frame = it.frame - from) }.toMutableList()
        if (keys.any { it.frame < from } && kept.none { it.frame == 0L }) {
            val before = keys.last { it.frame < from }
            kept.add(0, ParamKey(0, evaluate(keys, from, before.value), before.interpolation, before.out))
        }
        val lastKept = kept.lastOrNull()?.frame ?: -1L
        if (keys.any { it.frame >= to } && lastKept < length - 1) {
            kept += ParamKey(length - 1, evaluate(keys, to - 1, keys.first().value), inn = keys.first { it.frame >= to }.inn)
        }
        return kept
    }

    fun croppedTracks(tracks: List<ParamTrack>, from: Long, to: Long): List<ParamTrack> {
        if (tracks.isEmpty()) return tracks
        return tracks.mapNotNull { track ->
            // A range that holds none of the keys and sits wholly on one side keeps the value held there.
            val keys = cropped(track.keys, from, to).ifEmpty {
                listOf(ParamKey(0, evaluate(track.keys, from.coerceIn(0, track.keys.last().frame), track.keys.first().value)))
            }
            ParamTrack(track.paramId, keys)
        }
    }

    /** [keys] stretched for a clip that went from [oldLength] to [newLength] frames; keys landing on one frame keep the earlier. */
    fun scaled(keys: List<ParamKey>, oldLength: Long, newLength: Long): List<ParamKey> {
        if (keys.isEmpty() || oldLength <= 0) return keys
        val result = ArrayList<ParamKey>(keys.size)
        for (key in keys) {
            val frame = (key.frame * newLength / oldLength).coerceIn(0, newLength - 1)
            if (result.isEmpty() || frame > result.last().frame) result += key.copy(frame = frame)
        }
        return result
    }

    fun scaledTracks(tracks: List<ParamTrack>, oldLength: Long, newLength: Long): List<ParamTrack> =
        tracks.map { it.copy(keys = scaled(it.keys, oldLength, newLength)) }

    /**
     * The keys of [source] shifted so the first lands at [at] (clip frames), clipped to a clip of
     * [durationFrames] frames: what pasting keyframes copied from another place does.
     */
    fun shiftedTo(source: List<ParamKey>, at: Long, durationFrames: Long): List<ParamKey> {
        if (source.isEmpty()) return source
        val offset = at - source.first().frame
        return source.map { it.copy(frame = it.frame + offset) }.filter { it.frame in 0 until durationFrames }
    }
}

/** Parameter specs and values of a clip. */
fun Clip.paramSpec(id: String): ParamSpec? {
    PoseParams.byId(id)?.let { return ParamSpec(it.id, it.label, it.min, it.max) }
    ParamIds.parseFx(id)?.let { (effectId, index) ->
        val effect = fx.effect(effectId) ?: return null
        val param = effect.type.params.getOrNull(index) ?: return null
        // The LUT's library key is picked, never animated.
        if (effect.type == EffectType.LUT && index == 0) return null
        return ParamSpec(id, "${effect.type.label} ${param.name.lowercase()}", param.min, param.max)
    }
    ParamIds.parseVoice(id)?.let { index ->
        val voice = audio.voice ?: return null
        val slider = voice.preset.sliders.getOrNull(index) ?: return null
        return ParamSpec(id, "${voice.preset.label} ${slider.name.lowercase()}", slider.min, slider.max)
    }
    return when (id) {
        ParamIds.GAIN_DB -> ParamSpec(id, "Volume", ClipGain.MIN_DB, ClipGain.MAX_DB)
        ParamIds.PAN -> ParamSpec(id, "Pan", -1.0, 1.0)
        else -> ParamIds.parseEqBand(id)?.takeIf { it in 0 until ClipEq.BAND_COUNT }?.let {
            ParamSpec(id, "EQ band ${it + 1} gain", EqBand.MIN_GAIN_DB, EqBand.MAX_GAIN_DB)
        }
    }
}

/** The value a parameter has when it is not animated (what the controls show without a keyframe). */
fun Clip.staticParamValue(id: String): Double? {
    PoseParams.byId(id)?.let { return it.read(transform) }
    ParamIds.parseFx(id)?.let { (effectId, index) -> return fx.effect(effectId)?.values?.getOrNull(index) }
    ParamIds.parseVoice(id)?.let { index -> return audio.voice?.values?.getOrNull(index) }
    return when (id) {
        ParamIds.GAIN_DB -> gainDb
        ParamIds.PAN -> audio.pan
        else -> ParamIds.parseEqBand(id)?.let { audio.eq.bands.getOrNull(it)?.gainDb }
    }
}

/** The keys of parameter [id]: the clip's own [params] track, or the pose keyframes projected onto the parameter. */
fun Clip.paramKeys(id: String): List<ParamKey> {
    PoseParams.byId(id)?.let { pose ->
        return keyframes.map { ParamKey(it.frame, pose.read(it.transform), it.interpolation, it.out, it.inn) }
    }
    return ParamTracks.track(params, id)?.keys.orEmpty()
}

/** The value of parameter [id] [relativeFrame] frames after the clip's start, animated or static; null for an unknown parameter. */
fun Clip.paramValueAt(id: String, relativeFrame: Long): Double? {
    val base = staticParamValue(id) ?: return null
    return ParamTracks.evaluate(paramKeys(id), relativeFrame, base)
}

/** Everything wrong with this clip's parameter tracks; empty when they are valid. */
fun Clip.paramProblems(): List<String> {
    if (params.isEmpty()) return emptyList()
    val problems = ArrayList<String>()
    val seen = HashSet<String>()
    for (track in params) {
        if (!seen.add(track.paramId)) {
            problems += "has two tracks for ${track.paramId}"
            continue
        }
        if (ParamIds.isPose(track.paramId)) {
            problems += "keeps ${track.paramId} in its pose keyframes, not in a parameter track"
            continue
        }
        val spec = paramSpec(track.paramId)
        if (spec == null) {
            problems += "animates ${track.paramId}, which does not exist on it"
            continue
        }
        ParamTracks.problem(spec, track.keys, durationFrames)?.let { problems += it }
    }
    return problems
}

/** True when any parameter of this clip is animated by a track (pose keyframes count separately). */
val Clip.hasParamTracks: Boolean get() = params.isNotEmpty()

/**
 * The effects of this clip with every animated value replaced by what it is [relativeFrame] frames after
 * the clip's start. The clip's own [Clip.fx] when nothing is animated.
 */
fun Clip.fxAt(relativeFrame: Long): ClipFx = fx.animatedAt(params, relativeFrame)

/** [this] with the tracks of [params] applied at [relativeFrame]; [this] when no track refers to an effect. */
fun ClipFx.animatedAt(params: List<ParamTrack>, relativeFrame: Long): ClipFx {
    if (params.isEmpty() || effects.isEmpty()) return this
    var changed = false
    val animated = effects.map { effect ->
        var values: MutableList<Double>? = null
        for (track in params) {
            val (effectId, index) = ParamIds.parseFx(track.paramId) ?: continue
            if (effectId != effect.id || index !in effect.values.indices) continue
            val v = values ?: effect.values.toMutableList().also { values = it }
            v[index] = ParamTracks.evaluate(track.keys, relativeFrame, effect.values[index])
        }
        values?.let { changed = true; effect.copy(values = it) } ?: effect
    }
    return if (changed) copy(effects = animated) else this
}
