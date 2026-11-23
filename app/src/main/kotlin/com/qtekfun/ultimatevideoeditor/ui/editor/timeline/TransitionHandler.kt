package com.qtekfun.ultimatevideoeditor.ui.editor.timeline

import com.qtekfun.ultimatevideoeditor.R
import com.qtekfun.ultimatevideoeditor.domain.EditCommand
import com.qtekfun.ultimatevideoeditor.domain.TimelineOps
import com.qtekfun.ultimatevideoeditor.domain.Transition
import com.qtekfun.ultimatevideoeditor.ui.editor.*
import com.qtekfun.ultimatevideoeditor.ui.text.UiText

/**
 * Transitions between two clips: adding one at the cut nearest the playhead, its length and style, and removing it.
 */
internal class TransitionHandler(
    host: EditorHost,
) : EditorHost by host {

    fun addTransition() {
        val timeline = history.timeline
        val cut = state.value.transitionCut
        if (cut == null) {
            val message = if (state.value.selectedClip != null && state.value.visibleTimeline.transitions.isNotEmpty() && state.value.clipAfterSelected != null) {
                UiText.res(R.string.ed_vm_already_transition)
            } else {
                UiText.res(R.string.ed_vm_place_another_clip)
            }
            emit(EditorEffect.ShowMessage(message))
            return
        }
        val clip = cut.from
        val next = cut.to
        val sourceLength = assetLengthFrames(clip.assetId)
        val room = TimelineOps.maxTransitionFrames(timeline, clip.id, next.id, sourceLength)
        if (room < Transition.MIN_DURATION_FRAMES) {
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_there_is_not_enough_extra)))
            return
        }
        val wanted = state.value.fps.microsToFrames(TRANSITION_DEFAULT_MICROS).coerceAtLeast(Transition.MIN_DURATION_FRAMES)
        execute(EditCommand.AddTransition(Transition("transition-${idGenerator()}", clip.id, next.id, minOf(wanted, room)), sourceLength))
    }

    fun setTransitionDuration(frames: Long) = withSelection { clipId ->
        val transition = history.timeline.transitions.firstOrNull { it.fromClipId == clipId }
        if (transition == null) {
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_this_clip_has_no_transition)))
            return@withSelection
        }
        if (frames == transition.durationFrames) return@withSelection
        val from = history.timeline.trackOfClip(clipId)?.clip(clipId)
        execute(EditCommand.SetTransitionDuration(transition.id, frames, assetLengthFrames(from?.assetId)))
    }

    fun setTransitionStyle(
        type: com.qtekfun.ultimatevideoeditor.domain.TransitionType,
        direction: com.qtekfun.ultimatevideoeditor.domain.TransitionDirection,
    ) = withSelection { clipId ->
        val transition = history.timeline.transitions.firstOrNull { it.fromClipId == clipId }
        if (transition == null) {
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_add_a_transition_to_the)))
            return@withSelection
        }
        execute(EditCommand.SetTransitionStyle(transition.id, type, direction))
    }

    fun removeTransition() = withSelection { clipId ->
        val transition = history.timeline.transitions.firstOrNull { it.fromClipId == clipId } ?: return@withSelection
        execute(EditCommand.RemoveTransition(transition.id))
    }
}
