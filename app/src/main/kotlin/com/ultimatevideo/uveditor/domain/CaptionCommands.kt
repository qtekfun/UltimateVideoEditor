package com.ultimatevideo.uveditor.domain

import com.ultimatevideo.uveditor.domain.captions.CAPTION_ID_PREFIX
import com.ultimatevideo.uveditor.domain.captions.CaptionStyle
import com.ultimatevideo.uveditor.domain.captions.restyle

/**
 * Puts every generated caption (see [CAPTION_ID_PREFIX]) in [style] as one edit, so one Undo brings
 * the old look back. Text, timing and place on the timeline stay; position, animation and entrance
 * follow the style. Fails when the timeline has no captions.
 */
data class RestyleCaptions(val style: CaptionStyle, val canvasHeight: Int) : EditCommand {
    override fun apply(timeline: Timeline): EditResult<Timeline> {
        var changed = 0
        val tracks = timeline.tracks.map { track ->
            if (track.type != TrackType.TITLE) return@map track
            track.withClips(
                track.clips.map { clip ->
                    if (clip.id.startsWith(CAPTION_ID_PREFIX) && clip.title != null) {
                        changed++
                        style.restyle(clip, canvasHeight)
                    } else {
                        clip
                    }
                },
            )
        }
        if (changed == 0) return EditResult.Failure(EditError.InvalidClip("there are no captions to restyle"))
        return EditResult.Success(timeline.copy(tracks = tracks))
    }
}

/**
 * Puts a whole set of generated captions on the timeline as one edit, so one Undo removes them all.
 * Lives next to [EditCommand] because the interface is sealed; the planning logic is in
 * `domain.captions`.
 */
data class AddCaptions(val track: Track, val index: Int, val clips: List<Clip>) : EditCommand {
    override fun apply(timeline: Timeline): EditResult<Timeline> {
        var current = when (val added = TimelineOps.addTrack(timeline, track.copy(clips = emptyList()), index)) {
            is EditResult.Failure -> return added
            is EditResult.Success -> added.value
        }
        for (clip in clips) {
            current = when (val placed = TimelineOps.overwrite(current, track.id, clip)) {
                is EditResult.Failure -> return placed
                is EditResult.Success -> placed.value
            }
        }
        return EditResult.Success(current)
    }
}

/**
 * Puts captions on the caption track [trackId] that is already on the timeline, replacing whatever
 * the new clips cover, as one edit. Used for captions typed one at a time.
 */
data class AddCaptionsToTrack(val trackId: String, val clips: List<Clip>) : EditCommand {
    override fun apply(timeline: Timeline): EditResult<Timeline> {
        if (timeline.track(trackId) == null) return EditResult.Failure(EditError.TrackNotFound(trackId))
        var current = timeline
        for (clip in clips) {
            current = when (val placed = TimelineOps.overwrite(current, trackId, clip)) {
                is EditResult.Failure -> return placed
                is EditResult.Success -> placed.value
            }
        }
        return EditResult.Success(current)
    }
}
