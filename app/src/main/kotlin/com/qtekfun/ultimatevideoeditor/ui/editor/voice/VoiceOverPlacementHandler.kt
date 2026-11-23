package com.qtekfun.ultimatevideoeditor.ui.editor.voice

import com.qtekfun.ultimatevideoeditor.R
import com.qtekfun.ultimatevideoeditor.data.MediaImportException
import com.qtekfun.ultimatevideoeditor.domain.Clip
import com.qtekfun.ultimatevideoeditor.domain.EditCommand
import com.qtekfun.ultimatevideoeditor.domain.FrameIndex
import com.qtekfun.ultimatevideoeditor.domain.voice.VoicePlacement
import com.qtekfun.ultimatevideoeditor.ui.editor.*
import com.qtekfun.ultimatevideoeditor.ui.editor.tray.MediaImportHandler
import com.qtekfun.ultimatevideoeditor.ui.text.UiText
import com.qtekfun.ultimatevideoeditor.ui.text.reasonOf
import kotlinx.coroutines.launch

/**
 * Placing a finished voice-over recording on an audio lane at the frame it was started on, trimmed to what was recorded.
 */
internal class VoiceOverPlacementHandler(
    host: EditorHost,
    private val mediaImport: MediaImportHandler,
) : EditorHost by host {

    /** Puts a voice-over take on an audio lane where it overwrites nothing; one undo step (the lane, if new, and the clip together). */
    fun placeVoiceOver(uri: String, startFrame: Long, trimMs: Long) {
        scope.launch {
            val asset = try {
                mediaImport.assetFor(uri)
            } catch (e: MediaImportException) {
                emit(EditorEffect.ShowMessage(reasonOf(e, UiText.res(R.string.ed_vo_could_not_add))))
                return@launch
            }
            val length = assetLengthFrames(asset.id) ?: return@launch
            val fps = state.value.fps
            val trim = VoicePlacement.trimFrames(trimMs, 0, fps.num.toDouble() / fps.den, length)
            val start = FrameIndex(startFrame.coerceAtLeast(0))
            val plan = VoicePlacement.plan(history.timeline, start, length - trim, uniqueTrackId(history.timeline.tracks, "track-a"))
            val clip = Clip("clip-${idGenerator()}", asset.id, start, FrameIndex(trim), FrameIndex(length))
            val place = EditCommand.Overwrite(plan.trackId, clip)
            val command = plan.newTrack?.let { EditCommand.Batch(listOf(EditCommand.AddTrack(it, history.timeline.tracks.size), place)) } ?: place
            if (!execute(command)) return@launch
            reduce { copy(selectedClipId = clip.id, selectedTrackId = plan.trackId) }
            emit(EditorEffect.VoiceOverPlaced)
        }
    }
}
