package com.ultimatevideo.uveditor.data

import com.ultimatevideo.uveditor.data.model.BusCompressorDto
import com.ultimatevideo.uveditor.data.model.ClipAudioDto
import com.ultimatevideo.uveditor.data.model.ClipDto
import com.ultimatevideo.uveditor.data.model.ClipEqDto
import com.ultimatevideo.uveditor.data.model.DenoiseDto
import com.ultimatevideo.uveditor.data.model.VoiceFxDto
import com.ultimatevideo.uveditor.data.model.DuckingDto
import com.ultimatevideo.uveditor.data.model.EqBandDto
import com.ultimatevideo.uveditor.data.model.TrackAudioDto
import com.ultimatevideo.uveditor.domain.AudioRole
import com.ultimatevideo.uveditor.domain.BusCompressor
import com.ultimatevideo.uveditor.domain.ClipAudio
import com.ultimatevideo.uveditor.domain.FadeShape
import com.ultimatevideo.uveditor.domain.ClipEq
import com.ultimatevideo.uveditor.domain.Denoise
import com.ultimatevideo.uveditor.domain.VoiceFx
import com.ultimatevideo.uveditor.domain.VoicePreset
import com.ultimatevideo.uveditor.domain.Ducking
import com.ultimatevideo.uveditor.domain.EqBand
import com.ultimatevideo.uveditor.domain.TrackAudio
import com.ultimatevideo.uveditor.data.model.CurvePointDto
import com.ultimatevideo.uveditor.data.model.EffectDto
import com.ultimatevideo.uveditor.data.model.GradeCurvesDto
import com.ultimatevideo.uveditor.data.model.KeyframeDto
import com.ultimatevideo.uveditor.data.model.MarkerDto
import com.ultimatevideo.uveditor.data.model.AngleCutDto
import com.ultimatevideo.uveditor.data.model.MotionTrackDto
import com.ultimatevideo.uveditor.data.model.MulticamAngleDto
import com.ultimatevideo.uveditor.data.model.MulticamDto
import com.ultimatevideo.uveditor.domain.multicam.AngleCut
import com.ultimatevideo.uveditor.domain.multicam.MulticamAngle
import com.ultimatevideo.uveditor.domain.multicam.MulticamClip
import com.ultimatevideo.uveditor.domain.MotionTrack
import com.ultimatevideo.uveditor.domain.TrackSeed
import com.ultimatevideo.uveditor.data.model.MaskDto
import com.ultimatevideo.uveditor.data.model.MediaAssetDto
import com.ultimatevideo.uveditor.data.model.ProjectDto
import com.ultimatevideo.uveditor.data.model.SpeedKeyDto
import com.ultimatevideo.uveditor.data.model.StabiliseDto
import com.ultimatevideo.uveditor.data.model.TitleDto
import com.ultimatevideo.uveditor.data.model.TitleWordDto
import com.ultimatevideo.uveditor.domain.SourceColorSpace
import com.ultimatevideo.uveditor.domain.TitleAnimation
import com.ultimatevideo.uveditor.domain.TitleWord
import com.ultimatevideo.uveditor.data.model.TrackDto
import com.ultimatevideo.uveditor.data.model.TransformDto
import com.ultimatevideo.uveditor.data.model.TransitionDto
import com.ultimatevideo.uveditor.domain.Clip
import com.ultimatevideo.uveditor.domain.ClipTransform
import com.ultimatevideo.uveditor.domain.FrameIndex
import com.ultimatevideo.uveditor.domain.Interpolation
import com.ultimatevideo.uveditor.domain.BlendMode
import com.ultimatevideo.uveditor.domain.ClipFx
import com.ultimatevideo.uveditor.domain.ClipMask
import com.ultimatevideo.uveditor.domain.Effect
import com.ultimatevideo.uveditor.domain.CurvePoint
import com.ultimatevideo.uveditor.domain.EffectType
import com.ultimatevideo.uveditor.domain.GradeCurve
import com.ultimatevideo.uveditor.domain.GradeCurves
import com.ultimatevideo.uveditor.domain.Keyframe
import com.ultimatevideo.uveditor.domain.BezierHandle
import com.ultimatevideo.uveditor.domain.ParamKey
import com.ultimatevideo.uveditor.domain.ParamTrack
import com.ultimatevideo.uveditor.data.model.HandleDto
import com.ultimatevideo.uveditor.data.model.ParamKeyDto
import com.ultimatevideo.uveditor.data.model.ParamTrackDto
import com.ultimatevideo.uveditor.domain.Marker
import com.ultimatevideo.uveditor.domain.MarkerColor
import com.ultimatevideo.uveditor.domain.MarkerKind
import com.ultimatevideo.uveditor.domain.MarkerOps
import com.ultimatevideo.uveditor.domain.SpeedKey
import com.ultimatevideo.uveditor.domain.StabCrop
import com.ultimatevideo.uveditor.domain.Stabilise
import com.ultimatevideo.uveditor.domain.StillKind
import com.ultimatevideo.uveditor.domain.MaskShape
import com.ultimatevideo.uveditor.domain.TitleAlignment
import com.ultimatevideo.uveditor.domain.TitleContent
import com.ultimatevideo.uveditor.domain.TitleLayerEdit
import com.ultimatevideo.uveditor.domain.Timeline
import com.ultimatevideo.uveditor.domain.Track
import com.ultimatevideo.uveditor.domain.TrackType
import com.ultimatevideo.uveditor.domain.Transition
import com.ultimatevideo.uveditor.domain.TransitionDirection
import com.ultimatevideo.uveditor.domain.TransitionType

