package com.ultimatevideo.uveditor.data.model

import kotlinx.serialization.Serializable

/**
 * On-disk project schema (SPECS.md section 4). All timeline positions are integer frames;
 * fps is rational. These DTOs are deliberately independent from the `domain/` model.
 */
const val CURRENT_SCHEMA_VERSION = 1

@Serializable
data class ProjectDto(
    val version: Int = CURRENT_SCHEMA_VERSION,
    val id: String,
    val name: String,
    val settings: ProjectSettingsDto,
    val mediaLibrary: List<MediaAssetDto> = emptyList(),
    val tracks: List<TrackDto> = emptyList(),
    val transitions: List<TransitionDto> = emptyList(),
    /** Ruler markers; absent in projects written before markers existed. */
    val markers: List<MarkerDto> = emptyList(),
    /** Sidechain ducking of music tracks by voice tracks; absent means off. */
    val ducking: DuckingDto? = null,
    /** Tracking targets on video clips (SPECS.md 9.15); absent in projects written before motion tracking existed. */
    val motionTracks: List<MotionTrackDto> = emptyList(),
    /** Multicam groups (SPECS.md 9.9); absent in projects written before multicam existed. */
    val multicams: List<MulticamDto> = emptyList(),
)

/** A synchronised multi-angle clip; see `domain/multicam/Multicam.kt`. */
@Serializable
data class MulticamDto(
    val id: String,
    val name: String,
    val angles: List<MulticamAngleDto>,
    val audioAngle: Int,
    val videoTrackId: String,
    val audioTrackId: String? = null,
    val startFrame: Long,
    val inFrame: Long,
    val lengthFrames: Long,
    val cuts: List<AngleCutDto>,
)

@Serializable
data class MulticamAngleDto(val id: String, val name: String, val assetId: String, val offsetFrames: Long, val durationFrames: Long)

@Serializable
data class AngleCutDto(val frame: Long, val angle: Int)

/** A tracking target: where [clipId]'s picture was pointed at, in fractions of the upright frame. See `domain/MotionTrack`. */
@Serializable
data class MotionTrackDto(
    val id: String,
    val clipId: String,
    val name: String,
    val seedFrame: Long,
    val cx: Double,
    val cy: Double,
    val w: Double,
    val h: Double,
)

/** See `domain/Ducking`. */
@Serializable
data class DuckingDto(
    val amountDb: Double = 10.0,
    val thresholdDb: Double = -35.0,
    val attackMs: Double = 20.0,
    val releaseMs: Double = 400.0,
)

/** Audio tools of a clip (`domain/ClipAudio`); the whole block is absent for a clip that uses none. */
@Serializable
data class ClipAudioDto(
    val pan: Double = 0.0,
    val fadeInFrames: Long = 0,
    val fadeOutFrames: Long = 0,
    val eq: ClipEqDto? = null,
    val denoise: DenoiseDto? = null,
    val normalizeDb: Double = 0.0,
    val targetLufs: Double? = null,
)

@Serializable
data class ClipEqDto(
    val highPassHz: Double = 0.0,
    val lowPassHz: Double = 0.0,
    /** Low shelf, three peaking bands, high shelf. */
    val bands: List<EqBandDto> = emptyList(),
)

@Serializable
data class EqBandDto(
    val freqHz: Double,
    val gainDb: Double = 0.0,
    val q: Double = 1.0,
)

/** Noise suppression: [strength] and the 513-bin noise profile taken from a quiet region. */
@Serializable
data class DenoiseDto(
    val strength: Double,
    val profile: List<Float>,
)

/** Mixer settings of a track (`domain/TrackAudio`); absent for a neutral track. */
@Serializable
data class TrackAudioDto(
    val volumeDb: Double = 0.0,
    val mute: Boolean = false,
    val solo: Boolean = false,
    /** `normal`, `voice` or `music`. */
    val role: String = "normal",
    val compressor: BusCompressorDto? = null,
)

@Serializable
data class BusCompressorDto(
    val thresholdDb: Double = -18.0,
    val ratio: Double = 3.0,
    val attackMs: Double = 10.0,
    val releaseMs: Double = 120.0,
    val makeupDb: Double = 0.0,
)

/**
 * A ruler marker at [frame] project frames; [kind] is `manual` or `beat` (see `domain/Marker`).
 * [note] and [color] (`red`, `orange`, `yellow`, `green`, `blue` or `purple`) are optional labels.
 */
