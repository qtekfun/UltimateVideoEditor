package com.qtekfun.ultimatevideoeditor.ui.editor.appearance

import com.qtekfun.ultimatevideoeditor.R
import com.qtekfun.ultimatevideoeditor.domain.Clip
import com.qtekfun.ultimatevideoeditor.domain.ClipGain
import com.qtekfun.ultimatevideoeditor.domain.ClipTransform
import com.qtekfun.ultimatevideoeditor.domain.EditCommand
import com.qtekfun.ultimatevideoeditor.domain.EditResult
import com.qtekfun.ultimatevideoeditor.domain.Interpolation
import com.qtekfun.ultimatevideoeditor.domain.Keyframe
import com.qtekfun.ultimatevideoeditor.domain.Keyframes
import com.qtekfun.ultimatevideoeditor.domain.ParamIds
import com.qtekfun.ultimatevideoeditor.domain.ParamTracks
import com.qtekfun.ultimatevideoeditor.domain.displayedAt
import com.qtekfun.ultimatevideoeditor.ui.editor.*
import com.qtekfun.ultimatevideoeditor.ui.editor.timeline.TimelineDragHandler
import com.qtekfun.ultimatevideoeditor.ui.text.UiText

/**
 * The inspector's transform and gain controls and the preview gestures: an edit in progress is shown live and committed as one undo step
 * when it ends.
 */
internal class AppearanceHandler(
    host: EditorHost,
    private val timelineDrag: TimelineDragHandler,
) : EditorHost by host {

    /** An inspector or preview-gesture edit in progress: shown live, committed as one undo step on release. */
    private class AppearanceSession(
        val clipId: String,
        val baseTransform: ClipTransform,
        val baseGain: Double,
        var transform: ClipTransform,
        var gainDb: Double,
        /** The clip frame a pose edit is keyed at when the clip is animated; null for a fixed transform. */
        val keyFrame: Long? = null,
    )

    private var appearance: AppearanceSession? = null

    /** Forgets the edit in progress without committing it (the timeline changed under it). */
    fun discard() {
        appearance = null
    }

    fun toggleInspector() {
        if (state.value.inspectorOpen) endAppearance(commit = true)
        reduce { copy(inspectorOpen = !inspectorOpen) }
    }

    /** Starts an edit session on the selected clip. Returns false (with a message) if there is nothing to edit. */
    fun beginAppearance(): Boolean {
        if (appearance != null) return true
        if (timelineDrag.isDragging) return false
        val clipId = state.value.selectedClipId
        val clip = clipId?.let { history.timeline.trackOfClip(it)?.clip(it) }
        if (clip == null) {
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_select_a_clip_first)))
            return false
        }
        // An animated clip is edited at the playhead: the edit becomes a keyframe there.
        val keyFrame = if (clip.keyframes.isEmpty()) null else state.value.selectedClipFrame
        if (clip.keyframes.isNotEmpty() && keyFrame == null) {
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_move_the_playhead_inside_the_3)))
            return false
        }
        val pose = if (keyFrame != null) clip.transformAt(keyFrame) else clip.transform
        // The volume slider shows the keyframed volume at the playhead when there is one.
        val gain = clip.displayedAt(state.value.selectedFrame).gainDb
        appearance = AppearanceSession(clip.id, pose, gain, pose, gain, keyFrame)
        return true
    }

    /** The command that sets the volume: a key at the playhead when the volume is keyframed, else the fixed gain. */
    private fun gainCommand(clip: Clip, gainDb: Double): EditCommand =
        if (ParamTracks.track(clip.params, ParamIds.GAIN_DB) != null) {
            EditCommand.SetGainAt(clip.id, gainDb, state.value.selectedFrame)
        } else {
            EditCommand.SetGain(clip.id, gainDb)
        }

    /**
     * The edit an appearance session stands for. A fixed clip gets its transform and gain replaced;
     * an animated one gets a keyframe at the session's frame (only when the pose changed, so a gain
     * edit never adds one) and the new gain.
     */
    private fun sessionCommand(session: AppearanceSession): EditCommand {
        val clip = history.timeline.trackOfClip(session.clipId)?.clip(session.clipId)
            ?: return EditCommand.SetAppearance(session.clipId, session.transform, session.gainDb)
        val frame = session.keyFrame
            ?: return if (ParamTracks.track(clip.params, ParamIds.GAIN_DB) == null) {
                EditCommand.SetAppearance(session.clipId, session.transform, session.gainDb)
            } else {
                // A keyframed volume: the transform is replaced, the volume change becomes a key at the playhead.
                val parts = ArrayList<EditCommand>()
                parts += EditCommand.SetTransform(clip.id, session.transform)
                if (session.gainDb != session.baseGain) parts += gainCommand(clip, session.gainDb)
                EditCommand.Batch(parts)
            }
        val parts = ArrayList<EditCommand>()
        if (session.transform != session.baseTransform) {
            val interpolation = Keyframes.at(clip.keyframes, frame)?.interpolation ?: Interpolation.LINEAR
            parts += EditCommand.SetKeyframe(clip.id, Keyframe(frame, session.transform, interpolation))
        }
        if (session.gainDb != session.baseGain) parts += gainCommand(clip, session.gainDb)
        return EditCommand.Batch(parts)
    }

    fun updateAppearance(transform: ClipTransform? = null, gainDb: Double? = null) {
        if (!beginAppearance()) return
        val session = appearance ?: return
        val newTransform = transform ?: session.transform
        val newGain = gainDb ?: session.gainDb
        val problem = newTransform.problem() ?: ClipGain.problem(newGain)
        if (problem != null) {
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_that_value_is_not_allowed, problem)))
            return
        }
        session.transform = newTransform
        session.gainDb = newGain
        showAppearance(session)
    }

    /** A step of a pan/pinch/rotate gesture on the preview; only meaningful while the clip is visible. */
    fun transformGesture(step: EditorIntent.TransformGesture) {
        if (appearance == null) {
            // Gestures edit what is on screen: the selected video clip under the playhead.
            if (!state.value.selectedClipVisible) return
            if (!beginAppearance()) return
        }
        val session = appearance ?: return
        session.transform = PreviewGeometry.applyGesture(session.transform, step.panX, step.panY, step.zoom, step.rotationDegrees)
        showAppearance(session)
    }

    /** Shows the session's values on the preview without touching the undo history. */
    private fun showAppearance(session: AppearanceSession) {
        val result = sessionCommand(session).apply(history.timeline)
        if (result is EditResult.Success) reduce { copy(dragPreview = result.value) }
    }

    fun endAppearance(commit: Boolean) {
        val session = appearance ?: return
        appearance = null
        val changed = session.transform != session.baseTransform || session.gainDb != session.baseGain
        if (commit && changed && execute(sessionCommand(session))) return
        reduce { copy(dragPreview = null) }
    }

    fun resetAppearance() = withSelection { clipId ->
        endAppearance(commit = false)
        val clip = history.timeline.trackOfClip(clipId)?.clip(clipId) ?: return@withSelection
        if (clip.transform.isIdentity && clip.gainDb == 0.0 && clip.keyframes.isEmpty()) return@withSelection
        // One undo step: the animation goes and the clip returns to its original placement.
        execute(EditCommand.Batch(listOf(EditCommand.ClearKeyframes(clipId), EditCommand.SetAppearance(clipId, ClipTransform.IDENTITY, 0.0))))
    }
}
