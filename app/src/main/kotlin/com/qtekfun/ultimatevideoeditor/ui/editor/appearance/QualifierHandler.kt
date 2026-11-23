package com.qtekfun.ultimatevideoeditor.ui.editor.appearance

import com.qtekfun.ultimatevideoeditor.R
import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto
import com.qtekfun.ultimatevideoeditor.domain.Clip
import com.qtekfun.ultimatevideoeditor.domain.EditCommand
import com.qtekfun.ultimatevideoeditor.domain.EffectType
import com.qtekfun.ultimatevideoeditor.domain.FrameIndex
import com.qtekfun.ultimatevideoeditor.domain.Keyframes
import com.qtekfun.ultimatevideoeditor.domain.Qualifier
import com.qtekfun.ultimatevideoeditor.domain.StillKind
import com.qtekfun.ultimatevideoeditor.domain.TrackMath
import com.qtekfun.ultimatevideoeditor.domain.sourceFrameAtProjectFrame
import com.qtekfun.ultimatevideoeditor.engine.sample.FrameSampler
import com.qtekfun.ultimatevideoeditor.ui.editor.*
import com.qtekfun.ultimatevideoeditor.ui.editor.playback.PlaybackController
import com.qtekfun.ultimatevideoeditor.ui.text.UiText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The HSL qualifier's eyedropper: arming a pick, reading the colour under a tap from the clip's picture and keying the qualifier on it.
 */
internal class QualifierHandler(
    host: EditorHost,
    private val frameSampler: FrameSampler,
    private val sampleDispatcher: CoroutineDispatcher,
    private val playback: PlaybackController,
) : EditorHost by host {

    fun qualifierIntent(intent: QualifierIntent) {
        when (intent) {
            is QualifierIntent.Arm -> armQualifierPick(intent.effectId)
            QualifierIntent.Cancel -> reduce { copy(qualifierPick = QualifierPickState()) }
            is QualifierIntent.Pick -> pickQualifierColor(intent.x, intent.y)
        }
    }

    /** The selected clip when its picture can be read for a colour: a video file or a photo, not a title or a sticker. */
    private fun samplableSelection(): Pair<Clip, MediaAssetDto>? {
        val found = clipWithAsset(state.value.selectedClipId) ?: return null
        val (clip, asset) = found
        val isPhoto = clip.still == StillKind.PHOTO
        return found.takeIf { clip.title == null && ((clip.hasMedia && asset.hasVideo) || isPhoto) }
    }

    private fun armQualifierPick(effectId: String) {
        val found = samplableSelection()
        if (found == null) {
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_select_a_video_or_photo)))
            return
        }
        val (clip, _) = found
        if (clip.fx.effect(effectId)?.type != EffectType.QUALIFIER) return
        val playhead = state.value.playhead
        if (playhead < clip.timelineStart || playhead >= clip.timelineEnd) {
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_move_the_playhead_onto_the)))
            return
        }
        playback.pausePlayback()
        reduce { copy(qualifierPick = QualifierPickState(effectId)) }
    }

    private fun pickQualifierColor(x: Double, y: Double) {
        val pick = state.value.qualifierPick
        val effectId = pick.effectId ?: return
        if (pick.busy) return
        val found = samplableSelection()
        if (found == null) {
            reduce { copy(qualifierPick = QualifierPickState()) }
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_select_a_video_or_photo)))
            return
        }
        val (clip, asset) = found
        val playhead = state.value.playhead
        if (playhead < clip.timelineStart || playhead >= clip.timelineEnd) {
            reduce { copy(qualifierPick = QualifierPickState()) }
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_move_the_playhead_onto_the)))
            return
        }
        reduce { copy(qualifierPick = qualifierPick.copy(busy = true)) }
        scope.launch {
            val outcome = withContext(sampleDispatcher) { sampleAndKey(clip, asset, effectId, x, y, playhead) }
            // The eyedropper ends after a pick, with a message when it found nothing, except for a tap beside the
            // picture, which keeps waiting for a better one.
            reduce { copy(qualifierPick = if (outcome.keepArmed) QualifierPickState(effectId) else QualifierPickState()) }
            outcome.message?.let { emit(EditorEffect.ShowMessage(it)) }
        }
    }

    /** What an eyedropper tap came to: [message] is what to tell the user (null when the key was set). */
    private class PickOutcome(val message: UiText?, val keepArmed: Boolean = false)

    /** Reads the colour under the tap and keys the effect on it. */
    private suspend fun sampleAndKey(clip: Clip, asset: MediaAssetDto, effectId: String, x: Double, y: Double, playhead: FrameIndex): PickOutcome {
        val s = state.value
        val aspect = frameSampler.aspect(asset) ?: return PickOutcome(UiText.res(R.string.pick_picture_unreadable))
        val pose = Keyframes.evaluate(clip.keyframes, playhead.value - clip.timelineStart.value, clip.transform)
        val (u, v) = TrackMath.fromCanvas(x, y, aspect, s.canvasWidth, s.canvasHeight, pose)
        if (u !in 0.0..1.0 || v !in 0.0..1.0) return PickOutcome(UiText.res(R.string.pick_tap_picture), keepArmed = true)
        val micros = if (asset.isImage) 0L else s.fps.framesToMicros(clip.sourceFrameAtProjectFrame(playhead))
        val colour = frameSampler.colorAt(asset, micros, u, v) ?: return PickOutcome(UiText.res(R.string.pick_colour_unreadable))
        // The clip may have been edited while the frame was read.
        val current = history.timeline.trackOfClip(clip.id)?.clip(clip.id)?.fx?.effect(effectId)
        if (current == null || current.type != EffectType.QUALIFIER) return PickOutcome(UiText.res(R.string.pick_effect_gone))
        val keyed = Qualifier.keyedOn(current.values, colour.r, colour.g, colour.b)
        return PickOutcome(if (execute(EditCommand.SetEffectValues(clip.id, effectId, keyed))) null else UiText.res(R.string.pick_key_failed))
    }
}
