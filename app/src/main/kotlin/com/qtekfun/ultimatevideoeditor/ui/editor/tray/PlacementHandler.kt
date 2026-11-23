package com.qtekfun.ultimatevideoeditor.ui.editor.tray

import com.qtekfun.ultimatevideoeditor.R
import com.qtekfun.ultimatevideoeditor.data.MissingMedia
import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto
import com.qtekfun.ultimatevideoeditor.domain.Clip
import com.qtekfun.ultimatevideoeditor.domain.ClipDeletion
import com.qtekfun.ultimatevideoeditor.domain.EditCommand
import com.qtekfun.ultimatevideoeditor.domain.EditResult
import com.qtekfun.ultimatevideoeditor.domain.FrameIndex
import com.qtekfun.ultimatevideoeditor.domain.PlaceWithDetachedAudio
import com.qtekfun.ultimatevideoeditor.domain.StillKind
import com.qtekfun.ultimatevideoeditor.domain.Timeline
import com.qtekfun.ultimatevideoeditor.domain.Track
import com.qtekfun.ultimatevideoeditor.domain.TrackType
import com.qtekfun.ultimatevideoeditor.ui.editor.*
import com.qtekfun.ultimatevideoeditor.ui.editor.layout.VideoAudioPlacementStore
import com.qtekfun.ultimatevideoeditor.ui.editor.playback.PlaybackController
import com.qtekfun.ultimatevideoeditor.ui.text.UiText

/**
 * Putting library files on the timeline: where a new clip goes (the active lane, a new lane, the end of the lane), the command that places it
 * and the selection afterwards. The drop and import handlers build on it.
 */
internal class PlacementHandler(
    host: EditorHost,
    private val videoAudioPlacement: VideoAudioPlacementStore,
    private val playback: PlaybackController,
) : EditorHost by host {

    /** The clip that placing [asset] creates and the kind of lane it needs, or null if it has no usable length. */
    fun newClipFor(asset: MediaAssetDto): Pair<Clip, TrackType>? {
        val type = if (asset.hasVideo || asset.isImage) TrackType.VIDEO else TrackType.AUDIO
        val length = (if (asset.isImage) stillLengthFrames() else assetLengthFrames(asset.id)) ?: return null
        val clip = Clip(
            "clip-${idGenerator()}", asset.id, FrameIndex.ZERO, FrameIndex.ZERO, FrameIndex(length),
            still = StillKind.PHOTO.takeIf { asset.isImage },
        )
        return clip to type
    }

    /**
     * [command] (which places [clip] on the timeline) as it should run: with the preference on, a video clip that has sound comes in
     * detached and linked on an audio lane, in the same undo step. Anything else (audio, photos, silent video) is placed as it is.
     */
    fun placing(command: EditCommand, clip: Clip, type: TrackType, timeline: Timeline = history.timeline): EditCommand {
        if (type != TrackType.VIDEO || clip.still != null || !videoAudioPlacement.isOn()) return command
        val asset = state.value.assets.firstOrNull { it.id == clip.assetId } ?: return command
        if (!asset.hasVideo || !asset.hasAudio) return command
        return PlaceWithDetachedAudio(command, clip.id, "audio-${idGenerator()}", uniqueTrackId(timeline.tracks, "track-a"))
    }

    fun selectPlaced(clipId: String) {
        val track = history.timeline.trackOfClip(clipId) ?: return
        reduce { copy(selectedClipId = clipId, selectedTrackId = track.id) }
    }

    fun addAssetById(assetId: String) {
        val asset = state.value.assets.firstOrNull { it.id == assetId }
        if (asset == null) {
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_that_media_is_no_longer)))
            return
        }
        if (asset.id in state.value.missingMedia) {
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_is_missing_relink_it_first, MissingMedia.nameOf(asset))))
            return
        }
        // The playhead waits where the clip ends, so the next one added continues from there.
        place(asset, state.value.playhead)?.let { playback.setPlayhead(it.value) }
    }

    /**
     * Adds several assets in the order given, each starting where the previous one ended, as one undo step; the playhead ends
     * after the last one. Missing or unknown assets are skipped with a message.
     */
    fun addAssetsById(assetIds: List<String>) {
        val assets = assetIds.mapNotNull { id -> state.value.assets.firstOrNull { it.id == id } }
        val usable = assets.filter { asset ->
            val missing = asset.id in state.value.missingMedia
            if (missing) emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_is_missing_relink_it_first, MissingMedia.nameOf(asset))))
            !missing
        }
        if (usable.isEmpty()) return
        var working = history.timeline
        var cursor = state.value.playhead
        val commands = mutableListOf<EditCommand>()
        var last: Pair<Clip, Track>? = null
        for (asset in usable) {
            val plan = planPlacement(asset, cursor, working) ?: continue
            val command = placing(plan.command, plan.clip, plan.type, working)
            val result = command.apply(working) as? EditResult.Success ?: continue
            working = result.value
            commands += command
            val landed = working.trackOfClip(plan.clip.id) ?: continue
            last = checkNotNull(landed.clip(plan.clip.id)) to landed
            cursor = last.first.timelineEnd
        }
        val (lastClip, lastTrack) = last ?: return
        if (!execute(if (commands.size == 1) commands.single() else EditCommand.Batch(commands))) return
        reduce { copy(selectedClipId = lastClip.id, selectedTrackId = lastTrack.id) }
        playback.setPlayhead(cursor.value)
    }

    /** What placing an asset would do: the command, the clip it creates and the kind of lane it needs. */
    private class Placement(val command: EditCommand, val clip: Clip, val type: TrackType, val track: Track)

    /** Plans [asset] at [start] on [timeline] (the selected lane if it fits, else the first of the right kind); says why when it cannot. */
    private fun planPlacement(asset: MediaAssetDto, start: FrameIndex, timeline: Timeline): Placement? {
        val type = if (asset.hasVideo || asset.isImage) TrackType.VIDEO else TrackType.AUDIO
        // The selected track if it fits the media, else the first track of the right type.
        val selected = timeline.tracks.firstOrNull { it.id == state.value.selectedTrackId }
        val track = selected?.takeIf { it.type == type } ?: timeline.tracks.firstOrNull { it.type == type }
        if (track == null) {
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_there_is_no_track_to, UiText.res(type.nameRes()))))
            return null
        }
        val length = (if (asset.isImage) stillLengthFrames() else assetLengthFrames(asset.id)) ?: return null
        val clip = Clip(
            "clip-${idGenerator()}", asset.id, start, FrameIndex.ZERO, FrameIndex(length),
            still = StillKind.PHOTO.takeIf { asset.isImage },
        )
        // On the base track new media is inserted (everything after ripples); elsewhere it overwrites.
        val onBase = track.id == ClipDeletion.baseTrack(timeline)?.id
        val command = if (onBase) EditCommand.InsertBase(clip, start) else EditCommand.Overwrite(track.id, clip)
        return Placement(command, clip, type, track)
    }

    /** Overwrites [asset] onto its track at [start]; returns the new clip's end, or null on failure. */
    fun place(asset: MediaAssetDto, start: FrameIndex): FrameIndex? {
        val plan = planPlacement(asset, start, history.timeline) ?: return null
        val clip = plan.clip
        if (!execute(placing(plan.command, clip, plan.type))) return null
        reduce { copy(selectedClipId = clip.id, selectedTrackId = plan.track.id) }
        // The base inserts at a clip boundary, which may differ from [start]: continue from where the clip landed.
        return history.timeline.trackOfClip(clip.id)?.clip(clip.id)?.timelineEnd ?: clip.timelineEnd
    }
}
