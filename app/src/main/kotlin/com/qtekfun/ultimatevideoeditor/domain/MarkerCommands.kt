package com.qtekfun.ultimatevideoeditor.domain

/**
 * Adds one marker. Lives next to [EditCommand] because the interface is sealed. With [stick] a manual marker is anchored to the
 * clip under its frame when there is one (see [MarkerAnchors.anchoredAt]); otherwise it is free, as a marker always was.
 */
data class AddMarker(val marker: Marker, val stick: Boolean = false) : EditCommand {
    override fun apply(timeline: Timeline) =
        MarkerOps.add(timeline, if (stick && marker.kind == MarkerKind.MANUAL) MarkerAnchors.anchoredAt(timeline, marker) else marker)
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

/** Sets the note and colour of a marker in one undo step. */
data class AnnotateMarker(val markerId: String, val note: String?, val color: MarkerColor?) : EditCommand {
    override fun apply(timeline: Timeline) = MarkerOps.annotate(timeline, markerId, note, color)
}

/**
 * Sets the name, note and colour of a marker in one undo step (the marker popup commits its edit session with this).
 * [stick] sticks the marker to its clip (true), frees it (false) or leaves it as it is (null).
 */
data class EditMarker(
    val markerId: String,
    val name: String?,
    val note: String?,
    val color: MarkerColor?,
    val stick: Boolean? = null,
) : EditCommand {
    override fun apply(timeline: Timeline): EditResult<Timeline> {
        val updated = when (val result = MarkerOps.update(timeline, markerId, name, note, color)) {
            is EditResult.Failure -> return result
            is EditResult.Success -> result.value
        }
        return if (stick == null) EditResult.Success(updated) else MarkerOps.setStick(updated, markerId, stick)
    }
}

/** Moves a marker along the ruler in one undo step. */
data class MoveMarker(val markerId: String, val frame: FrameIndex) : EditCommand {
    override fun apply(timeline: Timeline) = MarkerOps.move(timeline, markerId, frame)
}
