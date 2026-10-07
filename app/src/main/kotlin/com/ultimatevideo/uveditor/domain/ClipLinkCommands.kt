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