@Serializable
data class MarkerDto(
    val id: String,
    val frame: Long,
    val kind: String = "manual",
    val note: String? = null,
    val color: String? = null,
)

@Serializable
data class ProjectSettingsDto(
    val width: Int,
    val height: Int,
    val fpsNum: Int,
    val fpsDen: Int,
    val colorSpace: String,
)

@Serializable
data class MediaAssetDto(
    val id: String,
    val uri: String,
    val durationFrames: Long,
    val nativeFpsNum: Int,
    val nativeFpsDen: Int,
    val colorSpace: String,
    /** Defaults keep projects written before these fields existed valid. */
    val hasVideo: Boolean = true,
    val hasAudio: Boolean = true,
    /**
     * A still picture (photo). It has no frames of its own, so [durationFrames] is only the default
     * length a clip of it gets, [hasVideo] and [hasAudio] are false, and it is drawn from [uri] by
     * the picture path rather than decoded as video.
     */
    val isImage: Boolean = false,
    /**
     * The file's name as the picker showed it, kept so a missing file can be named to the user and
     * recognised when relinking (the URI of a lost file says nothing). Absent in older projects.
     */
    val displayName: String? = null,
    /** Free-form labels the user gave the file in the media library; absent in older projects. */
    val tags: List<String> = emptyList(),
    /** A short note about the file, shown in the media library; absent in older projects. */
    val note: String? = null,
)

@Serializable
data class TrackDto(
    val id: String,
    /** `video`, `audio` or `title`. */
    val type: String,
    val order: Int,
    val clips: List<ClipDto> = emptyList(),
    val audio: TrackAudioDto? = null,
)

@Serializable
data class ClipDto(
    val id: String,
    /** Null for title clips, which carry their own payload. */
    val assetId: String? = null,
    val timelineStartFrame: Long,
    val sourceInFrame: Long,
    val sourceOutFrame: Long,
    val transform: TransformDto = TransformDto(),
    val gainDb: Double = 0.0,
    val colorOverride: String? = null,
    /** Text payload; present exactly on clips of `title` tracks. */
    val title: TitleDto? = null,
    /** Animated pose in clip frames; empty means [transform] holds for the whole clip. */
    val keyframes: List<KeyframeDto> = emptyList(),
    /**
     * Length of the clip on the timeline when it is not the length of its source range (so the speed
     * is `(sourceOutFrame - sourceInFrame) / timelineFrames`); absent means normal speed. A one-frame
     * source range held for this many frames is a freeze frame.
     */
    val timelineFrames: Long? = null,
    /** Plays the source range backwards. */
    val reverse: Boolean = false,
    /** Relative speed over the clip; empty means constant speed. */
    val speedRamp: List<SpeedKeyDto> = emptyList(),
    /** Smooth slow motion: frames are blended with optical-flow interpolation where the clip plays slower than real time. */
    val smoothSlowMo: Boolean = false,
    /** Ordered shader effects; empty for a plain clip. */
    val effects: List<EffectDto> = emptyList(),
    /** `normal`, `add`, `multiply`, `screen` or `overlay`. */
    val blendMode: String = "normal",
    val mask: MaskDto? = null,
    /** `photo` or `sticker` for a still clip (then [assetId] is the picture or the built-in sticker id); absent otherwise. */
    val still: String? = null,
    /** Pan, fade handles, EQ, noise suppression and loudness normalisation; absent when unused. */
    val audio: ClipAudioDto? = null,
    /** Camera-shake correction; absent when off. The analysis it needs is a cache file, never part of the project. */
    val stabilise: StabiliseDto? = null,
    /** Keyframes of single parameters (effect values, volume, pan, EQ gains); absent when nothing is animated. */
    val params: List<ParamTrackDto> = emptyList(),
)

/** Settings of the stabiliser: [strength] 0..1 and [crop] (`tight`, `medium` or `full`). */
@Serializable
data class StabiliseDto(
    val strength: Double = 0.3,
    val crop: String = "medium",
)

/** Keyframes of one parameter; [paramId] is `fx.<effectId>.<index>`, `audio.gainDb`, `audio.pan` or `audio.eq.<band>.gainDb`. */
@Serializable
data class ParamTrackDto(
    val paramId: String,
    val keys: List<ParamKeyDto> = emptyList(),
)