/**
 * Converts between the on-disk DTOs and the editing [Timeline]. The domain model carries
 * placement, source range, transform and gain; extras it does not model yet (colour override)
 * are carried over from the previously saved DTO.
 *
 * Source ranges are expressed in project frames (time-based), so a clip's timeline length equals
 * its source length at 1x speed.
 */
object TimelineMapper {

    /** Separator used by timeline ops when a clip is derived from another ("<parent>~<new>"). */
    private const val DERIVED_ID_SEPARATOR = '~'

    /** @throws ProjectError.Corrupt if the project holds an unknown track type or an invalid timeline. */
    fun toTimeline(project: ProjectDto): Timeline {
        val tracks = project.tracks.sortedBy { it.order }.map { track ->
            Track(
                id = track.id,
                type = trackType(track.type),
                clips = track.clips.map(::toClip).sortedBy { it.timelineStart },
                audio = track.audio?.let { toTrackAudio(track.id, it) } ?: TrackAudio.NONE,
            )
        }
        val timeline = Timeline(
            tracks,
            project.transitions.map(::toTransition),
            project.markers.map(::toMarker).sortedBy { it.frame },
            project.ducking?.let { toDucking(it) },
            project.motionTracks.map(::toMotionTrack),
            project.multicams.map(::toMulticam),
        )
        val violations = timeline.invariantViolations()
        if (violations.isNotEmpty()) throw ProjectError.Corrupt("invalid timeline: ${violations.first()}")
        return timeline
    }

    fun toDto(base: ProjectDto, timeline: Timeline, assets: List<MediaAssetDto>): ProjectDto {
        val previous = base.tracks.flatMap { it.clips }.associateBy { it.id }
        val tracks = timeline.tracks.mapIndexed { index, track ->
            TrackDto(
                id = track.id,
                type = trackTypeName(track.type),
                order = index,
                clips = track.clips.map { clip -> toClipDto(clip, prototype(previous, clip.id)) },
                audio = track.audio.takeUnless { it.isNeutral }?.let(::toTrackAudioDto),
            )
        }
        val transitions = timeline.transitions.map { toTransitionDto(it) }
        val markers = timeline.markers.map { MarkerDto(it.id, it.frame.value, markerKindName(it.kind), it.note, it.color?.name?.lowercase(), it.name) }
        return base.copy(
            mediaLibrary = assets,
            tracks = tracks,
            transitions = transitions,
            markers = markers,
            ducking = timeline.ducking?.let { DuckingDto(it.amountDb, it.thresholdDb, it.attackMs, it.releaseMs) },
            motionTracks = timeline.motionTracks.map { MotionTrackDto(it.id, it.clipId, it.name, it.seed.sourceFrame, it.seed.cx, it.seed.cy, it.seed.w, it.seed.h) },
            multicams = timeline.multicams.map(::toMulticamDto),
        )
    }

