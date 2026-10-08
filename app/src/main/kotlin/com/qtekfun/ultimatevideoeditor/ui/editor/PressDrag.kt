package com.qtekfun.ultimatevideoeditor.ui.editor

import kotlin.math.hypot

/** What the timeline canvas does next with a press that was held on a clip. */
enum class PressDragStep {
    /** Nothing yet. */
    NONE,

    /** The held finger moved past the slop: the clip under it is picked up. */
    START_DRAG,

    /** The finger lifted without moving: the long press stands as a selection toggle. */
    TOGGLE_SELECTION,
}

/**
 * Press-and-drag on a clip (hold, then move without lifting), the LumaFusion way to move a clip.
 *
 * The platform gesture detector stops reporting scrolls once its long press has fired, so before this a hold followed by a
 * drag did nothing at all, and a long press on a selected clip even deselected it. A long press on a clip now keeps the touch
 * alive instead: if the finger then moves past [slopPx] the clip is dragged (so the hold has also selected it); if it lifts
 * first, the long press keeps its old meaning, toggling the clip in the selection. A clip that was not selected is toggled on
 * by the long press itself, a clip that already was is only toggled (off) on release, so a drag never loses its selection.
 * Pure arithmetic, no Android types.
 */
class PressDrag(private val slopPx: Float) {
    private var armed = false
    private var downX = 0f
    private var downY = 0f
    private var toggleOnRelease = false
    private var moved = false

    /** True from the long press until the finger lifts, whether or not it moved. */
    val isArmed: Boolean get() = armed

    /** The long press fired at ([x], [y]) on a clip; [alreadySelected] is whether the clip was part of the selection. */
    fun onLongPress(x: Float, y: Float, alreadySelected: Boolean) {
        armed = true
        downX = x
        downY = y
        toggleOnRelease = alreadySelected
    }

    /** A move while armed; the first one past the slop starts the drag, later ones return [PressDragStep.NONE]. */
    fun onMove(x: Float, y: Float): PressDragStep {
        if (!armed || moved) return PressDragStep.NONE
        if (hypot(x - downX, y - downY) < slopPx) return PressDragStep.NONE
        moved = true
        toggleOnRelease = false
        return PressDragStep.START_DRAG
    }

    /** The finger lifted or the gesture was cancelled ([cancelled]). */
    fun onEnd(cancelled: Boolean): PressDragStep {
        val step = if (armed && !moved && toggleOnRelease && !cancelled) PressDragStep.TOGGLE_SELECTION else PressDragStep.NONE
        armed = false
        moved = false
        toggleOnRelease = false
        return step
    }
}
