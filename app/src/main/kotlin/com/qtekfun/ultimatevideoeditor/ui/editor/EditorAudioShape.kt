package com.qtekfun.ultimatevideoeditor.ui.editor

import com.qtekfun.ultimatevideoeditor.engine.timeline.TimelineHit

/**
 * Shaping the sound of the selected audio clip on the timeline canvas: dragging its fade circles (top corners) and the
 * points of its volume curve, double tapping the clip to add a point and a point to remove it. What a drag means is decided
 * by [AudioShapeGesture]; the canvas only draws and reports hits. Every finished gesture is one undo step.
 */
sealed interface AudioShapeIntent : EditorIntent {
    /** A drag started on a fade circle or a curve point ([hit]). */
    data class Start(val hit: TimelineHit) : AudioShapeIntent

    /** The finger moved; [hit] is the hit-test at its position. */
    data class Move(val hit: TimelineHit) : AudioShapeIntent

    /** The finger lifted ([commit] true) or the gesture was cancelled. */
    data class End(val commit: Boolean) : AudioShapeIntent

    /** A double tap at [hit]: on the selected audio clip it adds a volume point, on a point it removes it. */
    data class DoubleTap(val hit: TimelineHit) : AudioShapeIntent

    /** Adds a volume point at the playhead, holding the volume the clip has there (the sheet's button). */
    data object AddPointAtPlayhead : AudioShapeIntent
}

/** Receives the shaping gestures of the canvas; see [AudioShapeIntent]. */
interface TimelineShaping {
    /** True when a drag starting at [hit] moves a handle or a point rather than scrolling. */
    fun canDrag(hit: TimelineHit): Boolean
    fun onDragStart(hit: TimelineHit)
    fun onDragMove(hit: TimelineHit)
    fun onDragEnd(commit: Boolean)

    /** A double tap landed on [hit]. */
    fun onDoubleTap(hit: TimelineHit)
}