    private fun toMulticam(dto: MulticamDto) = MulticamClip(
        id = dto.id,
        name = dto.name,
        angles = dto.angles.map { MulticamAngle(it.id, it.name, it.assetId, it.offsetFrames, it.durationFrames) },
        audioAngle = dto.audioAngle,
        videoTrackId = dto.videoTrackId,
        audioTrackId = dto.audioTrackId,
        startFrame = dto.startFrame,
        inFrame = dto.inFrame,
        lengthFrames = dto.lengthFrames,
        cuts = dto.cuts.map { AngleCut(it.frame, it.angle) },
    ).also { g -> g.problem()?.let { throw ProjectError.Corrupt("invalid multicam clip: $it") } }

    private fun toMulticamDto(group: MulticamClip) = MulticamDto(
        id = group.id,
        name = group.name,
        angles = group.angles.map { MulticamAngleDto(it.id, it.name, it.assetId, it.offsetFrames, it.durationFrames) },
        audioAngle = group.audioAngle,
        videoTrackId = group.videoTrackId,
        audioTrackId = group.audioTrackId,
        startFrame = group.startFrame,
        inFrame = group.inFrame,
        lengthFrames = group.lengthFrames,
        cuts = group.cuts.map { AngleCutDto(it.frame, it.angle) },
    )

    private fun toMotionTrack(dto: MotionTrackDto) = MotionTrack(dto.id, dto.clipId, dto.name, TrackSeed(dto.seedFrame, dto.cx, dto.cy, dto.w, dto.h))
        .also { t -> t.problem()?.let { throw ProjectError.Corrupt("invalid motion track: $it") } }

    private fun toDucking(dto: DuckingDto) = Ducking(dto.amountDb, dto.thresholdDb, dto.attackMs, dto.releaseMs)
        .also { d -> d.problem()?.let { throw ProjectError.Corrupt("invalid ducking: $it") } }

    private fun toTrackAudio(trackId: String, dto: TrackAudioDto): TrackAudio {
        val role = AudioRole.entries.firstOrNull { it.name.lowercase() == dto.role }
            ?: throw ProjectError.Corrupt("track $trackId has unknown audio role '${dto.role}'")
        val compressor = dto.compressor?.let { BusCompressor(it.thresholdDb, it.ratio, it.attackMs, it.releaseMs, it.makeupDb) }
        return TrackAudio(dto.volumeDb, dto.mute, dto.solo, role, compressor)
            .also { a -> a.problem()?.let { throw ProjectError.Corrupt("track $trackId has invalid audio settings: $it") } }
    }

    private fun toTrackAudioDto(audio: TrackAudio) = TrackAudioDto(
        volumeDb = audio.volumeDb,
        mute = audio.mute,
        solo = audio.solo,
        role = audio.role.name.lowercase(),
        compressor = audio.compressor?.let { BusCompressorDto(it.thresholdDb, it.ratio, it.attackMs, it.releaseMs, it.makeupDb) },
    )

