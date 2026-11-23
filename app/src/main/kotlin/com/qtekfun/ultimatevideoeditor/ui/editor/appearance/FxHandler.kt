package com.qtekfun.ultimatevideoeditor.ui.editor.appearance

import com.qtekfun.ultimatevideoeditor.R
import com.qtekfun.ultimatevideoeditor.domain.ClipFx
import com.qtekfun.ultimatevideoeditor.domain.ClipMask
import com.qtekfun.ultimatevideoeditor.domain.EditCommand
import com.qtekfun.ultimatevideoeditor.domain.EditResult
import com.qtekfun.ultimatevideoeditor.domain.EffectType
import com.qtekfun.ultimatevideoeditor.domain.GradeCurves
import com.qtekfun.ultimatevideoeditor.domain.TrackType
import com.qtekfun.ultimatevideoeditor.domain.fxAt
import com.qtekfun.ultimatevideoeditor.ui.editor.*
import com.qtekfun.ultimatevideoeditor.ui.editor.timeline.TimelineDragHandler
import com.qtekfun.ultimatevideoeditor.ui.text.UiText

/**
 * Effect, colour grade and mask sliders: an edit in progress is shown live and committed as one undo step when it ends.
 */
internal class FxHandler(
    host: EditorHost,
    private val timelineDrag: TimelineDragHandler,
) : EditorHost by host {

    /** An effect or mask slider drag in progress: shown live, committed as one undo step. */
    private class FxSession(val clipId: String, val base: ClipFx, var fx: ClipFx, val frame: Long? = null)

    private var fxEdit: FxSession? = null

    private fun beginFx(): FxSession? {
        fxEdit?.let { return it }
        if (timelineDrag.isDragging) return null
        val clipId = state.value.selectedClipId
        val clip = clipId?.let { history.timeline.trackOfClip(it)?.clip(it) }
        if (clip == null || history.timeline.trackOfClip(clip.id)?.type == TrackType.AUDIO) {
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_select_a_video_clip_or)))
            return null
        }
        // The controls show the effects as they are at the playhead; keyframed values changed here become keys there.
        val frame = state.value.selectedFrame
        val shown = if (frame != null) clip.fxAt(frame) else clip.fx
        return FxSession(clip.id, shown, shown, frame).also { fxEdit = it }
    }

    fun updateEffect(effectId: String, values: List<Double>) {
        val session = beginFx() ?: return
        val effect = session.fx.effect(effectId) ?: return
        val changed = effect.copy(values = values)
        changed.problem()?.let {
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_that_value_is_not_allowed, it)))
            return
        }
        session.fx = session.fx.copy(effects = session.fx.effects.map { if (it.id == effectId) changed else it })
        showFx(session)
    }

    fun updateGrade(effectId: String, values: List<Double>, curves: GradeCurves?) {
        val session = beginFx() ?: return
        val effect = session.fx.effect(effectId) ?: return
        val changed = effect.copy(values = values, curves = curves?.takeUnless { it.isIdentity })
        if (effect.type != EffectType.COLOR_GRADE) return
        changed.problem()?.let {
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_that_value_is_not_allowed, it)))
            return
        }
        session.fx = session.fx.copy(effects = session.fx.effects.map { if (it.id == effectId) changed else it })
        showFx(session)
    }

    /** A look or a pasted grade: replaces the clip's first colour grade, or adds one, as one undo step. */
    fun applyGrade(values: List<Double>, curves: GradeCurves?) = withSelection { clipId ->
        val clip = history.timeline.trackOfClip(clipId)?.clip(clipId) ?: return@withSelection
        val existing = clip.fx.effects.firstOrNull { it.type == EffectType.COLOR_GRADE }
        if (existing == null && clip.fx.effects.size >= ClipFx.MAX_EFFECTS) {
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_a_clip_can_have_at, ClipFx.MAX_EFFECTS)))
            return@withSelection
        }
        execute(EditCommand.SetGrade(clipId, existing?.id ?: idGenerator(), values, curves))
    }

    fun updateMask(mask: ClipMask?) {
        val session = beginFx() ?: return
        mask?.problem()?.let {
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_that_value_is_not_allowed, it)))
            return
        }
        session.fx = session.fx.copy(mask = mask)
        showFx(session)
    }

    private fun showFx(session: FxSession) {
        val result = EditCommand.SetFxAt(session.clipId, session.fx, session.frame).apply(history.timeline)
        if (result is EditResult.Success) reduce { copy(dragPreview = result.value) }
    }

    fun endFxEdit(commit: Boolean) {
        val session = fxEdit ?: return
        fxEdit = null
        if (commit && session.fx != session.base && execute(EditCommand.SetFxAt(session.clipId, session.fx, session.frame))) return
        reduce { copy(dragPreview = null) }
    }
}
