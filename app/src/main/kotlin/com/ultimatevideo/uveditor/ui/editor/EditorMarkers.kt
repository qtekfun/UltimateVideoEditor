package com.ultimatevideo.uveditor.ui.editor

import com.ultimatevideo.uveditor.domain.MarkerColor

/**
 * The marker popup: what is typed for the marker [markerId] while the popup is open. The edits are shown live
 * (the canvas draws them from a preview) and become one undo step when the popup closes.
 */
data class MarkerPopup(
    val markerId: String,
    val frame: Long,
    val name: String,
    val note: String,
    val color: MarkerColor?,
    val isBeat: Boolean,
    val hasPrevious: Boolean,
    val hasNext: Boolean,
)

/** How long the "Marker added" hint stays up. */
internal const val MARKER_HINT_MILLIS = 3_000L

/** The brief confirmation after a one-tap marker: shown over the timeline with an Edit action, gone after a moment. */
data class MarkerHint(val markerId: String, val frame: Long)

sealed interface MarkerIntent : EditorIntent {
    /** One tap on the marker button: drops a marker at the playhead; one already there opens its popup instead. */
    data object AddAtPlayhead : MarkerIntent

    /** Opens the popup of a marker (from the hint's Edit action or a tap on the ruler). */
    data class Open(val markerId: String) : MarkerIntent
    data class NameChanged(val text: String) : MarkerIntent
    data class NoteChanged(val text: String) : MarkerIntent
    data class ColorChosen(val color: MarkerColor?) : MarkerIntent

    /** Closes the popup, keeping the edits (one undo step when anything changed). */
    data object Close : MarkerIntent
    data object DeleteOpen : MarkerIntent

    /** Previous / next marker from the popup: closes the edit and opens that marker, moving the playhead to it. */
    data object PopupPrevious : MarkerIntent
    data object PopupNext : MarkerIntent

    /** Moves the playhead to the previous / next marker without opening anything (the marker button menu). */
    data object SeekPrevious : MarkerIntent
    data object SeekNext : MarkerIntent

    /** Takes the hint away at once (its own timer does it otherwise). */
    data object DismissHint : MarkerIntent
}
