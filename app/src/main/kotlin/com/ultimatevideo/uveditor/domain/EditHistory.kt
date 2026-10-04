package com.ultimatevideo.uveditor.domain

/** A reversible timeline edit. Applying is pure; inversion is done by [EditHistory] via snapshots. */
sealed interface EditCommand {
    fun apply(timeline: Timeline): EditResult<Timeline>

    /** Commits effects edited from what the controls showed at [frame]; keyframed values that changed become keys there. */
    data class SetFxAt(val clipId: String, val fx: ClipFx, val frame: Long?) : EditCommand {
        override fun apply(timeline: Timeline) = ParamOps.setFxAt(timeline, clipId, fx, frame)
    }

    /** Like [SetFxAt] for pan and EQ gains of the clip's audio block. */
    data class SetClipAudioAt(val clipId: String, val audio: ClipAudio, val frame: Long?) : EditCommand {
        override fun apply(timeline: Timeline) = ParamOps.setClipAudioAt(timeline, clipId, audio, frame)
    }

    /** Sets the volume: a key at [frame] when the volume is keyframed, else the fixed gain. */
    data class SetGainAt(val clipId: String, val gainDb: Double, val frame: Long?) : EditCommand {
        override fun apply(timeline: Timeline) = ParamOps.setGainAt(timeline, clipId, gainDb, frame)
    }

    /** Adds or replaces a keyframe of one parameter (effect value, volume, pan, EQ gain or a pose component). */
    data class SetParamKey(val clipId: String, val paramId: String, val key: ParamKey) : EditCommand {
        override fun apply(timeline: Timeline) = ParamOps.setKey(timeline, clipId, paramId, key)
    }

    data class RemoveParamKey(val clipId: String, val paramId: String, val frame: Long) : EditCommand {
        override fun apply(timeline: Timeline) = ParamOps.removeKey(timeline, clipId, paramId, frame)
    }

    data class MoveParamKey(val clipId: String, val paramId: String, val fromFrame: Long, val toFrame: Long) : EditCommand {
        override fun apply(timeline: Timeline) = ParamOps.moveKey(timeline, clipId, paramId, fromFrame, toFrame)
    }

    data class ClearParamTrack(val clipId: String, val paramId: String) : EditCommand {
        override fun apply(timeline: Timeline) = ParamOps.clearTrack(timeline, clipId, paramId)
    }

    /** Pastes copied keys onto a parameter (one undo step for the whole paste). */
    data class PasteParamKeys(val clipId: String, val paramId: String, val keys: List<ParamKey>) : EditCommand {
        override fun apply(timeline: Timeline) = ParamOps.pasteKeys(timeline, clipId, paramId, keys)
    }

    data class SetParamKeyShape(
        val clipId: String,
        val paramId: String,
        val frame: Long,
        val interpolation: Interpolation,
        val out: BezierHandle? = null,
        val inn: BezierHandle? = null,
    ) : EditCommand {
        override fun apply(timeline: Timeline) = ParamOps.setKeyShape(timeline, clipId, paramId, frame, interpolation, out, inn)
    }

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

    /** Inserts a clip into the base track at the nearest clip boundary, rippling later clips and overlays. */
    data class InsertBase(val clip: Clip, val at: FrameIndex) : EditCommand {
        override fun apply(timeline: Timeline) = MagneticBase.insert(timeline, clip, at)
    }

    /** Base-aware move: magnetic reorder on the base, insert from an overlay onto it, free-form between overlays. */
    data class MoveClip(
        val clipId: String,
        val newStart: FrameIndex,
        val toTrackId: String? = null,
        val snap: Snap? = null,
    ) : EditCommand {
        override fun apply(timeline: Timeline) = MagneticBase.move(timeline, clipId, newStart, toTrackId, snap)
    }

    /** Base-aware trim: on the base the following clips ripple and overlays follow. */
    data class TrimClip(
        val clipId: String,
        val edge: TrimEdge,
        val frame: FrameIndex,
        val sourceLength: Long? = null,
    ) : EditCommand {
        override fun apply(timeline: Timeline) = MagneticBase.trim(timeline, clipId, edge, frame, sourceLength)
    }

    /** Moves an overlay video clip onto a new lane above the others (one undo step). */
    data class MoveToNewLane(val clipId: String, val newStart: FrameIndex, val snap: Snap? = null) : EditCommand {
        override fun apply(timeline: Timeline) = LaneOps.moveToNewLane(timeline, clipId, newStart, snap)
    }

    /** Drops a clip on a lane replacing what it covers (base: length unchanged, overlays above cleared). */
    data class OverwriteMove(val clipId: String, val toTrackId: String, val newStart: FrameIndex) : EditCommand {
        override fun apply(timeline: Timeline) = LaneOps.overwriteMove(timeline, clipId, toTrackId, newStart)
    }

    /** Puts a clip that is not on the timeline yet on a new overlay lane above the others. */
    data class AddClipOnNewLane(val clip: Clip, val start: FrameIndex) : EditCommand {
        override fun apply(timeline: Timeline) = LaneOps.addClipOnNewLane(timeline, clip, start)
    }

