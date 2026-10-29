package com.qtekfun.ultimatevideoeditor.ui.editor

import com.qtekfun.ultimatevideoeditor.domain.DropTarget

/**
 * Whether the 'add a lane' target is open during a drag, and the lane index the finger landed on when the row the open itself
 * adds pushed the layout under it ([anchor], null until that first lane hit).
 */
internal data class NewLaneLatch(val open: Boolean = false, val anchor: Int? = null)

internal data class ResolvedTarget(val target: DropTarget?, val latch: NewLaneLatch)

/**
 * The drop target under the finger, with the 'add a lane' target held steady.
 *
 * Opening it shows a provisional lane, so the timeline grows by a row and the same finger position can then hit an existing
 * lane (or the provisional one at another index). Read naively that closes the target, the layout returns and it opens again.
 * So, once open, the target stays on 'add a lane' while the finger is over the room above the lanes, over the provisional
 * lane, or over the single lane it first lands on after the shift ([NewLaneLatch.anchor]); it closes when the finger leaves
 * the panel or moves to another lane. [shownIds] is the lane order of the timeline being shown (provisional lane included),
 * [committedIds] those of the committed one. [canOpen] is false for a drag that cannot make a lane (nothing is shown, nothing shifts).
 * A gap between lanes keeps [previous].
 */
internal fun resolveDropTarget(
    zone: DragZone,
    trackIndex: Int,
    shownIds: List<String>,
    committedIds: Set<String>,
    previous: DropTarget?,
    latch: NewLaneLatch,
    canOpen: Boolean,
): ResolvedTarget = when (zone) {
    DragZone.OUTSIDE -> ResolvedTarget(DropTarget.Outside, NewLaneLatch())
    DragZone.ABOVE_LANES -> ResolvedTarget(DropTarget.AboveLanes, NewLaneLatch(open = canOpen))
    DragZone.LANES -> {
        val id = shownIds.getOrNull(trackIndex)
        when {
            id == null -> ResolvedTarget(previous, latch)
            id !in committedIds -> ResolvedTarget(DropTarget.AboveLanes, NewLaneLatch(open = canOpen))
            canOpen && latch.open && latch.anchor == null -> ResolvedTarget(DropTarget.AboveLanes, NewLaneLatch(open = true, anchor = trackIndex))
            canOpen && latch.open && latch.anchor == trackIndex -> ResolvedTarget(DropTarget.AboveLanes, latch)
            else -> ResolvedTarget(DropTarget.Lane(id), NewLaneLatch())
        }
    }
}
