package com.ultimatevideo.uveditor.domain

// Commands for detached audio. They live next to the other command files because EditCommand is sealed.
// Each one is a single undo step.

/** Detaches the sound of a video clip onto an audio lane (see [ClipLinks.detachAudio]). */
data class DetachAudio(
    val videoClipId: String,
    val audioClipId: String,
    val audioTrackId: String,
    val linked: Boolean = true,
) : EditCommand {
    override fun apply(timeline: Timeline) = ClipLinks.detachAudio(timeline, videoClipId, audioClipId, audioTrackId, linked)
}

/** Ends the link between a clip and its partner; both stay where they are. */
data class UnlinkClip(val clipId: String) : EditCommand {
    override fun apply(timeline: Timeline) = ClipLinks.unlink(timeline, clipId)
}

/** Links a video clip with an audio clip of the same source, optionally moving the audio back into sync first. */
data class RelinkClips(val videoClipId: String, val audioClipId: String, val realign: Boolean) : EditCommand {
    override fun apply(timeline: Timeline) = ClipLinks.relink(timeline, videoClipId, audioClipId, realign)
}

/** Gives a video clip its own sound back and removes the audio clip linked to it. */
data class RestoreEmbeddedAudio(val videoClipId: String) : EditCommand {
    override fun apply(timeline: Timeline) = ClipLinks.restoreEmbeddedAudio(timeline, videoClipId)
}

/**
 * Places a new video clip with [place] (any command that inserts or overwrites it) and, in the same undo step, detaches its
 * sound onto the first audio lane with room, or onto a new lane [newTrackId] (the "Put video audio on an audio track" setting,
 * SPECS 5.38). The lane is chosen after the placement has been settled, so audio clips that the placement pushed along a ripple
 * no longer count as being in the way. A clip without sound or without media is placed as it is.
 */
data class PlaceWithDetachedAudio(
    val place: EditCommand,
    val videoClipId: String,
    val audioClipId: String,
    val newTrackId: String,
) : EditCommand {
    override fun apply(timeline: Timeline): EditResult<Timeline> {
        val placed = when (val result = place.apply(timeline).linkedFrom(timeline)) {
            is EditResult.Failure -> return result
            is EditResult.Success -> result.value
        }
        val clip = placed.trackOfClip(videoClipId)?.clip(videoClipId) ?: return EditResult.Failure(EditError.ClipNotFound(videoClipId))
        val lane = ClipLinks.audioTrackFor(placed, clip) ?: newTrackId
        return ClipLinks.detachAudio(placed, videoClipId, audioClipId, lane, linked = true)
    }
}
