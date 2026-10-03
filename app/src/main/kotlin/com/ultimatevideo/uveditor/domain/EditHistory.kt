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

    /** Deletes a clip: the base track closes the gap and other tracks follow, overlays leave a gap. */
    data class DeleteClip(val clipId: String) : EditCommand {
        override fun apply(timeline: Timeline) = ClipDeletion.delete(timeline, clipId)
    }

    data class RippleAppend(val clipId: String) : EditCommand {
        override fun apply(timeline: Timeline) = TimelineOps.rippleAppend(timeline, clipId)
    }

    data class SetTransform(val clipId: String, val transform: ClipTransform) : EditCommand {
        override fun apply(timeline: Timeline) = TimelineOps.setTransform(timeline, clipId, transform)
    }

    data class SetGain(val clipId: String, val gainDb: Double) : EditCommand {
        override fun apply(timeline: Timeline) = TimelineOps.setGain(timeline, clipId, gainDb)
    }

    /** Transform and gain in one undo step. */
    data class SetAppearance(val clipId: String, val transform: ClipTransform, val gainDb: Double) : EditCommand {
        override fun apply(timeline: Timeline) = TimelineOps.setAppearance(timeline, clipId, transform, gainDb)
    }

    data class AddEffect(val clipId: String, val effect: Effect) : EditCommand {
        override fun apply(timeline: Timeline) = TimelineOps.addEffect(timeline, clipId, effect)
    }

    data class RemoveEffect(val clipId: String, val effectId: String) : EditCommand {
        override fun apply(timeline: Timeline) = TimelineOps.removeEffect(timeline, clipId, effectId)
    }

    data class SetEffectValues(val clipId: String, val effectId: String, val values: List<Double>) : EditCommand {
        override fun apply(timeline: Timeline) = TimelineOps.setEffectValues(timeline, clipId, effectId, values)
    }

    data class MoveEffect(val clipId: String, val effectId: String, val toIndex: Int) : EditCommand {
        override fun apply(timeline: Timeline) = TimelineOps.moveEffect(timeline, clipId, effectId, toIndex)
    }

    data class SetBlendMode(val clipId: String, val mode: BlendMode) : EditCommand {
        override fun apply(timeline: Timeline) = TimelineOps.setBlendMode(timeline, clipId, mode)
    }

    data class SetMask(val clipId: String, val mask: ClipMask?) : EditCommand {
        override fun apply(timeline: Timeline) = TimelineOps.setMask(timeline, clipId, mask)
    }

    data class SetFx(val clipId: String, val fx: ClipFx) : EditCommand {
        override fun apply(timeline: Timeline) = TimelineOps.setFx(timeline, clipId, fx)
    }

    data class ClearFx(val clipId: String) : EditCommand {
        override fun apply(timeline: Timeline) = TimelineOps.clearFx(timeline, clipId)
    }

    data class ClearKeyframes(val clipId: String) : EditCommand {
        override fun apply(timeline: Timeline) = TimelineOps.clearKeyframes(timeline, clipId)
    }

    /** Several commands applied in order as one undo step; if any fails, none of them takes effect. */
    data class Batch(val commands: List<EditCommand>) : EditCommand {
        override fun apply(timeline: Timeline): EditResult<Timeline> {
            var current = timeline
            for (command in commands) {
                when (val result = command.apply(current)) {
                    is EditResult.Failure -> return result
                    is EditResult.Success -> current = result.value
                }
            }
            return EditResult.Success(current)
        }
    }

    data class SetKeyframe(val clipId: String, val keyframe: Keyframe) : EditCommand {
        override fun apply(timeline: Timeline) = TimelineOps.setKeyframe(timeline, clipId, keyframe)
    }

    data class RemoveKeyframe(val clipId: String, val frame: Long) : EditCommand {
        override fun apply(timeline: Timeline) = TimelineOps.removeKeyframe(timeline, clipId, frame)
    }

    data class MoveKeyframe(val clipId: String, val fromFrame: Long, val toFrame: Long) : EditCommand {
        override fun apply(timeline: Timeline) = TimelineOps.moveKeyframe(timeline, clipId, fromFrame, toFrame)
    }

    data class SetKeyframeInterpolation(val clipId: String, val frame: Long, val interpolation: Interpolation) : EditCommand {
        override fun apply(timeline: Timeline) = TimelineOps.setKeyframeInterpolation(timeline, clipId, frame, interpolation)
    }

    data class SetSpeed(val clipId: String, val num: Long, val den: Long, val ripple: Boolean = false) : EditCommand {
        override fun apply(timeline: Timeline) = TimelineOps.setSpeed(timeline, clipId, num, den, ripple)
    }

    data class SetReverse(val clipId: String, val reverse: Boolean) : EditCommand {
        override fun apply(timeline: Timeline) = TimelineOps.setReverse(timeline, clipId, reverse)
    }

    data class SetSpeedRamp(val clipId: String, val ramp: List<SpeedKey>) : EditCommand {
        override fun apply(timeline: Timeline) = TimelineOps.setSpeedRamp(timeline, clipId, ramp)
    }

    data class FreezeFrame(
        val trackId: String,
        val at: FrameIndex,
        val durationFrames: Long,
        val freezeClipId: String,
        val rightClipId: String,
    ) : EditCommand {
        override fun apply(timeline: Timeline) = TimelineOps.freezeFrame(timeline, trackId, at, durationFrames, freezeClipId, rightClipId)
    }

    data class SetTitle(val clipId: String, val title: TitleContent) : EditCommand {
        override fun apply(timeline: Timeline) = TimelineOps.setTitle(timeline, clipId, title)
    }

    data class AddTransition(val transition: Transition, val outgoingSourceLength: Long? = null) : EditCommand {
        override fun apply(timeline: Timeline) = TimelineOps.addTransition(timeline, transition, outgoingSourceLength)
    }

    data class SetTransitionDuration(
        val transitionId: String,
        val durationFrames: Long,
        val outgoingSourceLength: Long? = null,
    ) : EditCommand {
        override fun apply(timeline: Timeline) =
            TimelineOps.setTransitionDuration(timeline, transitionId, durationFrames, outgoingSourceLength)
    }

    data class RemoveTransition(val transitionId: String) : EditCommand {
        override fun apply(timeline: Timeline) = TimelineOps.removeTransition(timeline, transitionId)
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
