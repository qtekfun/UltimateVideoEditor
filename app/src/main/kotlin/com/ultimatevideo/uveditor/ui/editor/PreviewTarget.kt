package com.ultimatevideo.uveditor.ui.editor

import com.ultimatevideo.uveditor.domain.Clip
import com.ultimatevideo.uveditor.domain.FrameIndex
import com.ultimatevideo.uveditor.domain.Timeline
import com.ultimatevideo.uveditor.domain.TrackType

/** The clip whose source frame the preview must show, and which frame of its source that is. */
data class PreviewTarget(val clip: Clip, val sourceFrame: Long)

/**
 * Picks what the preview shows at [playhead]: the topmost video track (the first in display order)
 * that has a clip with media under the playhead. Returns null in a gap or past the end, where the preview
 * keeps the last frame it drew. Source frames are in project frames, like the clip's source range.
 */
fun previewTargetAt(timeline: Timeline, playhead: FrameIndex): PreviewTarget? {
    for (track in timeline.tracks.filter { it.type == TrackType.VIDEO }) {
        val clip = track.clips.firstOrNull { playhead >= it.timelineStart && playhead < it.timelineEnd } ?: continue
        if (clip.assetId == null) continue
        return PreviewTarget(clip, (clip.sourceIn + (playhead - clip.timelineStart)).value)
    }
    return null
}
