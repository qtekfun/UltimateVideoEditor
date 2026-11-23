package com.qtekfun.ultimatevideoeditor.ui.editor.audio

import com.qtekfun.ultimatevideoeditor.R
import com.qtekfun.ultimatevideoeditor.domain.ClipLinks
import com.qtekfun.ultimatevideoeditor.domain.DetachAudio
import com.qtekfun.ultimatevideoeditor.domain.RelinkClips
import com.qtekfun.ultimatevideoeditor.domain.RestoreEmbeddedAudio
import com.qtekfun.ultimatevideoeditor.domain.TrackType
import com.qtekfun.ultimatevideoeditor.ui.editor.*
import com.qtekfun.ultimatevideoeditor.ui.text.UiText

/**
 * The audio of a video clip: detaching it onto an audio lane, relinking it and restoring it into the clip (SPECS.md 5.38).
 */
internal class AudioLinkHandler(
    host: EditorHost,
) : EditorHost by host {

    private fun linkInfo(clipId: String): ClipLinks.LinkInfo? {
        val clip = history.timeline.trackOfClip(clipId)?.clip(clipId) ?: return null
        val hasAudio = state.value.assets.firstOrNull { it.id == clip.assetId }?.hasAudio == true
        return ClipLinks.infoFor(history.timeline, clipId, hasAudio)
    }

    fun detachAudio() = withSelection { clipId ->
        val timeline = history.timeline
        val clip = timeline.trackOfClip(clipId)?.clip(clipId)
        if (clip == null || linkInfo(clipId)?.canDetach != true) {
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_select_a_video_clip_that)))
            return@withSelection
        }
        val laneId = ClipLinks.audioTrackFor(timeline, clip) ?: uniqueTrackId(timeline.tracks, "track-a")
        execute(DetachAudio(clipId, "audio-${idGenerator()}", laneId, linked = true))
    }

    fun relinkAudio(realign: Boolean) = withSelection { clipId ->
        val info = linkInfo(clipId)
        val candidate = info?.relinkCandidateId
        if (info == null || candidate == null) {
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_no_unlinked_clip_of_the)))
            return@withSelection
        }
        val (video, audio) = if (info.isVideo) clipId to candidate else candidate to clipId
        execute(RelinkClips(video, audio, realign))
    }

    fun restoreEmbeddedAudio() = withSelection { clipId ->
        val timeline = history.timeline
        val clip = timeline.trackOfClip(clipId)?.clip(clipId) ?: return@withSelection
        val video = if (timeline.trackOfClip(clipId)?.type == TrackType.VIDEO) clip else ClipLinks.partnerOf(timeline, clip)?.second
        if (video == null || !video.audioDetached) {
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_select_a_video_clip_whose)))
            return@withSelection
        }
        execute(RestoreEmbeddedAudio(video.id))
    }
}
