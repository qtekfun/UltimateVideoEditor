package com.ultimatevideo.uveditor.domain

/** A reversible timeline edit. Applying is pure; inversion is done by [EditHistory] via snapshots. */
sealed interface EditCommand {
    fun apply(timeline: Timeline): EditResult<Timeline>

    data class AddTrack(val track: Track, val index: Int) : EditCommand {
        override fun apply(timeline: Timeline) = TimelineOps.addTrack(timeline, track, index)
    }

    data class RemoveTrack(val trackId: String) : EditCommand {
        override fun apply(timeline: Timeline) = TimelineOps.removeTrack(timeline, trackId)
    }

    data class Split(val trackId: String, val at: FrameIndex, val newClipId: String) : EditCommand {
        override fun apply(timeline: Timeline) = TimelineOps.split(timeline, trackId, at, newClipId)
    }

    data class Move(
        val clipId: String,
        val newStart: FrameIndex,
        val toTrackId: String? = null,
        val snap: Snap? = null,
    ) : EditCommand {
        override fun apply(timeline: Timeline) = TimelineOps.move(timeline, clipId, newStart, toTrackId, snap)
    }

    data class Overwrite(val trackId: String, val clip: Clip) : EditCommand {
        override fun apply(timeline: Timeline) = TimelineOps.overwrite(timeline, trackId, clip)
    }

    data class RippleDelete(val clipId: String) : EditCommand {
        override fun apply(timeline: Timeline) = TimelineOps.rippleDelete(timeline, clipId)
    }

    data class RippleAppend(val clipId: String) : EditCommand {
        override fun apply(timeline: Timeline) = TimelineOps.rippleAppend(timeline, clipId)
    }

    data class Trim(
        val clipId: String,
        val edge: TrimEdge,
        val frame: FrameIndex,
        val sourceLength: Long? = null,
    ) : EditCommand {
        override fun apply(timeline: Timeline) = TimelineOps.trim(timeline, clipId, edge, frame, sourceLength)
    }
}

/**
 * Immutable undo/redo history. Timelines are persistent values, so each entry stores the
 * before/after snapshots (structurally shared) and undo is exact by construction.
 * Only the most recent [limit] edits are undoable.
 */
class EditHistory private constructor(
    val timeline: Timeline,
    private val undoStack: List<Entry>,
    private val redoStack: List<Entry>,
    private val limit: Int,
) {
    private data class Entry(val command: EditCommand, val before: Timeline, val after: Timeline)

    constructor(timeline: Timeline, limit: Int = DEFAULT_LIMIT) : this(timeline, emptyList(), emptyList(), limit) {
        require(limit > 0) { "History limit must be positive" }
    }

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val undoDepth: Int get() = undoStack.size

    /** Applies [command]; a successful edit clears the redo stack, a failed one changes nothing. */
    fun execute(command: EditCommand): EditResult<EditHistory> = when (val result = command.apply(timeline)) {
        is EditResult.Failure -> result
        is EditResult.Success -> {
            val entry = Entry(command, timeline, result.value)
            EditResult.Success(EditHistory(result.value, (undoStack + entry).takeLast(limit), emptyList(), limit))
        }
    }

    fun undo(): EditHistory {
        val entry = undoStack.lastOrNull() ?: return this
        return EditHistory(entry.before, undoStack.dropLast(1), redoStack + entry, limit)
    }

    fun redo(): EditHistory {
        val entry = redoStack.lastOrNull() ?: return this
        return EditHistory(entry.after, undoStack + entry, redoStack.dropLast(1), limit)
    }

    companion object {
        const val DEFAULT_LIMIT = 100
    }
}