    private fun toClipAudio(clipId: String, dto: ClipAudioDto): ClipAudio {
        val eq = dto.eq?.let { e ->
            ClipEq(e.highPassHz, e.lowPassHz, if (e.bands.size == ClipEq.BAND_COUNT) e.bands.map { EqBand(it.freqHz, it.gainDb, it.q) } else ClipEq.DEFAULT_BANDS)
        } ?: ClipEq.FLAT
        val denoise = dto.denoise?.let { Denoise(it.strength, it.profile) }
        val voice = dto.voice?.let { v ->
            val preset = VoicePreset.entries.firstOrNull { it.name.lowercase() == v.preset }
                ?: throw ProjectError.Corrupt("clip $clipId has unknown voice effect '${v.preset}'")
            VoiceFx(preset, v.values)
        }
        return ClipAudio(dto.pan, dto.fadeInFrames, dto.fadeOutFrames, eq, denoise, dto.normalizeDb, dto.targetLufs, voice, FadeShape.fromId(dto.fadeShape))
            .also { a ->
                // Only the value ranges can be checked here; whether a fade fits the clip is part of the timeline invariants.
                val problem = if (a.fadeInFrames < 0 || a.fadeOutFrames < 0) "negative fade" else a.problem(Long.MAX_VALUE)
                if (problem != null) throw ProjectError.Corrupt("clip $clipId has invalid audio settings: $problem")
            }
    }

    private fun toClipAudioDto(audio: ClipAudio) = ClipAudioDto(
        pan = audio.pan,
        fadeInFrames = audio.fadeInFrames,
        fadeOutFrames = audio.fadeOutFrames,
        eq = audio.eq.takeUnless { it.isFlat }?.let { e ->
            ClipEqDto(e.highPassHz, e.lowPassHz, e.bands.map { EqBandDto(it.freqHz, it.gainDb, it.q) })
        },
        denoise = audio.denoise?.let { DenoiseDto(it.strength, it.profile) },
        normalizeDb = audio.normalizeDb,
        targetLufs = audio.targetLufs,
        voice = audio.voice?.let { VoiceFxDto(it.preset.name.lowercase(), it.values) },
        fadeShape = audio.fadeShape.takeIf { it != FadeShape.EQUAL_POWER }?.id,
    )

