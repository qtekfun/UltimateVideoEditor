package com.qtekfun.ultimatevideoeditor.ui.editor.timeline

import com.qtekfun.ultimatevideoeditor.R
import com.qtekfun.ultimatevideoeditor.domain.AddMarker
import com.qtekfun.ultimatevideoeditor.domain.EditMarker
import com.qtekfun.ultimatevideoeditor.domain.EditResult
import com.qtekfun.ultimatevideoeditor.domain.FrameIndex
import com.qtekfun.ultimatevideoeditor.domain.Marker
import com.qtekfun.ultimatevideoeditor.domain.MarkerAnchors
import com.qtekfun.ultimatevideoeditor.domain.MarkerKind
import com.qtekfun.ultimatevideoeditor.domain.MarkerOps
import com.qtekfun.ultimatevideoeditor.domain.RemoveMarker
import com.qtekfun.ultimatevideoeditor.domain.SetMarkerDone
import com.qtekfun.ultimatevideoeditor.domain.Timeline
import com.qtekfun.ultimatevideoeditor.ui.editor.*
import com.qtekfun.ultimatevideoeditor.ui.editor.playback.PlaybackController
import com.qtekfun.ultimatevideoeditor.ui.text.UiText
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Markers on the ruler: adding one at the playhead, the popup that edits its name, note and colour, stepping between markers and the
 * hint shown after adding.
 */
