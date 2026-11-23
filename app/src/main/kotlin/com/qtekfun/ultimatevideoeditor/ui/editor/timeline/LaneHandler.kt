package com.qtekfun.ultimatevideoeditor.ui.editor.timeline

import com.qtekfun.ultimatevideoeditor.R
import com.qtekfun.ultimatevideoeditor.domain.ClipDeletion
import com.qtekfun.ultimatevideoeditor.domain.EditCommand
import com.qtekfun.ultimatevideoeditor.domain.LaneOps
import com.qtekfun.ultimatevideoeditor.domain.Track
import com.qtekfun.ultimatevideoeditor.domain.TrackType
import com.qtekfun.ultimatevideoeditor.engine.timeline.HitKind
import com.qtekfun.ultimatevideoeditor.ui.editor.*
import com.qtekfun.ultimatevideoeditor.ui.text.UiText

/**
 * Lanes (tracks): adding, removing and reordering them, and dragging a lane by its header.
 */
internal class LaneHandler(
    host: EditorHost,
) : EditorHost by host {

    /**
     * New video tracks go on top of the video stack (they overlay the ones below); new audio
     * tracks go at the bottom. Track order is display order, top to bottom.
     */
    fun addTrack(type: TrackType) {
        if (type == TrackType.TITLE) return
        val tracks = history.timeline.tracks
        val index = if (type == TrackType.VIDEO) tracks.indexOfFirst { it.type == TrackType.VIDEO }.takeIf { it >= 0 } ?: 0 else tracks.size
        val prefix = if (type == TrackType.VIDEO) "track-v" else "track-a"
        val track = Track(uniqueTrackId(tracks, prefix), type)
        if (execute(EditCommand.AddTrack(track, index))) reduce { copy(selectedTrackId = track.id, selectedClipId = null) }
    }

    fun moveSelectedTrack(delta: Int) {
        val id = state.value.selectedTrackId
        if (id == null) {
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_tap_a_track_to_select)))
            return
        }
        execute(EditCommand.MoveTrack(id, delta))
    }

    /** Lane header drag: pick a lane up, choose where it lands among the lanes of its kind, apply on release. */
    fun laneDragIntent(intent: LaneDragIntent) {
        val timeline = history.timeline
        when (intent) {
            is LaneDragIntent.Start -> {
                val index = intent.hit.trackIndex
                val track = timeline.tracks.getOrNull(index) ?: return
                if (intent.hit.kind != HitKind.LANE_HEADER) return
                reduce { copy(selectedTrackId = track.id) }
                if (LaneOps.laneDropTarget(timeline, track.id, index) == null) {
                    val isBase = ClipDeletion.baseTrack(timeline)?.id == track.id
                    emit(
                        EditorEffect.ShowMessage(
                            if (isBase) UiText.res(R.string.ed_vm_the_base_track_stays_at) else UiText.res(R.string.ed_vm_this_is_the_only_lane),
                        ),
                    )
                    return
                }
                reduce { copy(laneDrag = LaneDrag(track.id, index, index)) }
            }
            is LaneDragIntent.Move -> {
                val drag = state.value.laneDrag ?: return
                val hover = when (intent.hit.kind) {
                    // Above the lanes or in the ruler: the top of the stack.
                    HitKind.ABOVE_LANES, HitKind.RULER, HitKind.PLAYHEAD, HitKind.MARKER -> 0
                    // Outside the panel or in the gap between lanes: keep the last target.
                    HitKind.OUTSIDE, HitKind.NONE -> return
                    else -> intent.hit.trackIndex
                }
                if (hover < 0) return
                val target = LaneOps.laneDropTarget(timeline, drag.trackId, hover) ?: return
                if (target != drag.toIndex) reduce { copy(laneDrag = drag.copy(toIndex = target)) }
            }
            is LaneDragIntent.End -> {
                val drag = state.value.laneDrag ?: return
                reduce { copy(laneDrag = null) }
                if (intent.commit && drag.toIndex != drag.fromIndex) execute(EditCommand.MoveTrackTo(drag.trackId, drag.toIndex))
            }
        }
    }

    fun removeSelectedTrack() {
        val track = history.timeline.tracks.firstOrNull { it.id == state.value.selectedTrackId }
        if (track == null) {
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_tap_a_track_to_select)))
            return
        }
        if (track.type != TrackType.TITLE && history.timeline.tracks.count { it.type == track.type } <= 1) {
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_keep_at_least_one_track, UiText.res(track.type.nameRes()))))
            return
        }
        execute(EditCommand.RemoveTrack(track.id))
    }
}
