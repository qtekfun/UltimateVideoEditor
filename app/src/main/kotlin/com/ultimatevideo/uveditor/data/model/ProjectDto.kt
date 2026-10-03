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
)

@Serializable
data class TrackDto(
    val id: String,
    /** `video`, `audio` or `title`. */
    val type: String,
    val order: Int,
    val clips: List<ClipDto> = emptyList(),
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
    /** Ordered shader effects; empty for a plain clip. */
    val effects: List<EffectDto> = emptyList(),
    /** `normal`, `add`, `multiply`, `screen` or `overlay`. */
    val blendMode: String = "normal",
    val mask: MaskDto? = null,
    /** `photo` or `sticker` for a still clip (then [assetId] is the picture or the built-in sticker id); absent otherwise. */
    val still: String? = null,
)

/** Relative speed ([weightPermille], 1000 = the clip's average) at [frame] clip frames; linear between keys. */
@Serializable
data class SpeedKeyDto(
    val frame: Long,
    val weightPermille: Int = 1000,
)

/** One effect: [type] is the lower-case `EffectType` name, [values] follow that type's parameters. */
@Serializable
data class EffectDto(
    val id: String,
    val type: String,
    val values: List<Double> = emptyList(),
)

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
