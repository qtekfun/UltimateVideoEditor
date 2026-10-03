package com.ultimatevideo.uveditor.domain

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