    private fun toMarker(dto: MarkerDto) = Marker(
        id = dto.id,
        frame = FrameIndex(dto.frame),
        kind = when (dto.kind) {
            "manual" -> MarkerKind.MANUAL
            "beat" -> MarkerKind.BEAT
            else -> throw ProjectError.Corrupt("marker ${dto.id} has unknown kind '${dto.kind}'")
        },
        note = dto.note?.takeIf { it.isNotBlank() },
        color = dto.color?.let { name -> MarkerColor.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } },
        name = dto.name?.trim()?.takeIf { it.isNotEmpty() }?.take(MarkerOps.MAX_NAME_LENGTH),
    )

    private fun markerKindName(kind: MarkerKind) = when (kind) {
        MarkerKind.MANUAL -> "manual"
        MarkerKind.BEAT -> "beat"
    }

    /** Extras of the clip itself, else of the clip it was derived from by a split or overwrite. */
    private fun prototype(previous: Map<String, ClipDto>, clipId: String): ClipDto? =
        previous[clipId] ?: previous[clipId.substringBefore(DERIVED_ID_SEPARATOR)]

    private fun toClip(dto: ClipDto) = Clip(
        id = dto.id,
        assetId = dto.assetId,
        timelineStart = FrameIndex(dto.timelineStartFrame),
        sourceIn = FrameIndex(dto.sourceInFrame),
        sourceOut = FrameIndex(dto.sourceOutFrame),
        transform = toTransform(dto.id, dto.transform),
        gainDb = dto.gainDb,
        title = dto.title?.let { toTitle(dto.id, it) },
        keyframes = dto.keyframes.map { toKeyframe(dto.id, it) },
        retimedFrames = dto.timelineFrames,
        reverse = dto.reverse,
        speedRamp = dto.speedRamp.map { SpeedKey(it.frame, it.weightPermille, it.smooth) },
        smoothSlowMo = dto.smoothSlowMo,
        fx = toFx(dto),
        still = dto.still?.let { toStill(dto.id, it) },
        colorOverride = SourceColorSpace.fromIdOrNull(dto.colorOverride),
        audio = dto.audio?.let { toClipAudio(dto.id, it) } ?: ClipAudio.NONE,
        stabilise = dto.stabilise?.let { toStabilise(dto.id, it) },
        params = dto.params.map { toParamTrack(dto.id, it) },
    )

    private fun toInterpolation(clipId: String, name: String): Interpolation = when (name) {
        "linear" -> Interpolation.LINEAR
        "ease" -> Interpolation.EASE
        "hold" -> Interpolation.HOLD
        "bezier" -> Interpolation.BEZIER
        else -> throw ProjectError.Corrupt("clip $clipId has a keyframe with unknown interpolation '$name'")
    }

    private fun toParamTrack(clipId: String, dto: ParamTrackDto) = ParamTrack(
        paramId = dto.paramId,
        keys = dto.keys.map {
            ParamKey(
                frame = it.frame,
                value = it.value,
                interpolation = toInterpolation(clipId, it.interpolation),
                out = it.out?.let { h -> BezierHandle(h.x, h.y) },
                inn = it.inn?.let { h -> BezierHandle(h.x, h.y) },
            )
        },
    )

    private fun toParamTrackDto(track: ParamTrack) = ParamTrackDto(
        paramId = track.paramId,
        keys = track.keys.map {
            ParamKeyDto(
                frame = it.frame,
                value = it.value,
                interpolation = it.interpolation.name.lowercase(),
                out = it.out?.let { h -> HandleDto(h.x, h.y) },
                inn = it.inn?.let { h -> HandleDto(h.x, h.y) },
            )
        },
    )

    private fun toStabilise(clipId: String, dto: StabiliseDto): Stabilise {
        val crop = StabCrop.fromId(dto.crop) ?: throw ProjectError.Corrupt("clip $clipId has unknown stabilise crop '${dto.crop}'")
        return Stabilise(strength = dto.strength, crop = crop).also { stabilise ->
            stabilise.problem()?.let { throw ProjectError.Corrupt("clip $clipId: $it") }
        }
    }

    private fun toStill(clipId: String, name: String): StillKind =
        StillKind.entries.firstOrNull { it.name.lowercase() == name }
            ?: throw ProjectError.Corrupt("clip $clipId has unknown still kind '$name'")

    private fun toFx(dto: ClipDto) = ClipFx(
        effects = dto.effects.map { toEffect(dto.id, it) },
        blendMode = BlendMode.entries.firstOrNull { it.name.lowercase() == dto.blendMode }
            ?: throw ProjectError.Corrupt("clip ${dto.id} has unknown blend mode '${dto.blendMode}'"),
        mask = dto.mask?.let { toMask(dto.id, it) },
    )

    private fun toEffect(clipId: String, dto: EffectDto) = Effect(
        id = dto.id,
        type = EffectType.entries.firstOrNull { it.name.lowercase() == dto.type }
            ?: throw ProjectError.Corrupt("clip $clipId has unknown effect '${dto.type}'"),
        values = dto.values,
        curves = dto.curves?.let(::toCurves),
    )

    internal fun toCurves(dto: GradeCurvesDto) = GradeCurves(
        master = toCurve(dto.master),
        red = toCurve(dto.red),
        green = toCurve(dto.green),
        blue = toCurve(dto.blue),
    )

    // An empty list is the identity curve, so a file that only lists the curves it changed still loads.
    private fun toCurve(points: List<CurvePointDto>) =
        if (points.isEmpty()) GradeCurve() else GradeCurve(points.map { CurvePoint(it.x, it.y) })

    internal fun toCurvesDto(curves: GradeCurves) = GradeCurvesDto(
        master = toCurvePoints(curves.master),
        red = toCurvePoints(curves.red),
        green = toCurvePoints(curves.green),
        blue = toCurvePoints(curves.blue),
    )

    private fun toCurvePoints(curve: GradeCurve) =
        if (curve.isIdentity) emptyList() else curve.points.map { CurvePointDto(it.x, it.y) }

    private fun toMask(clipId: String, dto: MaskDto) = ClipMask(
        shape = MaskShape.entries.firstOrNull { it.name.lowercase() == dto.shape }
            ?: throw ProjectError.Corrupt("clip $clipId has unknown mask shape '${dto.shape}'"),
        centerX = dto.centerX,
        centerY = dto.centerY,
        width = dto.width,
        height = dto.height,
        feather = dto.feather,
        invert = dto.invert,
    )

    private fun toEffectDto(effect: Effect) = EffectDto(
        effect.id,
        effect.type.name.lowercase(),
        effect.values,
        effect.curves?.takeUnless { it.isIdentity }?.let(::toCurvesDto),
    )

    private fun toMaskDto(mask: ClipMask) = MaskDto(
        shape = mask.shape.name.lowercase(),
        centerX = mask.centerX,
        centerY = mask.centerY,
        width = mask.width,
        height = mask.height,
        feather = mask.feather,
        invert = mask.invert,
    )

    private fun toKeyframe(clipId: String, dto: KeyframeDto) = Keyframe(
        frame = dto.frame,
        transform = toTransform(clipId, dto.transform),
        interpolation = toInterpolation(clipId, dto.interpolation),
        out = dto.out?.let { BezierHandle(it.x, it.y) },
        inn = dto.inn?.let { BezierHandle(it.x, it.y) },
    )

    private fun toKeyframeDto(key: Keyframe) = KeyframeDto(
        frame = key.frame,
        transform = toTransformDto(key.transform),
        interpolation = key.interpolation.name.lowercase(),
        out = key.out?.let { HandleDto(it.x, it.y) },
        inn = key.inn?.let { HandleDto(it.x, it.y) },
    )

    private fun toTitle(clipId: String, dto: TitleDto): TitleContent = TitleContent(
        text = dto.text,
        sizeFraction = dto.sizeFraction,
        colorArgb = parseColor(clipId, dto.color),
        alignment = when (dto.alignment) {
            "left" -> TitleAlignment.LEFT
            "center" -> TitleAlignment.CENTER
            "right" -> TitleAlignment.RIGHT
            else -> throw ProjectError.Corrupt("clip $clipId has unknown title alignment '${dto.alignment}'")
        },
        bold = dto.bold,
        outline = dto.outline,
        words = dto.words.map { TitleWord(it.text, it.start, it.end) },
        animation = when (dto.animation) {
            "none" -> TitleAnimation.NONE
            "karaoke" -> TitleAnimation.KARAOKE
            "pop_in" -> TitleAnimation.POP_IN
            "typewriter" -> TitleAnimation.TYPEWRITER
            else -> throw ProjectError.Corrupt("clip $clipId has unknown title animation '${dto.animation}'")
        },
        highlightArgb = parseColor(clipId, dto.highlight),
        layers = dto.layers.map { TitleLayerMapper.toLayer("clip $clipId", it) },
    ).let(TitleLayerEdit::synced)

    private fun parseColor(clipId: String, value: String): Int {
        val hex = value.removePrefix("#")
        val parsed = if (hex.length == COLOR_HEX_LENGTH) hex.toLongOrNull(HEX_RADIX) else null
        return parsed?.toInt() ?: throw ProjectError.Corrupt("clip $clipId has a malformed title colour '$value'")
    }

    private fun toTitleDto(title: TitleContent) = TitleDto(
        text = title.text,
        sizeFraction = title.sizeFraction,
        color = "#%08X".format(title.colorArgb),
        alignment = title.alignment.name.lowercase(),
        bold = title.bold,
        outline = title.outline,
        words = title.words.map { TitleWordDto(it.text, it.startFrame, it.endFrame) },
        animation = title.animation.name.lowercase(),
        highlight = "#%08X".format(title.highlightArgb),
        layers = title.layers.map(TitleLayerMapper::toDto),
    )

    private fun toTransition(dto: TransitionDto): Transition = Transition(
        id = dto.id,
        fromClipId = dto.fromClipId,
        toClipId = dto.toClipId,
        durationFrames = dto.durationFrames,
        type = TransitionType.entries.firstOrNull { transitionName(it) == dto.type }
            ?: throw ProjectError.Corrupt("transition ${dto.id} has unknown type '${dto.type}'"),
        direction = when (val name = dto.direction) {
            null -> TransitionDirection.LEFT
            else -> TransitionDirection.entries.firstOrNull { it.name.lowercase() == name }
                ?: throw ProjectError.Corrupt("transition ${dto.id} has unknown direction '$name'")
        },
    )

    /** The name a transition type has in `project.json`: lower case, words joined by a dash. */
    internal fun transitionName(type: TransitionType): String = type.name.lowercase().replace('_', '-')

    private fun toTransitionDto(transition: Transition) = TransitionDto(
        id = transition.id,
        type = transitionName(transition.type),
        fromClipId = transition.fromClipId,
        toClipId = transition.toClipId,
        durationFrames = transition.durationFrames,
        // Only a directional look writes its direction; files with a plain crossfade stay as they were.
        direction = transition.direction.name.lowercase().takeIf { transition.type.hasDirection },
    )

    private fun toTransform(clipId: String, dto: TransformDto): ClipTransform {
        if (dto.position.size != 2 || dto.scale.size != 2) throw ProjectError.Corrupt("clip $clipId has a malformed transform")
        return ClipTransform(
            positionX = dto.position[0],
            positionY = dto.position[1],
            scaleX = dto.scale[0],
            scaleY = dto.scale[1],
            rotationDegrees = dto.rotation,
            opacity = dto.opacity,
        )
    }

    private fun toTransformDto(transform: ClipTransform) = TransformDto(
        scale = listOf(transform.scaleX, transform.scaleY),
        rotation = transform.rotationDegrees,
        position = listOf(transform.positionX, transform.positionY),
        opacity = transform.opacity,
    )

    private fun toClipDto(clip: Clip, prototype: ClipDto?): ClipDto =
        (prototype ?: ClipDto(id = clip.id, timelineStartFrame = 0, sourceInFrame = 0, sourceOutFrame = 0)).copy(
            id = clip.id,
            assetId = clip.assetId,
            timelineStartFrame = clip.timelineStart.value,
            sourceInFrame = clip.sourceIn.value,
            sourceOutFrame = clip.sourceOut.value,
            transform = toTransformDto(clip.transform),
            gainDb = clip.gainDb,
            title = clip.title?.let(::toTitleDto),
            keyframes = clip.keyframes.map(::toKeyframeDto),
            timelineFrames = clip.retimedFrames,
            reverse = clip.reverse,
            speedRamp = clip.speedRamp.map { SpeedKeyDto(it.frame, it.weightPermille, it.smooth) },
            smoothSlowMo = clip.smoothSlowMo,
            effects = clip.fx.effects.map(::toEffectDto),
            blendMode = clip.fx.blendMode.name.lowercase(),
            mask = clip.fx.mask?.let(::toMaskDto),
            still = clip.still?.name?.lowercase(),
            colorOverride = clip.colorOverride?.id,
            audio = clip.audio.takeUnless { it.isNeutral }?.let(::toClipAudioDto),
            stabilise = clip.stabilise?.let { StabiliseDto(strength = it.strength, crop = it.crop.id) },
            params = clip.params.map(::toParamTrackDto),
        )

    private const val COLOR_HEX_LENGTH = 8
    private const val HEX_RADIX = 16

    private fun trackType(name: String): TrackType = when (name) {
        "video" -> TrackType.VIDEO
        "audio" -> TrackType.AUDIO
        "title" -> TrackType.TITLE
        else -> throw ProjectError.Corrupt("unknown track type '$name'")
    }

    private fun trackTypeName(type: TrackType): String = when (type) {
        TrackType.VIDEO -> "video"
        TrackType.AUDIO -> "audio"
        TrackType.TITLE -> "title"
    }
}
