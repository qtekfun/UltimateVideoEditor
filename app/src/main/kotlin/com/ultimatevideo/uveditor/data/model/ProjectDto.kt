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
