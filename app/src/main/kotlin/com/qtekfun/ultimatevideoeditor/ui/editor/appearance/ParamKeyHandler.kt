package com.qtekfun.ultimatevideoeditor.ui.editor.appearance

import com.qtekfun.ultimatevideoeditor.R
import com.qtekfun.ultimatevideoeditor.domain.Clip
import com.qtekfun.ultimatevideoeditor.domain.EditCommand
import com.qtekfun.ultimatevideoeditor.domain.EditResult
import com.qtekfun.ultimatevideoeditor.domain.ParamKey
import com.qtekfun.ultimatevideoeditor.domain.ParamTracks
import com.qtekfun.ultimatevideoeditor.domain.paramKeys
import com.qtekfun.ultimatevideoeditor.domain.paramSpec
import com.qtekfun.ultimatevideoeditor.domain.paramValueAt
import com.qtekfun.ultimatevideoeditor.ui.editor.*
import com.qtekfun.ultimatevideoeditor.ui.editor.playback.PlaybackController
import com.qtekfun.ultimatevideoeditor.ui.editor.timeline.TimelineDragHandler
import com.qtekfun.ultimatevideoeditor.ui.text.UiText

/**
 * Keyframes of single parameters (an effect value, audio pan...): the keyframe lane's add, move, copy and paste. A key drag is
 * provisional until it is released.
 */
internal class ParamKeyHandler(
    host: EditorHost,
    private val playback: PlaybackController,
    private val timelineDrag: TimelineDragHandler,
) : EditorHost by host {

    /** The key drag in progress in the keyframe lane, shown live and committed as one undo step on release. */
    private var paramEdit: EditCommand? = null

    fun selectedParamClip(): Clip? = state.value.selectedClipId?.let { history.timeline.trackOfClip(it)?.clip(it) }

    /** Adds a key holding the value shown at the playhead, or removes the one that is there. */
    fun toggleParamKey(paramId: String) = withSelection { clipId ->
        val clip = selectedParamClip() ?: return@withSelection
        val frame = state.value.selectedFrame
        if (frame == null) {
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_move_the_playhead_inside_the_4)))
            return@withSelection
        }
        val spec = clip.paramSpec(paramId)
        if (spec == null) {
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_that_value_cannot_be_animated)))
            return@withSelection
        }
        if (ParamTracks.at(clip.paramKeys(paramId), frame) != null) {
            execute(EditCommand.RemoveParamKey(clipId, paramId, frame))
        } else {
            val value = (clip.paramValueAt(paramId, frame) ?: spec.min).coerceIn(spec.min, spec.max)
            execute(EditCommand.SetParamKey(clipId, paramId, ParamKey(frame, value)))
        }
    }

    fun jumpToParamKey(paramId: String, forward: Boolean) = withSelection {
        val clip = selectedParamClip() ?: return@withSelection
        val relative = state.value.playhead - clip.timelineStart
        val keys = clip.paramKeys(paramId)
        val target = if (forward) ParamTracks.nextFrame(keys, relative) else ParamTracks.previousFrame(keys, relative)
        if (target == null) {
            emit(EditorEffect.ShowMessage(if (forward) UiText.res(R.string.ed_vm_no_later_keyframe_on_this_2) else UiText.res(R.string.ed_vm_no_earlier_keyframe_on_this_2)))
            return@withSelection
        }
        playback.seekTo(clip.timelineStart.value + target)
    }

    fun copyParamKeys(paramId: String) {
        val clip = selectedParamClip() ?: return
        val keys = clip.paramKeys(paramId)
        if (keys.isEmpty()) {
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_this_value_has_no_keyframes)))
            return
        }
        val first = keys.first().frame
        reduce { copy(paramClipboard = ParamClipboard(paramId, keys.map { it.copy(frame = it.frame - first) })) }
        emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_copied_keyframes, keys.size)))
    }

    /** Pastes the copied keys so the first lands on the playhead; values are clamped to this value's range. */
    fun pasteParamKeys(paramId: String) = withSelection { clipId ->
        val clip = selectedParamClip() ?: return@withSelection
        val clipboard = state.value.paramClipboard
        if (clipboard == null) {
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_copy_keyframes_first)))
            return@withSelection
        }
        val frame = state.value.selectedFrame
        if (frame == null) {
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_move_the_playhead_inside_the_5)))
            return@withSelection
        }
        val keys = ParamTracks.shiftedTo(clipboard.keys, frame, clip.durationFrames)
        if (keys.isEmpty() || clip.paramSpec(paramId) == null) {
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_the_copied_keyframes_do_not)))
            return@withSelection
        }
        execute(EditCommand.PasteParamKeys(clipId, paramId, keys))
    }

    /** Drags the key at [from] to [to] with [value]: shown live, kept provisional until [endParamKeyEdit]. */
    fun updateParamKey(paramId: String, from: Long, to: Long, value: Double) {
        if (timelineDrag.isDragging) return
        val clipId = state.value.selectedClipId ?: return
        val clip = selectedParamClip() ?: return
        val existing = ParamTracks.at(clip.paramKeys(paramId), from) ?: return
        val spec = clip.paramSpec(paramId) ?: return
        val moved = existing.copy(frame = to.coerceIn(0L, clip.durationFrames - 1), value = value.coerceIn(spec.min, spec.max))
        val parts = ArrayList<EditCommand>(2)
        if (moved.frame != from) parts += EditCommand.MoveParamKey(clipId, paramId, from, moved.frame)
        parts += EditCommand.SetParamKey(clipId, paramId, moved)
        val command = EditCommand.Batch(parts)
        when (val result = command.apply(history.timeline)) {
            is EditResult.Success -> {
                paramEdit = command
                reduce { copy(dragPreview = result.value, selectedParamKey = paramId to moved.frame) }
            }
            is EditResult.Failure -> Unit // a position that is not allowed keeps the last valid preview
        }
    }

    fun endParamKeyEdit(commit: Boolean) {
        val command = paramEdit ?: return
        paramEdit = null
        val result = command.apply(history.timeline)
        val changed = result is EditResult.Success && result.value != history.timeline
        if (commit && changed && execute(command)) return
        reduce { copy(dragPreview = null) }
    }
}