    /** Drops a new clip on a lane replacing what it covers (base: overlays above the replaced part are cleared). */
    data class OverwriteNewClip(val clip: Clip, val trackId: String, val start: FrameIndex) : EditCommand {
        override fun apply(timeline: Timeline) = LaneOps.overwriteNewClip(timeline, clip, trackId, start)
    }

    /** Drops a clip into a cut of a non-base lane, shifting that lane's later clips right. */
    data class InsertOnLane(val clipId: String, val toTrackId: String, val at: FrameIndex) : EditCommand {
        override fun apply(timeline: Timeline) = LaneOps.insertOnLane(timeline, clipId, toTrackId, at)
    }

    /** Drops a new clip into a cut of a non-base lane, shifting that lane's later clips right. */
    data class InsertNewOnLane(val clip: Clip, val trackId: String, val at: FrameIndex) : EditCommand {
        override fun apply(timeline: Timeline) = LaneOps.insertNewOnLane(timeline, clip, trackId, at)
    }

    /** Lifts a base clip onto an overlay lane ([toTrackId]) or a new lane (null); the base closes the gap. */
    data class LiftFromBase(val clipId: String, val toTrackId: String?, val newStart: FrameIndex) : EditCommand {
        override fun apply(timeline: Timeline) = LaneOps.liftFromBase(timeline, clipId, toTrackId, newStart)
    }

    /** Reorders a lane among the lanes of its kind: [delta] -1 is up, +1 is down. */
    data class MoveTrack(val trackId: String, val delta: Int) : EditCommand {
        override fun apply(timeline: Timeline) = LaneOps.moveTrack(timeline, trackId, delta)
    }

    data class RippleAppend(val clipId: String) : EditCommand {
        override fun apply(timeline: Timeline) = TimelineOps.rippleAppend(timeline, clipId)
    }

    data class SetTransform(val clipId: String, val transform: ClipTransform) : EditCommand {
        override fun apply(timeline: Timeline) = TimelineOps.setTransform(timeline, clipId, transform)
    }

    data class SetClipAudio(val clipId: String, val audio: ClipAudio) : EditCommand {
        override fun apply(timeline: Timeline) = TimelineOps.setClipAudio(timeline, clipId, audio)
    }

    data class SetTrackAudio(val trackId: String, val audio: TrackAudio) : EditCommand {
        override fun apply(timeline: Timeline) = TimelineOps.setTrackAudio(timeline, trackId, audio)
    }

    data class SetDucking(val ducking: Ducking?) : EditCommand {
        override fun apply(timeline: Timeline) = TimelineOps.setDucking(timeline, ducking)
    }

    /** Remembers a tracking target on a video clip (SPECS.md 9.15); the analysis itself is not an edit. */
    data class AddMotionTrack(val track: MotionTrack) : EditCommand {
        override fun apply(timeline: Timeline) = MotionTrackOps.add(timeline, track)
    }

    data class RemoveMotionTrack(val trackId: String) : EditCommand {
        override fun apply(timeline: Timeline) = MotionTrackOps.remove(timeline, trackId)
    }

    /** Makes a clip follow a tracked path: replaces its keyframes with position keys built from the path; one undo step. */
    data class AttachToMotionTrack(val clipId: String, val keyframes: List<Keyframe>) : EditCommand {
        override fun apply(timeline: Timeline) = MotionTrackOps.attach(timeline, clipId, keyframes)
    }

    /** Turns the stabiliser on (or off with null) for a clip; one undo step. */
    data class SetStabilise(val clipId: String, val stabilise: Stabilise?) : EditCommand {
        override fun apply(timeline: Timeline) = TimelineOps.setStabilise(timeline, clipId, stabilise)
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

    data class SetEffectCurves(val clipId: String, val effectId: String, val curves: GradeCurves?) : EditCommand {
        override fun apply(timeline: Timeline) = TimelineOps.setEffectCurves(timeline, clipId, effectId, curves)
    }

    /** Values and curves of a colour grade in one undo step (apply a look, paste a grade). */
    data class SetGrade(val clipId: String, val effectId: String, val values: List<Double>, val curves: GradeCurves?) : EditCommand {
        override fun apply(timeline: Timeline) = TimelineOps.setGrade(timeline, clipId, effectId, values, curves)
    }

    data class MoveEffect(val clipId: String, val effectId: String, val toIndex: Int) : EditCommand {
        override fun apply(timeline: Timeline) = TimelineOps.moveEffect(timeline, clipId, effectId, toIndex)
    }

    /** Reads a clip's source as the given colour space; null goes back to what the file says. */
    data class SetColorOverride(val clipId: String, val space: SourceColorSpace?) : EditCommand {
        override fun apply(timeline: Timeline) = TimelineOps.setColorOverride(timeline, clipId, space)
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
        override fun apply(timeline: Timeline) =
            if (ripple) MagneticBase.setSpeed(timeline, clipId, num, den) else TimelineOps.setSpeed(timeline, clipId, num, den, ripple = false)
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

    /** Every timeline this history can still return to by undo or redo, plus the current one (for checks of what could come back). */
    fun reachableTimelines(): List<Timeline> =
        listOf(timeline) + undoStack.flatMap { listOf(it.before, it.after) } + redoStack.flatMap { listOf(it.before, it.after) }

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
