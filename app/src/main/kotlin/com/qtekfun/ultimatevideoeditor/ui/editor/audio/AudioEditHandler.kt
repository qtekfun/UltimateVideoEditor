package com.qtekfun.ultimatevideoeditor.ui.editor.audio

import com.qtekfun.ultimatevideoeditor.R
import com.qtekfun.ultimatevideoeditor.domain.Clip
import com.qtekfun.ultimatevideoeditor.domain.ClipAudio
import com.qtekfun.ultimatevideoeditor.domain.ClipGain
import com.qtekfun.ultimatevideoeditor.domain.EditCommand
import com.qtekfun.ultimatevideoeditor.domain.EditResult
import com.qtekfun.ultimatevideoeditor.domain.ParamIds
import com.qtekfun.ultimatevideoeditor.domain.paramKeys
import com.qtekfun.ultimatevideoeditor.domain.paramValueAt
import com.qtekfun.ultimatevideoeditor.engine.timeline.HitKind
import com.qtekfun.ultimatevideoeditor.engine.timeline.TimelineHit
import com.qtekfun.ultimatevideoeditor.ui.editor.*
import com.qtekfun.ultimatevideoeditor.ui.editor.appearance.ParamKeyHandler
import com.qtekfun.ultimatevideoeditor.ui.editor.timeline.TimelineDragHandler
import com.qtekfun.ultimatevideoeditor.ui.text.UiText

/**
 * Audio controls: the pan, EQ, track volume and ducking sliders and the fade circles and volume curve drawn on the timeline canvas. A
 * slider or gesture in progress is shown live and committed as one undo step when it ends.
 */
internal class AudioEditHandler(
    host: EditorHost,
    private val timelineDrag: TimelineDragHandler,
    private val paramKeys: ParamKeyHandler,
) : EditorHost by host {

    /** The audio edit in progress (a slider drag), shown and heard live and committed as one undo step. */
    private var audioEdit: EditCommand? = null

    private var audioEditKey: String? = null

    /** Forgets the edit in progress without committing it (the timeline changed under it). */
    fun discard() {
        audioEdit = null
        audioEditKey = null
    }

    fun updateAudioEdit(key: String, command: EditCommand) {
        if (timelineDrag.isDragging) return
        // A slider of another clip, track or the ducking: the previous one is final first.
        if (audioEdit != null && audioEditKey != key) endAudioEdit(commit = true)
        when (val result = command.apply(history.timeline)) {
            is EditResult.Success -> {
                audioEdit = command
                audioEditKey = key
                reduce { copy(dragPreview = result.value, audioSessionActive = true) }
            }
            is EditResult.Failure -> emit(EditorEffect.ShowMessage(describe(result.error)))
        }
    }

    fun endAudioEdit(commit: Boolean) {
        val command = audioEdit ?: return
        audioEdit = null
        audioEditKey = null
        val result = command.apply(history.timeline)
        val changed = result is EditResult.Success && result.value != history.timeline
        if (commit && changed && execute(command)) return
        reduce { copy(dragPreview = null, audioSessionActive = false) }
    }

    /** The drag of a fade circle or curve point in progress: the clip and the gesture. */
    private var shapeDrag: Pair<String, AudioShapeGesture.Drag>? = null

    /** The steps of a shaping drag keep the provisional audio and key edits they make alive between moves. */
    fun isShapeDragStep(intent: EditorIntent) = intent is AudioShapeIntent.Move || intent is AudioShapeIntent.End

    private fun shapeClip(hit: TimelineHit): Pair<String, Clip>? {
        val clipId = clipKeys.idFor(hit.clipKey) ?: return null
        if (clipId != state.value.selectedClipId) return null
        val track = history.timeline.trackOfClip(clipId) ?: return null
        val clip = track.clip(clipId) ?: return null
        return if (AudioShapeGesture.isEditable(clip, track.type, isPrimary = true)) clipId to clip else null
    }

    fun audioShapeIntent(intent: AudioShapeIntent) {
        when (intent) {
            is AudioShapeIntent.Start -> {
                val (clipId, clip) = shapeClip(intent.hit) ?: return
                shapeDrag = AudioShapeGesture.Drag.begin(intent.hit, clip)?.let { clipId to it }
            }
            is AudioShapeIntent.Move -> {
                val (clipId, drag) = shapeDrag ?: return
                val clip = history.timeline.trackOfClip(clipId)?.clip(clipId) ?: return
                when (val action = drag.move(intent.hit)) {
                    is AudioShapeGesture.Action.SetFades -> {
                        val audio = clip.audio.copy(fadeInFrames = action.fadeIn, fadeOutFrames = action.fadeOut)
                        updateAudioEdit("clip:$clipId", EditCommand.SetClipAudioAt(clipId, audio, null))
                    }
                    is AudioShapeGesture.Action.MovePoint -> paramKeys.updateParamKey(ParamIds.GAIN_DB, action.fromFrame, action.toFrame, action.db)
                    null -> Unit
                }
            }
            is AudioShapeIntent.End -> {
                shapeDrag = null
                endAudioEdit(intent.commit)
                paramKeys.endParamKeyEdit(intent.commit)
            }
            is AudioShapeIntent.DoubleTap -> {
                val (clipId, clip) = shapeClip(intent.hit) ?: return
                when (intent.hit.kind) {
                    HitKind.VOLUME_POINT -> clip.paramKeys(ParamIds.GAIN_DB).getOrNull(intent.hit.index)?.let {
                        execute(EditCommand.RemoveParamKey(clipId, ParamIds.GAIN_DB, it.frame))
                    }
                    HitKind.CLIP, HitKind.CLIP_LEFT_EDGE, HitKind.CLIP_RIGHT_EDGE -> intent.hit.dbTenths?.let { tenths ->
                        val frame = intent.hit.frame - clip.timelineStart.value
                        execute(EditCommand.PasteParamKeys(clipId, ParamIds.GAIN_DB, AudioShapeGesture.keysForNewPoint(clip, frame, tenths / 10.0)))
                    }
                    else -> Unit
                }
            }
            AudioShapeIntent.AddPointAtPlayhead -> withSelection { clipId ->
                val clip = paramKeys.selectedParamClip() ?: return@withSelection
                val frame = state.value.selectedFrame
                if (frame == null) {
                    emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_move_the_playhead_inside_the)))
                    return@withSelection
                }
                val here = (clip.paramValueAt(ParamIds.GAIN_DB, frame) ?: clip.gainDb).coerceIn(ClipGain.MIN_DB, ClipGain.MAX_DB)
                execute(EditCommand.PasteParamKeys(clipId, ParamIds.GAIN_DB, AudioShapeGesture.keysForNewPoint(clip, frame, here, snap = false)))
            }
        }
    }

    fun resetClipAudio() = withSelection { clipId ->
        val clip = history.timeline.trackOfClip(clipId)?.clip(clipId) ?: return@withSelection
        if (clip.audio.isNeutral) {
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_this_clip_has_no_audio)))
            return@withSelection
        }
        execute(EditCommand.SetClipAudio(clipId, ClipAudio.NONE))
    }
}
