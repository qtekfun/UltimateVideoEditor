package com.qtekfun.ultimatevideoeditor.ui.editor

import com.qtekfun.ultimatevideoeditor.domain.AlignEdge
import com.qtekfun.ultimatevideoeditor.domain.GroupTransition
import com.qtekfun.ultimatevideoeditor.engine.timeline.TimelineHit

/**
 * Selecting several clips and acting on them together. In select mode a tap adds or removes a clip and
 * dragging empty space draws a marquee; a long press toggles a clip in or out of the selection in any
 * mode. The clip last chosen stays the primary one (the inspector's); a drag of any selected clip moves
 * the whole group. Every group edit is one undo step (see `domain.GroupOps`).
 */
sealed interface SelectionIntent : EditorIntent {
    data object ToggleSelectMode : SelectionIntent

    /** A long press on a clip toggles it in the selection (ignored elsewhere). */
    data class LongPress(val hit: TimelineHit) : SelectionIntent

    /** The clips (native keys) inside the marquee rectangle were chosen; they are added to the selection. */
    data class Marquee(val clipKeys: List<Long>) : SelectionIntent

    /** Selects every clip of the selected lane / every clip that has not ended at the playhead / every clip. */
    data object SelectLane : SelectionIntent
    data object SelectFromPlayhead : SelectionIntent
    data object SelectAll : SelectionIntent
    data object ClearSelection : SelectionIntent

    data object Copy : SelectionIntent
    data object Cut : SelectionIntent
    data object Paste : SelectionIntent
    data object Duplicate : SelectionIntent
    data object DeleteSelection : SelectionIntent

    /** Copies the transform, effects, gain and speed of the clipboard's first clip onto the selected clips. */
    data object PasteAttributes : SelectionIntent

    data class SetGroupSpeed(val num: Long, val den: Long) : SelectionIntent
    data class SetGroupGain(val gainDb: Double) : SelectionIntent
    data class SetGroupOpacity(val opacity: Double) : SelectionIntent
    data class Align(val edge: AlignEdge) : SelectionIntent
    data class ApplyTransitions(val mode: GroupTransition) : SelectionIntent
}

/**
 * What the timeline canvas needs from the editor to select clips: whether select mode is on (a drag on empty
 * space then draws a marquee), and where a long press on a clip and a finished marquee go.
 */
interface TimelineSelecting {
    val selectMode: Boolean
    fun onLongPress(hit: TimelineHit)
    fun onMarquee(clipKeys: List<Long>)
}

/** Every clip that is selected: [EditorState.selectedClipIds] when it still holds the primary clip, else just the primary. */
val EditorState.selection: Set<String>
    get() {
        val primary = selectedClipId ?: return emptySet()
        if (primary !in selectedClipIds) return setOf(primary)
        return selectedClipIds.filterTo(LinkedHashSet()) { visibleTimeline.trackOfClip(it) != null }.ifEmpty { setOf(primary) }
    }

/** True when more than one clip is selected, so the selection bar shows and a drag moves the group. */
val EditorState.isMultiSelection: Boolean get() = selection.size > 1