internal class MarkerHandler(
    host: EditorHost,
    private val playback: PlaybackController,
) : EditorHost by host {

    fun markerIntent(intent: MarkerIntent) {
        when (intent) {
            MarkerIntent.AddAtPlayhead -> addMarkerAtPlayhead()
            is MarkerIntent.Open -> openMarker(intent.markerId)
            is MarkerIntent.NameChanged -> editMarkerPopup { copy(name = intent.text.replace('\n', ' ').take(MarkerOps.MAX_NAME_LENGTH)) }
            is MarkerIntent.NoteChanged -> editMarkerPopup { copy(note = intent.text.take(MarkerOps.MAX_NOTE_LENGTH)) }
            is MarkerIntent.ColorChosen -> editMarkerPopup { copy(color = intent.color) }
            is MarkerIntent.StickChanged -> editMarkerPopup { if (canStick) copy(stick = intent.stick) else this }
            is MarkerIntent.DoneChanged -> editMarkerPopup { copy(done = intent.done) }
            MarkerIntent.OpenList -> { closeMarkerPopup(); reduce { copy(flagListOpen = true) } }
            MarkerIntent.CloseList -> reduce { copy(flagListOpen = false) }
            is MarkerIntent.SetDone -> execute(SetMarkerDone(intent.markerId, intent.done))
            is MarkerIntent.GoTo -> history.timeline.markers.firstOrNull { it.id == intent.markerId }?.let { playback.pausePlayback(); playback.setPlayhead(it.frame.value) }
            MarkerIntent.Close -> closeMarkerPopup()
            MarkerIntent.DeleteOpen -> deleteOpenMarker()
            MarkerIntent.PopupPrevious -> stepMarkerPopup(forward = false)
            MarkerIntent.PopupNext -> stepMarkerPopup(forward = true)
            MarkerIntent.SeekPrevious -> seekMarker(forward = false)
            MarkerIntent.SeekNext -> seekMarker(forward = true)
            MarkerIntent.DismissHint -> clearMarkerHint()
        }
    }

    /**
     * One tap: the marker is on the ruler at once (one command, nothing else runs), with a short "added" hint
     * that offers Edit. A marker already at the playhead is edited instead of duplicated.
     */
    private fun addMarkerAtPlayhead() {
        closeMarkerPopup()
        val playhead = state.value.playhead
        val existing = MarkerOps.nearest(history.timeline.markers, playhead, MARKER_TOGGLE_RADIUS_FRAMES)
        if (existing != null) {
            openMarker(existing.id)
            return
        }
        val marker = Marker("marker-${idGenerator()}", playhead, MarkerKind.MANUAL)
        // A new marker sticks to the clip under the playhead (a gap leaves it free).
        if (execute(AddMarker(marker, stick = true))) showMarkerHint(MarkerHint(marker.id, marker.frame.value))
    }

    private var markerHintJob: Job? = null

    private fun showMarkerHint(hint: MarkerHint) {
        reduce { copy(markerHint = hint) }
        markerHintJob?.cancel()
        markerHintJob = scope.launch {
            delay(MARKER_HINT_MILLIS)
            reduce { copy(markerHint = null) }
        }
    }

    private fun clearMarkerHint() {
        markerHintJob?.cancel()
        markerHintJob = null
        if (state.value.markerHint != null) reduce { copy(markerHint = null) }
    }

    private fun popupOf(marker: Marker, markers: List<Marker>, timeline: Timeline = history.timeline) = MarkerPopup(
        markerId = marker.id,
        frame = marker.frame.value,
        name = marker.name.orEmpty(),
        note = marker.note.orEmpty(),
        color = marker.color,
        isBeat = marker.kind == MarkerKind.BEAT,
        stick = marker.isAnchored,
        canStick = marker.kind == MarkerKind.MANUAL && (marker.isAnchored || MarkerAnchors.targetAt(timeline, marker.frame) != null),
        hasPrevious = MarkerOps.previous(markers, marker.frame) != null,
        hasNext = MarkerOps.next(markers, marker.frame) != null,
        done = marker.done,
    )

    fun openMarker(markerId: String) {
        closeMarkerPopup()
        val markers = history.timeline.markers
        val marker = markers.firstOrNull { it.id == markerId } ?: return
        clearMarkerHint()
        reduce { copy(markerPopup = popupOf(marker, markers)) }
    }

    /** A change in the popup: shown live through the preview timeline, committed (one step) by [closeMarkerPopup]. */
    private fun editMarkerPopup(change: MarkerPopup.() -> MarkerPopup) {
        val popup = state.value.markerPopup ?: return
        val edited = popup.change()
        val preview = (EditMarker(edited.markerId, edited.name.ifBlank { null }, edited.note.ifBlank { null }, edited.color, edited.stick, edited.done).apply(history.timeline) as? EditResult.Success)?.value
        reduce { copy(markerPopup = edited, dragPreview = preview ?: dragPreview) }
    }

    fun closeMarkerPopup() {
        val popup = state.value.markerPopup ?: return
        reduce { copy(markerPopup = null, dragPreview = null) }
        val current = history.timeline.markers.firstOrNull { it.id == popup.markerId } ?: return
        val name = popup.name.trim().ifBlank { null }
        val note = popup.note.trim().ifBlank { null }
        if (current.name == name && current.note == note && current.color == popup.color && current.isAnchored == popup.stick && current.done == popup.done) return
        execute(EditMarker(popup.markerId, name, note, popup.color, popup.stick, popup.done))
    }

    private fun deleteOpenMarker() {
        val popup = state.value.markerPopup ?: return
        reduce { copy(markerPopup = null, dragPreview = null) }
        execute(RemoveMarker(popup.markerId))
    }

    private fun stepMarkerPopup(forward: Boolean) {
        val popup = state.value.markerPopup ?: return
        closeMarkerPopup()
        val markers = history.timeline.markers
        val from = markers.firstOrNull { it.id == popup.markerId }?.frame ?: FrameIndex(popup.frame)
        val target = (if (forward) MarkerOps.next(markers, from) else MarkerOps.previous(markers, from)) ?: return
        playback.pausePlayback()
        playback.setPlayhead(target.frame.value)
        reduce { copy(markerPopup = popupOf(target, markers)) }
    }

    private fun seekMarker(forward: Boolean) {
        val markers = history.timeline.markers
        val from = state.value.playhead
        val target = if (forward) MarkerOps.next(markers, from) else MarkerOps.previous(markers, from)
        if (target == null) {
            emit(EditorEffect.ShowMessage(if (forward) UiText.res(R.string.ed_vm_no_marker_after_the_playhead) else UiText.res(R.string.ed_vm_no_marker_before_the_playhead)))
            return
        }
        playback.pausePlayback()
        playback.setPlayhead(target.frame.value)
    }
}
