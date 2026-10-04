package com.ultimatevideo.uveditor.domain.templates

import com.ultimatevideo.uveditor.data.model.MediaAssetDto
import com.ultimatevideo.uveditor.domain.Clip
import com.ultimatevideo.uveditor.domain.ClipTransform
import com.ultimatevideo.uveditor.domain.FrameIndex
import com.ultimatevideo.uveditor.domain.MarkerKind
import com.ultimatevideo.uveditor.domain.StillKind
import com.ultimatevideo.uveditor.domain.Timeline
import com.ultimatevideo.uveditor.domain.TrackType

object TemplateBuilder {
    /**
     * Frames of source a slot clip carries before its in point. A template has no media, but a transition needs
     * footage before the incoming clip's in point to be valid, so the slot clips pretend to have it.
     */
    const val SLOT_HANDLE_FRAMES = 60L

    /** A video or audio slot clip: its range is the slot length after a [SLOT_HANDLE_FRAMES] handle. */
    fun slotClip(placeholder: Placeholder, start: Long): Clip {
        val photo = placeholder.kind == PlaceholderKind.PHOTO
        return Clip(
            id = "slot-${placeholder.id}",
            assetId = placeholder.slotAssetId,
            timelineStart = FrameIndex(start),
            sourceIn = FrameIndex(if (photo) 0L else SLOT_HANDLE_FRAMES),
            sourceOut = FrameIndex((if (photo) 0L else SLOT_HANDLE_FRAMES) + placeholder.frames),
            still = StillKind.PHOTO.takeIf { photo },
        )
    }

    /**
     * Makes a template out of a project: every clip that plays media (video, photo, audio) becomes a placeholder
     * of the same length in the same place, and everything else (titles, stickers, effects, grades, transitions,
     * keyframes, tracks, manual markers) is kept. The media itself, its speed changes, stabilisation, motion tracks
     * and multicam links are left out: they belong to the footage, not to the structure.
     */
    fun fromProject(
        id: String,
        name: String,
        description: String,
        width: Int,
        height: Int,
        fpsNum: Int,
        fpsDen: Int,
        colorSpace: String,
        timeline: Timeline,
        assets: List<MediaAssetDto>,
    ): ProjectTemplate {
        val byId = assets.associateBy { it.id }
        val placeholders = ArrayList<Placeholder>()
        val replaced = HashMap<String, Clip>()
        val ordered = timeline.tracks.flatMap { track -> track.clips.map { track to it } }.sortedBy { it.second.timelineStart }
        for ((track, clip) in ordered) {
            val isMedia = clip.hasMedia || clip.still == StillKind.PHOTO
            if (!isMedia || clip.assetId == null || Placeholder.idOfSlotAsset(clip.assetId) != null) continue
            val asset = byId[clip.assetId]
            val kind = when {
                clip.still == StillKind.PHOTO || asset?.isImage == true -> PlaceholderKind.PHOTO
                track.type == TrackType.AUDIO || asset?.hasVideo == false -> PlaceholderKind.AUDIO
                else -> PlaceholderKind.VIDEO_OR_PHOTO
            }
            val frames = clip.durationFrames
            val number = placeholders.count { it.kind == kind } + 1
            val placeholder = Placeholder(
                id = "ph${placeholders.size + 1}",
                name = "${kind.label} $number",
                kind = kind,
                frames = frames,
                minFrames = (frames / 2).coerceAtLeast(1),
            )
            placeholders += placeholder
            val slot = slotClip(placeholder, clip.timelineStart.value)
            replaced[clip.id] = slot.copy(
                id = clip.id,
                transform = ClipTransform.IDENTITY.copy(opacity = clip.transform.opacity),
                gainDb = clip.gainDb,
                keyframes = clip.keyframes,
                fx = clip.fx,
                audio = clip.audio,
                params = clip.params,
                colorOverride = null,
            )
        }
        val tracks = timeline.tracks.map { track -> track.copy(clips = track.clips.map { replaced[it.id] ?: it }) }
        val bare = Timeline(tracks = tracks, markers = timeline.markers.filter { it.kind == MarkerKind.MANUAL })
        // A transition survives only if it still holds with the slot clips' stand-in footage.
        val kept = timeline.transitions.filter { t -> bare.copy(transitions = listOf(t)).transitionProblem(t) == null }
        val structure = bare.copy(transitions = kept)
        return ProjectTemplate(id, name, description, width, height, fpsNum, fpsDen, colorSpace, structure, placeholders)
    }
}
