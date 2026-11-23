package com.qtekfun.ultimatevideoeditor.ui.editor.layout

import kotlin.math.roundToInt

/*
 * Pure decisions behind the editor's own chrome (top bar, transport row, vertical budget). The composables only
 * apply what these return, so a window or font size that is too small for everything moves actions into an
 * overflow menu (and never clips or overlaps), and the rules are covered by JVM tests. Sizes are dp unless a name says px.
 */

/** Size of an icon button in the chrome (matches `ToolButton`). */
internal const val CHROME_BUTTON_DP = 40f

/** Side padding of the top bar row, both sides together. */
internal const val TOP_BAR_PADDING_DP = 8f

/** Side padding of the transport row, both sides together. */
internal const val TRANSPORT_PADDING_DP = 24f

/** The project name keeps at least this much room (times the font scale); it ends in an ellipsis. */
internal const val TOP_BAR_NAME_MIN_DP = 56f

/** The timecode stops growing with the system font beyond this scale: it is a readout, not running text. */
internal const val TIMECODE_MAX_FONT_SCALE = 1.3f

/** Estimated width of a timecode character at 1x (a 14 sp label, digits and colons), in dp. */
private const val TIMECODE_CHAR_DP = 8.7f
private const val TIMECODE_FULL_CHARS = 11 // HH:MM:SS:FF
private const val TIMECODE_COMPACT_CHARS = 9 // MMM:SS:FF

// ---------------------------------------------------------------------------------------------
// Top bar
// ---------------------------------------------------------------------------------------------

/** The top bar's actions, in the order they are drawn. */
internal enum class TopBarAction { BACK, HELP, LAYOUT, UNDO, REDO, EXPORT }

/** What stays in the bar ([inline], drawing order) and what waits in the overflow menu ([overflow], drawing order). */
internal data class TopBarPlan(val inline: List<TopBarAction>, val overflow: List<TopBarAction>) {
    val hasOverflow: Boolean get() = overflow.isNotEmpty()
}

/** The last to move into the overflow menu comes first. */
internal val TOP_BAR_PRIORITY = listOf(
    TopBarAction.BACK, TopBarAction.UNDO, TopBarAction.EXPORT, TopBarAction.REDO, TopBarAction.LAYOUT, TopBarAction.HELP,
)

/**
 * Chooses what the top bar shows in [widthDp]. The name keeps [TOP_BAR_NAME_MIN_DP] x [fontScale]; the buttons that do not fit
 * (and the overflow button that holds them) go to the overflow menu, least important first. Back, Undo and Export always stay inline.
 */
internal fun resolveTopBar(widthDp: Float, fontScale: Float): TopBarPlan {
    val room = widthDp - TOP_BAR_PADDING_DP - TOP_BAR_NAME_MIN_DP * fontScale.coerceAtLeast(1f)
    val all = TopBarAction.entries.size
    val fitting = (room / CHROME_BUTTON_DP).toInt().coerceIn(0, all)
    // Whatever is left out is reached through one more button.
    val inlineCount = if (fitting >= all) all else (fitting - 1).coerceIn(3, all - 1)
    val keep = TOP_BAR_PRIORITY.take(inlineCount).toSet()
    return TopBarPlan(TopBarAction.entries.filter { it in keep }, TopBarAction.entries.filter { it !in keep })
}

// ---------------------------------------------------------------------------------------------
// Transport row
// ---------------------------------------------------------------------------------------------

internal enum class TransportAction { SAVE_FRAME, ZOOM_DETAIL, FIT }

internal enum class TimecodeForm { FULL, COMPACT }

/**
 * The transport row: how the timecode is written, which of the right-hand actions are inline, which are in the overflow menu,
 * and whether the row had to be split in two lines ([stacked]: timecode and actions above, previous / play / next below).
 */
internal data class TransportPlan(
    val timecode: TimecodeForm,
    val stacked: Boolean,
    val inline: List<TransportAction>,
    val overflow: List<TransportAction>,
)

/** The last to move into the overflow menu comes first. */
private val TRANSPORT_PRIORITY = listOf(TransportAction.FIT, TransportAction.ZOOM_DETAIL, TransportAction.SAVE_FRAME)

/** Width of [form] at [fontScale] (capped at [TIMECODE_MAX_FONT_SCALE]), in dp. */
internal fun timecodeWidthDp(form: TimecodeForm, fontScale: Float): Float {
    val chars = if (form == TimecodeForm.FULL) TIMECODE_FULL_CHARS else TIMECODE_COMPACT_CHARS
    return chars * TIMECODE_CHAR_DP * fontScale.coerceIn(1f, TIMECODE_MAX_FONT_SCALE)
}

/** How many of [count] buttons fit in [room] dp, counting the overflow button that stands in for the ones left out. */
private fun buttonsThatFit(count: Int, room: Float): Int {
    if (count == 0) return 0
    val whole = (room / CHROME_BUTTON_DP).toInt()
    return if (whole >= count) count else (whole - 1).coerceAtLeast(0)
}

/**
 * Plans the transport row for [widthDp]. [actions] are the right-hand actions that exist (zoom only when the setting is on).
 * Previous / play / next stay centred, the timecode on one side and the actions on the other, each with half of what the three
 * buttons leave. When that is not enough the actions move into the overflow menu first (Fit is the last one standing), then the
 * timecode turns compact, and only then the row splits in two lines.
 */
