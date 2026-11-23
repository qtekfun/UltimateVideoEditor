package com.qtekfun.ultimatevideoeditor.ui.editor.timeline

import com.qtekfun.ultimatevideoeditor.R
import com.qtekfun.ultimatevideoeditor.domain.EditCommand
import com.qtekfun.ultimatevideoeditor.domain.SpeedRamps
import com.qtekfun.ultimatevideoeditor.domain.TrackType
import com.qtekfun.ultimatevideoeditor.ui.editor.*
import com.qtekfun.ultimatevideoeditor.ui.text.UiText

/**
 * Changing a clip's speed: constant speed, reverse, smooth slow motion, speed ramps and freeze frames (SPECS.md 5.14).
 */
internal class RetimeHandler(
    host: EditorHost,
) : EditorHost by host {

    /** Slowing down pushes the clips after this one later and speeding up pulls them earlier, so a slow-motion clip never fails on its neighbour. */
    fun setSpeed(num: Long, den: Long) = withSelection { clipId ->
        execute(EditCommand.SetSpeed(clipId, num, den, ripple = true))
    }

    fun toggleSmoothSlowMo() = withSelection { clipId ->
        val clip = history.timeline.trackOfClip(clipId)?.clip(clipId) ?: return@withSelection
        execute(EditCommand.SetSmoothSlowMo(clipId, !clip.smoothSlowMo))
    }

    fun toggleReverse() = withSelection { clipId ->
        val clip = history.timeline.trackOfClip(clipId)?.clip(clipId) ?: return@withSelection
        execute(EditCommand.SetReverse(clipId, !clip.reverse))
    }

    fun setSpeedRamp(shape: SpeedRampShape) = withSelection { clipId ->
        val clip = history.timeline.trackOfClip(clipId)?.clip(clipId) ?: return@withSelection
        val ramp = when (shape) {
            SpeedRampShape.NONE -> emptyList()
            SpeedRampShape.EASE_IN -> SpeedRamps.easeIn(clip.durationFrames)
            SpeedRampShape.EASE_OUT -> SpeedRamps.easeOut(clip.durationFrames)
            SpeedRampShape.BELL -> SpeedRamps.bell(clip.durationFrames)
            SpeedRampShape.EASE_IN_SMOOTH -> SpeedRamps.easeInSmooth(clip.durationFrames)
            SpeedRampShape.EASE_OUT_SMOOTH -> SpeedRamps.easeOutSmooth(clip.durationFrames)
            SpeedRampShape.MONTAGE -> SpeedRamps.montage(clip.durationFrames)
            SpeedRampShape.HERO -> SpeedRamps.hero(clip.durationFrames)
            SpeedRampShape.BULLET -> SpeedRamps.bullet(clip.durationFrames)
        }
        if (shape != SpeedRampShape.NONE && ramp.isEmpty()) {
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_this_clip_is_too_short)))
            return@withSelection
        }
        execute(EditCommand.SetSpeedRamp(clipId, ramp))
    }

    fun freezeFrame() = withSelection { clipId ->
        val track = history.timeline.trackOfClip(clipId) ?: return@withSelection
        val clip = track.clip(clipId) ?: return@withSelection
        val playhead = state.value.playhead
        if (track.type != TrackType.VIDEO || playhead < clip.timelineStart || playhead >= clip.timelineEnd) {
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_move_the_playhead_inside_the_6)))
            return@withSelection
        }
        val frames = state.value.fps.microsToFrames(FREEZE_DEFAULT_MICROS).coerceAtLeast(1)
        val still = "freeze-${idGenerator()}"
        if (execute(EditCommand.FreezeFrame(track.id, playhead, frames, still, "$clipId~${idGenerator()}"))) {
            reduce { copy(selectedClipId = still) }
        }
    }
}
