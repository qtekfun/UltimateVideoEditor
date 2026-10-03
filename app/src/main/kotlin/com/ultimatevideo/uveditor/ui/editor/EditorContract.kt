package com.ultimatevideo.uveditor.ui.editor

import com.ultimatevideo.uveditor.data.model.MediaAssetDto
import com.ultimatevideo.uveditor.domain.FrameIndex
import com.ultimatevideo.uveditor.domain.FrameRate
import com.ultimatevideo.uveditor.domain.Timeline
import com.ultimatevideo.uveditor.domain.TrackType
import com.ultimatevideo.uveditor.engine.timeline.TimelineHit
import com.ultimatevideo.uveditor.mvi.UiEffect
import com.ultimatevideo.uveditor.mvi.UiIntent
import com.ultimatevideo.uveditor.mvi.UiState

data class EditorState(
    val isLoading: Boolean = true,
    val loadError: String? = null,
    val projectName: String = "",
    val fps: FrameRate = FrameRate(30, 1),
    /** Committed timeline; only changes through the undo history. */
    val timeline: Timeline = Timeline(),
    /** Provisional timeline while a clip is being dragged; discarded or committed on release. */
    val dragPreview: Timeline? = null,
    val assets: List<MediaAssetDto> = emptyList(),
    val playhead: FrameIndex = FrameIndex.ZERO,
    val selectedClipId: String? = null,
    /** Where imports land and what Remove track acts on. Kept in step with clip selection. */
    val selectedTrackId: String? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val isImporting: Boolean = false,
    val isPlaying: Boolean = false,
) : UiState {
    /** What the canvas should draw right now. */
    val visibleTimeline: Timeline get() = dragPreview ?: timeline

    /** "V1", "A2": the position of the selected track among tracks of its type, top to bottom. */
    val selectedTrackLabel: String?
        get() {
            val track = timeline.tracks.firstOrNull { it.id == selectedTrackId } ?: return null
            val ofType = timeline.tracks.filter { it.type == track.type }
            val prefix = when (track.type) {
                TrackType.VIDEO -> "V"
                TrackType.AUDIO -> "A"
                TrackType.TITLE -> "T"
            }
            // Video stacks upward like a mixer: the top lane is the highest number.
            val number = if (track.type == TrackType.VIDEO) ofType.size - ofType.indexOf(track) else ofType.indexOf(track) + 1
            return "$prefix$number"
        }
}

sealed interface EditorIntent : UiIntent {
    data class TapTimeline(val hit: TimelineHit) : EditorIntent
    data class SetPlayhead(val frame: Long) : EditorIntent

    data class DragStart(val hit: TimelineHit) : EditorIntent
    data class DragMove(val frame: Long, val trackIndex: Int) : EditorIntent
    data class DragEnd(val commit: Boolean) : EditorIntent

    data object SplitAtPlayhead : EditorIntent
    data object RippleDeleteSelected : EditorIntent
    data object RippleAppendSelected : EditorIntent
    data object TogglePlay : EditorIntent

    data class AddTrack(val type: TrackType) : EditorIntent
    data object RemoveSelectedTrack : EditorIntent

    /** Jump to the previous / next clip boundary (start or end of a clip), or the timeline start. */
    data object SeekPrevious : EditorIntent
    data object SeekNext : EditorIntent
    data object Undo : EditorIntent
    data object Redo : EditorIntent

    data class ImportMedia(val uris: List<String>) : EditorIntent
    data class AddAsset(val assetId: String) : EditorIntent

    /** Save now (app going to background). */
    data object Flush : EditorIntent

    /** Save, then close the editor. */
    data object Back : EditorIntent

    data class ReportError(val message: String) : EditorIntent
}

sealed interface EditorEffect : UiEffect {
    data class ShowMessage(val text: String) : EditorEffect
    data object Close : EditorEffect
}
