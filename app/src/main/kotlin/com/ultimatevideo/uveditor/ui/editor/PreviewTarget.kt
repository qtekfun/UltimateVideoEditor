package com.ultimatevideo.uveditor.ui.editor

import com.ultimatevideo.uveditor.domain.Clip
import com.ultimatevideo.uveditor.domain.FrameIndex
import com.ultimatevideo.uveditor.domain.Timeline
import com.ultimatevideo.uveditor.domain.TrackType

/** A clip showing at the playhead, and which frame of its source that is. */
data class PreviewTarget(val clip: Clip, val sourceFrame: Long)

/**
 * The clips the preview composites at [playhead], bottom layer first. Video tracks stack like a
 * mixer: the first video track in display order is on top, so it is drawn last. Tracks with
 * nothing under the playhead (a gap) contribute no layer. Source frames are in project frames, like
 * the clip's source range. Empty in a gap on every track or past the end, where the preview keeps
 * the last frame it drew.
 */
fun previewLayersAt(timeline: Timeline, playhead: FrameIndex): List<PreviewTarget> =
    timeline.tracks
        .filter { it.type == TrackType.VIDEO }
        .asReversed()
        .mapNotNull { track ->
            val clip = track.clips.firstOrNull { playhead >= it.timelineStart && playhead < it.timelineEnd }
            if (clip == null || clip.assetId == null) null else PreviewTarget(clip, (clip.sourceIn + (playhead - clip.timelineStart)).value)
        }

/** The topmost visible clip at [playhead], or null. */
fun previewTargetAt(timeline: Timeline, playhead: FrameIndex): PreviewTarget? = previewLayersAt(timeline, playhead).lastOrNull()
