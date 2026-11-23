package com.qtekfun.ultimatevideoeditor.ui.editor.appearance

import com.qtekfun.ultimatevideoeditor.R
import com.qtekfun.ultimatevideoeditor.domain.EditCommand
import com.qtekfun.ultimatevideoeditor.domain.Interpolation
import com.qtekfun.ultimatevideoeditor.domain.Keyframe
import com.qtekfun.ultimatevideoeditor.domain.Keyframes
import com.qtekfun.ultimatevideoeditor.domain.TrackType
import com.qtekfun.ultimatevideoeditor.ui.editor.*
import com.qtekfun.ultimatevideoeditor.ui.editor.appearance.AppearanceHandler
import com.qtekfun.ultimatevideoeditor.ui.editor.playback.PlaybackController
import com.qtekfun.ultimatevideoeditor.ui.text.UiText

/**
 * Keyframes of a clip's pose (position, scale, rotation, opacity): adding or removing one at the playhead, jumping between them and
 * choosing how the motion eases.
 */
internal class KeyframeHandler(
    host: EditorHost,
    private val playback: PlaybackController,
    private val appearance: AppearanceHandler,
) : EditorHost by host {

    /** Adds a keyframe at the playhead holding the pose shown there, or removes the one that is there. */
    fun toggleKeyframe() = withSelection { clipId ->
        appearance.endAppearance(commit = true)
        val clip = history.timeline.trackOfClip(clipId)?.clip(clipId) ?: return@withSelection
        if (history.timeline.trackOfClip(clipId)?.type == TrackType.AUDIO) {
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_only_video_clips_and_titles)))
            return@withSelection
        }
        val frame = state.value.selectedClipFrame
        if (frame == null) {
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_move_the_playhead_inside_the_4)))
            return@withSelection
        }
        if (Keyframes.at(clip.keyframes, frame) != null) {
            execute(EditCommand.RemoveKeyframe(clipId, frame))
        } else {
            execute(EditCommand.SetKeyframe(clipId, Keyframe(frame, clip.transformAt(frame))))
        }
    }

    fun jumpToKeyframe(forward: Boolean) = withSelection { clipId ->
        val clip = history.timeline.trackOfClip(clipId)?.clip(clipId) ?: return@withSelection
        val relative = state.value.playhead - clip.timelineStart
        val target = if (forward) Keyframes.nextFrame(clip.keyframes, relative) else Keyframes.previousFrame(clip.keyframes, relative)
        if (target == null) {
            emit(EditorEffect.ShowMessage(if (forward) UiText.res(R.string.ed_vm_no_later_keyframe_on_this) else UiText.res(R.string.ed_vm_no_earlier_keyframe_on_this)))
            return@withSelection
        }
        playback.seekTo(clip.timelineStart.value + target)
    }

    fun setKeyframeInterpolation(interpolation: Interpolation) = withSelection { clipId ->
        appearance.endAppearance(commit = true)
        val frame = state.value.keyframeAtPlayhead?.frame
        if (frame == null) {
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_put_the_playhead_on_a)))
            return@withSelection
        }
        execute(EditCommand.SetKeyframeInterpolation(clipId, frame, interpolation))
    }

    fun clearKeyframes() = withSelection { clipId ->
        appearance.endAppearance(commit = false)
        val clip = history.timeline.trackOfClip(clipId)?.clip(clipId) ?: return@withSelection
        if (clip.keyframes.isEmpty()) return@withSelection
        // The clip keeps the pose it has at the playhead, so the picture does not jump.
        val frame = state.value.selectedClipFrame
        val pose = if (frame != null) clip.transformAt(frame) else clip.keyframes.first().transform
        execute(EditCommand.Batch(listOf(EditCommand.ClearKeyframes(clipId), EditCommand.SetTransform(clipId, pose))))
    }
}
