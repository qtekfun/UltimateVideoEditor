package com.qtekfun.ultimatevideoeditor.ui.editor.project

import com.qtekfun.ultimatevideoeditor.domain.Timeline
import com.qtekfun.ultimatevideoeditor.domain.TrackType
import com.qtekfun.ultimatevideoeditor.ui.editor.EditorState

/**
 * The state after the undo history moved to [committed] (an edit, an undo or a redo): the timeline and the undo flags are replaced, edits that
 * were only on screen are dropped, and the selection falls back to what still exists.
 */
internal fun EditorState.syncedTo(committed: Timeline, canUndo: Boolean, canRedo: Boolean): EditorState =
    copy(
        timeline = committed,
        dragPreview = null,
        dragOverlay = null,
        audioSessionActive = false,
        noiseRegion = noiseRegion?.takeIf { committed.trackOfClip(it.clipId) != null },
        dropHint = null,
        canUndo = canUndo,
        canRedo = canRedo,
        selectedClipId = selectedClipId?.takeIf { committed.trackOfClip(it) != null },
        selectedClipIds = selectedClipIds.filterTo(LinkedHashSet()) { committed.trackOfClip(it) != null },
        // The active lane follows the selected clip when an edit moved it to another lane (a clip lifted to V2 makes V2 the lane the
        // next added media goes to); if the selected track vanished (undo, removal), fall back to the first video track.
        selectedTrackId = run {
            val clipId = selectedClipId
            val before = clipId?.let { this.timeline.trackOfClip(it)?.id }
            val now = clipId?.let { committed.trackOfClip(it)?.id }
            if (now != null && now != before && selectedTrackId == before) now
            else selectedTrackId?.takeIf { committed.track(it) != null } ?: committed.tracks.firstOrNull { it.type == TrackType.VIDEO }?.id
        },
    )
