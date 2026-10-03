package com.ultimatevideo.uveditor.data

import com.ultimatevideo.uveditor.data.model.ClipDto
import com.ultimatevideo.uveditor.data.model.MediaAssetDto
import com.ultimatevideo.uveditor.data.model.ProjectDto
import com.ultimatevideo.uveditor.data.model.TitleDto
import com.ultimatevideo.uveditor.data.model.TrackDto
import com.ultimatevideo.uveditor.data.model.TransformDto
import com.ultimatevideo.uveditor.data.model.TransitionDto
import com.ultimatevideo.uveditor.domain.Clip
import com.ultimatevideo.uveditor.domain.ClipTransform
import com.ultimatevideo.uveditor.domain.FrameIndex
import com.ultimatevideo.uveditor.domain.TitleAlignment
import com.ultimatevideo.uveditor.domain.TitleContent
import com.ultimatevideo.uveditor.domain.Timeline
import com.ultimatevideo.uveditor.domain.Track
import com.ultimatevideo.uveditor.domain.TrackType
import com.ultimatevideo.uveditor.domain.Transition
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
            )
        }
        val timeline = Timeline(tracks, project.transitions.map(::toTransition))
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
            )
        }
        val transitions = timeline.transitions.map { toTransitionDto(it) }
        return base.copy(mediaLibrary = assets, tracks = tracks, transitions = transitions)
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
    )

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
    )

    private fun toTransition(dto: TransitionDto): Transition = Transition(
        id = dto.id,
        fromClipId = dto.fromClipId,
        toClipId = dto.toClipId,
        durationFrames = dto.durationFrames,
        type = when (dto.type) {
            "crossfade" -> TransitionType.CROSSFADE
            else -> throw ProjectError.Corrupt("transition ${dto.id} has unknown type '${dto.type}'")
        },
    )

    private fun toTransitionDto(transition: Transition) = TransitionDto(
        id = transition.id,
        type = when (transition.type) {
            TransitionType.CROSSFADE -> "crossfade"
        },
        fromClipId = transition.fromClipId,
        toClipId = transition.toClipId,
        durationFrames = transition.durationFrames,
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
