package com.ultimatevideo.uveditor.ui.editor

import com.ultimatevideo.uveditor.engine.timeline.TimelineHit

/**
 * Reordering lanes by dragging their header: a long press on the name tab at the left edge of a lane picks it up,
 * the finger over another lane of the same kind chooses where it lands (an indicator bar shows it), and release
 * applies it as one undo step (`EditCommand.MoveTrackTo`). The base never moves and lanes only reorder among their
 * own kind, exactly like the up/down buttons.
 */
sealed interface LaneDragIntent : EditorIntent {
    /** The lane under [hit] (a lane header) was long-pressed. */
    data class Start(val hit: TimelineHit) : LaneDragIntent

    /** The finger moved; [hit] is the hit-test at its position. */
    data class Move(val hit: TimelineHit) : LaneDragIntent

    /** The finger lifted ([commit] true) or the gesture was cancelled. */
    data class End(val commit: Boolean) : LaneDragIntent
}

/** A lane being dragged: its id, where it started and the display index of the lane it would take the place of. */
data class LaneDrag(val trackId: String, val fromIndex: Int, val toIndex: Int)
