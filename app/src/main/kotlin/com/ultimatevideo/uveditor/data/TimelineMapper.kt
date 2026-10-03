package com.ultimatevideo.uveditor.data

import com.ultimatevideo.uveditor.data.model.ClipDto
import com.ultimatevideo.uveditor.data.model.MediaAssetDto
import com.ultimatevideo.uveditor.data.model.ProjectDto
import com.ultimatevideo.uveditor.data.model.TrackDto
import com.ultimatevideo.uveditor.domain.Clip
import com.ultimatevideo.uveditor.domain.FrameIndex
import com.ultimatevideo.uveditor.domain.Timeline
import com.ultimatevideo.uveditor.domain.Track
import com.ultimatevideo.uveditor.domain.TrackType

/**
 * Converts between the on-disk DTOs and the editing [Timeline]. The domain model only carries
 * what editing needs (placement and source range), so per-clip extras such as transform, gain
 * and colour override are carried over from the previously saved DTO.
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
        val timeline = Timeline(tracks)
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
        return base.copy(mediaLibrary = assets, tracks = tracks)
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
    )

    private fun toClipDto(clip: Clip, prototype: ClipDto?): ClipDto =
        (prototype ?: ClipDto(id = clip.id, timelineStartFrame = 0, sourceInFrame = 0, sourceOutFrame = 0)).copy(
            id = clip.id,
            assetId = clip.assetId,
            timelineStartFrame = clip.timelineStart.value,
            sourceInFrame = clip.sourceIn.value,
            sourceOutFrame = clip.sourceOut.value,
        )

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