/** [value] at [frame] clip frames; [interpolation] is `linear`, `ease`, `hold` or `bezier` (with optional handles). */
@Serializable
data class ParamKeyDto(
    val frame: Long,
    val value: Double,
    val interpolation: String = "linear",
    val out: HandleDto? = null,
    val inn: HandleDto? = null,
)

/** A Bezier handle in the unit square of a segment; see `domain/BezierHandle`. */
@Serializable
data class HandleDto(val x: Double, val y: Double)

/**
 * Relative speed ([weightPermille], 1000 = the clip's average) at [frame] clip frames; linear to the next
 * key, or eased (smoothstep) when [smooth] is set.
 */
@Serializable
data class SpeedKeyDto(
    val frame: Long,
    val weightPermille: Int = 1000,
    val smooth: Boolean = false,
)

/** One effect: [type] is the lower-case `EffectType` name, [values] follow that type's parameters. */
@Serializable
data class EffectDto(
    val id: String,
    val type: String,
    val values: List<Double> = emptyList(),
    /** Colour grade only; absent means identity curves. */
    val curves: GradeCurvesDto? = null,
)

/** The four tone curves of a colour grade; each is a list of control points (see `domain/GradeCurve`). */
@Serializable
data class GradeCurvesDto(
    val master: List<CurvePointDto> = emptyList(),
    val red: List<CurvePointDto> = emptyList(),
    val green: List<CurvePointDto> = emptyList(),
    val blue: List<CurvePointDto> = emptyList(),
)

@Serializable
data class CurvePointDto(val x: Double, val y: Double)

/** Shape mask in fractions of the layer box; see `domain/ClipMask`. [shape] is `rectangle` or `ellipse`. */
@Serializable
data class MaskDto(
    val shape: String = "rectangle",
    val centerX: Double = 0.0,
    val centerY: Double = 0.0,
    val width: Double = 0.6,
    val height: Double = 0.6,
    val feather: Double = 0.02,
    val invert: Boolean = false,
)

/**
 * A pose at [frame] clip frames after the clip's start. [interpolation] is how the animation moves
 * from this keyframe to the next: `linear`, `ease` or `hold`.
 */
@Serializable
data class KeyframeDto(
    val frame: Long,
    val transform: TransformDto = TransformDto(),
    val interpolation: String = "linear",
    /** Bezier handles of the segment leaving / arriving at this key; absent unless [interpolation] is `bezier`. */
    val out: HandleDto? = null,
    val inn: HandleDto? = null,
)

/**
 * Title text and style. [sizeFraction] is relative to the project height; [color] is `#AARRGGBB`;
 * [alignment] is `left`, `center` or `right`.
 */
@Serializable
data class TitleDto(
    val text: String,
    val sizeFraction: Double = 0.08,
    val color: String = "#FFFFFFFF",
    val alignment: String = "center",
    val bold: Boolean = false,
    val outline: Boolean = false,
    /** Timed words of a caption (clip frames); empty for plain titles. Optional so older projects load. */
    val words: List<TitleWordDto> = emptyList(),
    /** `none`, `karaoke`, `pop_in` or `typewriter`. */
    val animation: String = "none",
    /** Colour of the emphasised word, `#AARRGGBB`. */
    val highlight: String = "#FFFFE600",
    /** A multilayer title: the layers bottom to top. When not empty the single-text fields above are ignored (but [text] mirrors the first text layer). */
    val layers: List<TitleLayerDto> = emptyList(),
)

/** One word of a caption with its timing in clip frames. */
@Serializable
data class TitleWordDto(
    val text: String,
    val start: Long,
    val end: Long,
)

/**
 * Per-clip 2D transform. [position] is the offset of the clip centre from the canvas centre in
 * project pixels (+x right, +y down); [rotation] is clockwise degrees; [opacity] is 0..1.
 * The clip is first fitted into the canvas, then scaled/rotated/moved (see SPECS.md section 4).
 */
@Serializable
data class TransformDto(
    val scale: List<Double> = listOf(1.0, 1.0),
    val rotation: Double = 0.0,
    val position: List<Double> = listOf(0.0, 0.0),
    /** Defaults keep projects written before this field existed valid. */
    val opacity: Double = 1.0,
)

/** Transition across the cut where [toClipId] starts, centred on it; see `domain/Transition`. */
@Serializable
data class TransitionDto(
    val id: String,
    /** Only `crossfade` exists so far. */
    val type: String,
    val fromClipId: String,
    val toClipId: String,
    val durationFrames: Long,
)
