package com.qtekfun.ultimatevideoeditor.domain.voice

import com.qtekfun.ultimatevideoeditor.domain.FrameIndex
import com.qtekfun.ultimatevideoeditor.domain.Timeline
import com.qtekfun.ultimatevideoeditor.domain.Track
import com.qtekfun.ultimatevideoeditor.domain.TrackType

/**
 * Where a voice-over take goes. A voice must never overwrite the music or the sound under it, so it takes the first audio lane that is
 * free for the whole take, and a new audio lane at the end when there is none. Pure, so it is tested without the editor.
 */
object VoicePlacement {
    /** [trackId] is the lane to put the take on; [newTrack] is set when that lane does not exist yet and must be added first, at the end. */
    data class Plan(val trackId: String, val newTrack: Track?)

    fun plan(timeline: Timeline, start: FrameIndex, lengthFrames: Long, newTrackId: String): Plan {
        val end = start + lengthFrames.coerceAtLeast(1)
        val free = timeline.tracks.firstOrNull { track ->
            track.type == TrackType.AUDIO && track.clips.none { clip -> clip.timelineStart < end && clip.timelineEnd > start }
        }
        return if (free != null) Plan(free.id, null) else Plan(newTrackId, Track(newTrackId, TrackType.AUDIO))
    }

    /**
     * How much of the start of a take to cut off, in frames: [leadMs] is how long the recorder was running before the timeline started
     * to play (those sounds belong before the playhead) plus [latencyMs], the delay the phone adds between the voice and the file.
     * Never so much that nothing is left: at least one frame stays out of [lengthFrames].
     */
    fun trimFrames(leadMs: Long, latencyMs: Long, framesPerSecond: Double, lengthFrames: Long): Long {
        if (lengthFrames <= 1) return 0
        val ms = (leadMs + latencyMs).coerceAtLeast(0)
        val frames = Math.round(ms * framesPerSecond / 1000.0)
        return frames.coerceIn(0, lengthFrames - 1)
    }
}
