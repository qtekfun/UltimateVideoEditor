package com.ultimatevideo.uveditor.domain

/** Adds one marker. Lives next to [EditCommand] because the interface is sealed. */
data class AddMarker(val marker: Marker) : EditCommand {
    override fun apply(timeline: Timeline) = MarkerOps.add(timeline, marker)
}

data class RemoveMarker(val markerId: String) : EditCommand {
    override fun apply(timeline: Timeline) = MarkerOps.remove(timeline, markerId)
}

/** Replaces the beat markers inside [from, until) in one step, so one Undo restores the previous beats. */
data class SetBeatMarkers(
    val beats: List<Marker>,
    val from: FrameIndex = FrameIndex.ZERO,
    val until: FrameIndex = FrameIndex(Long.MAX_VALUE),
) : EditCommand {
    override fun apply(timeline: Timeline) = MarkerOps.setBeats(timeline, beats, from, until)
}