internal fun resolveTransport(widthDp: Float, fontScale: Float, actions: List<TransportAction>): TransportPlan {
    val present = TRANSPORT_PRIORITY.filter { it in actions }
    val width = widthDp - TRANSPORT_PADDING_DP
    val side = (width - 3 * CHROME_BUTTON_DP) / 2f

    fun plan(form: TimecodeForm, stacked: Boolean, room: Float): TransportPlan {
        val keep = present.take(buttonsThatFit(present.size, room)).toSet()
        return TransportPlan(
            form, stacked,
            TransportAction.entries.filter { it in keep },
            TransportAction.entries.filter { it in actions && it !in keep },
        )
    }

    for (form in TimecodeForm.entries) {
        // Even the overflow button alone needs a button's room on its side.
        if (timecodeWidthDp(form, fontScale) <= side && (present.isEmpty() || side >= CHROME_BUTTON_DP)) return plan(form, false, side)
    }
    for (form in TimecodeForm.entries) {
        val room = width - timecodeWidthDp(form, fontScale) - 8f
        if (present.isEmpty() || room >= CHROME_BUTTON_DP) return plan(form, true, room)
    }
    return TransportPlan(TimecodeForm.COMPACT, true, emptyList(), TransportAction.entries.filter { it in actions })
}

// ---------------------------------------------------------------------------------------------
// Space budget of the middle block
// ---------------------------------------------------------------------------------------------

/** The least height the picture is given in the stacked and workbench layouts, so it stays a picture. */
internal const val MIN_PREVIEW_DP = 72f

/** The least height the timeline is given: the ruler and a lane or two. */
internal const val MIN_TIMELINE_DP = 96f

/** The least height the controls keep visible (one row); taller controls scroll instead of taking the rest. */
internal const val MIN_CHROME_DP = 48f

/** Smallest widths in the side by side workspace: the picture, and the controls with the timeline. */
internal const val MIN_SIDE_PREVIEW_DP = 96f
internal const val MIN_SIDE_REST_DP = 240f

/**
 * The tallest the controls may be, in px, in a block of [total] px that also holds a divider of [handle] px, a preview of at
 * least [minPreview] and a timeline of at least [minTimeline]. They keep [minChrome] whatever happens; taller controls scroll.
 * Never more than the room there is.
 */
internal fun chromeMaxHeight(total: Int, handle: Int, minPreview: Int, minTimeline: Int, minChrome: Int): Int {
    val room = (total - handle).coerceAtLeast(0)
    return (room - minPreview - minTimeline).coerceAtLeast(minChrome).coerceAtMost(room)
}

/** Heights, in px, of the three parts of the stacked block; with the divider they add up to the block. */
internal data class StackedHeights(val chrome: Int, val preview: Int, val timeline: Int)

/**
 * Divides [total] px between a measured [chrome] (at most what [chromeMaxHeight] allowed), the preview and the timeline.
 * The preview takes [fraction] of what the controls and the [handle] leave, never less than [minPreview] and never so much that
 * the timeline falls under [minTimeline]; when the room is shorter than both minimums they share it in proportion.
 */
internal fun resolveStacked(total: Int, handle: Int, chrome: Int, fraction: Float, minPreview: Int, minTimeline: Int): StackedHeights {
    val room = (total - handle).coerceAtLeast(0)
    val chromePx = chrome.coerceIn(0, room)
    val flexible = room - chromePx
    val preview = if (flexible >= minPreview + minTimeline) {
        (flexible * fraction).roundToInt().coerceIn(minPreview, flexible - minTimeline)
    } else {
        (flexible.toLong() * minPreview / (minPreview + minTimeline).coerceAtLeast(1)).toInt()
    }
    return StackedHeights(chromePx, preview, flexible - preview)
}

/**
 * Splits [room] px of the side by side workspace into the picture and the rest by [fraction], keeping [minPreview] and [minRest]
 * (shared in proportion when the room is shorter than both). Nothing is lost: the two add up to [room].
 */
internal fun resolveSideSplit(room: Int, fraction: Float, minPreview: Int, minRest: Int): SidePx {
    val space = room.coerceAtLeast(0)
    val preview = if (space >= minPreview + minRest) {
        (space * fraction).roundToInt().coerceIn(minPreview, space - minRest)
    } else {
        (space.toLong() * minPreview / (minPreview + minRest).coerceAtLeast(1)).toInt()
    }
    return SidePx(preview, space - preview)
}

// ---------------------------------------------------------------------------------------------
// Chips over the timeline
// ---------------------------------------------------------------------------------------------

/** Where the drop-mode and marker chips go in a timeline view: [topDp] from its top, [stacked] in one column or apart. */
internal data class OverlayChips(val stacked: Boolean, val topDp: Float)

/** The ruler's height: chips sit under it when the view is tall enough to show lanes below them. */
private const val RULER_CLEARANCE_DP = 40f

/** Both chips side by side need about this much width at 1x (the drop chip is the wide one). */
private const val CHIPS_ABREAST_DP = 520f

private const val CHIPS_UNDER_RULER_MIN_HEIGHT_DP = 160f

/**
 * Places the chips for a timeline view of [widthDp] x [heightDp]: side by side only when there is room for both (more as the font
 * grows), otherwise stacked in the middle; over the ruler instead of under it when the view is too short to hold them below it.
 */
internal fun overlayChipLayout(widthDp: Float, heightDp: Float, fontScale: Float): OverlayChips =
    OverlayChips(
        stacked = widthDp < CHIPS_ABREAST_DP * fontScale.coerceAtLeast(1f),
        topDp = if (heightDp >= CHIPS_UNDER_RULER_MIN_HEIGHT_DP) RULER_CLEARANCE_DP else 4f,
    )
