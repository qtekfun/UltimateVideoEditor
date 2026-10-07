package com.ultimatevideo.uveditor.domain

// Commands for group edits. They live next to the other command files because EditCommand is sealed.
// Each one is a single undo step: EditHistory restores the timeline from the snapshot it took before.

/** Moves the clips by [deltaFrames] and [laneDelta] lanes, keeping their relative layout. */
data class GroupMove(val clipIds: List<String>, val deltaFrames: Long, val laneDelta: Int = 0) : EditCommand {
    override fun apply(timeline: Timeline) = GroupOps.move(timeline, clipIds, deltaFrames, laneDelta).linkedFrom(timeline)
}

data class GroupDelete(val clipIds: List<String>) : EditCommand {
    override fun apply(timeline: Timeline) = GroupOps.delete(timeline, clipIds)
}

data class GroupDuplicate(val clipIds: List<String>) : EditCommand {
    override fun apply(timeline: Timeline) = GroupOps.duplicate(timeline, clipIds)
}

/** Pastes [clipboard] with its earliest clip at [at]. */
data class GroupPaste(val clipboard: Clipboard, val at: FrameIndex) : EditCommand {
    override fun apply(timeline: Timeline) = GroupOps.paste(timeline, clipboard, at)
}

data class GroupPasteAttributes(
    val attributes: ClipAttributes,
    val clipIds: List<String>,
    val kinds: Set<AttributeKind> = AttributeKind.entries.toSet(),
) : EditCommand {
    override fun apply(timeline: Timeline) = GroupOps.pasteAttributes(timeline, attributes, clipIds, kinds)
}

data class GroupSetSpeed(val clipIds: List<String>, val num: Long, val den: Long) : EditCommand {
    override fun apply(timeline: Timeline) = GroupOps.setSpeed(timeline, clipIds, num, den)
}

data class GroupSetGain(val clipIds: List<String>, val gainDb: Double) : EditCommand {
    override fun apply(timeline: Timeline) = GroupOps.setGain(timeline, clipIds, gainDb)
}

data class GroupSetOpacity(val clipIds: List<String>, val opacity: Double) : EditCommand {
    override fun apply(timeline: Timeline) = GroupOps.setOpacity(timeline, clipIds, opacity)
}

data class GroupAlign(val clipIds: List<String>, val edge: AlignEdge) : EditCommand {
    override fun apply(timeline: Timeline) = GroupOps.align(timeline, clipIds, edge)
}

/**
 * Adds a transition to many clips (see [GroupOps.applyTransitions]). [sourceLengths] maps a clip id to
 * its media length in frames, which only the editor knows (it holds the media library).
 */
data class GroupTransitions(
    val clipIds: List<String>,
    val durationFrames: Long,
    val mode: GroupTransition,
    val sourceLengths: Map<String, Long> = emptyMap(),
) : EditCommand {
    override fun apply(timeline: Timeline) =
        GroupOps.applyTransitions(timeline, clipIds, durationFrames, mode) { clip -> sourceLengths[clip.id] }
}
