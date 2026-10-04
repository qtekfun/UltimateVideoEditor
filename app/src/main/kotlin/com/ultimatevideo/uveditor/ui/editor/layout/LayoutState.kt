package com.ultimatevideo.uveditor.ui.editor.layout

/** Window size class by width, the same breakpoints Material uses. */
enum class WidthClass {
    COMPACT,
    MEDIUM,
    EXPANDED,
    ;

    companion object {
        fun of(widthDp: Float): WidthClass = when {
            widthDp < 600f -> COMPACT
            widthDp < 840f -> MEDIUM
            else -> EXPANDED
        }
    }
}

/** The window the editor is laid out in. */
data class WindowMetrics(val widthDp: Float, val heightDp: Float) {
    val widthClass: WidthClass get() = WidthClass.of(widthDp)
    val landscape: Boolean get() = widthDp > heightDp

    /** Side docks need room: below this width everything stays at the bottom or over the timeline. */
    val sideDocksAllowed: Boolean get() = widthDp >= SIDE_DOCK_MIN_WIDTH_DP

    /** The widest a side panel may be, so the editor itself always keeps most of the window. */
    val maxSideWidthDp: Float get() = minOf(MAX_SIDE_WIDTH_DP, widthDp * MAX_SIDE_SHARE).coerceAtLeast(MIN_SIDE_WIDTH_DP)

    companion object {
        const val SIDE_DOCK_MIN_WIDTH_DP = 600f
        const val MIN_SIDE_WIDTH_DP = 200f
        const val MAX_SIDE_WIDTH_DP = 520f
        const val MAX_SIDE_SHARE = 0.45f
    }
}

/** Layouts are remembered per window size class and orientation (a phone upright and sideways differ). */
data class LayoutKey(val widthClass: WidthClass, val landscape: Boolean) {
    val id: String get() = "${widthClass.name.lowercase()}-${if (landscape) "landscape" else "portrait"}"

    companion object {
        fun of(window: WindowMetrics) = LayoutKey(window.widthClass, window.landscape)
    }
}

/** The panels that can be moved around; the preview and the timeline are always there. */
enum class Panel { TRAY, INSPECTOR }

/**
 * Where a panel sits. BOTTOM is the media tray under the timeline, OVERLAY is the inspector drawn over the
 * timeline; LEFT and RIGHT are side columns and need a wide enough window.
 */
enum class Dock {
    BOTTOM,
    OVERLAY,
    LEFT,
    RIGHT,
    ;

    val isSide: Boolean get() = this == LEFT || this == RIGHT
}

/** Docks a panel may use. */
fun Panel.allowedDocks(): List<Dock> = when (this) {
    Panel.TRAY -> listOf(Dock.BOTTOM, Dock.LEFT, Dock.RIGHT)
    Panel.INSPECTOR -> listOf(Dock.OVERLAY, Dock.LEFT, Dock.RIGHT)
}

/** The dock a panel falls back to when a side dock is not possible. */
fun Panel.fallbackDock(): Dock = when (this) {
    Panel.TRAY -> Dock.BOTTOM
    Panel.INSPECTOR -> Dock.OVERLAY
}

/** Lane heights of the timeline; [scale] multiplies the default 64 dp lane. */
enum class LaneHeight(val scale: Float, val label: String) {
    SMALL(0.75f, "Small"),
    MEDIUM(1.0f, "Medium"),
    LARGE(1.4f, "Large"),
}

enum class LayoutPreset(val label: String) {
    DEFAULT("Default"),
    TIMELINE_FOCUS("Timeline focus"),
    PREVIEW_FOCUS("Preview focus"),
    TWO_PANELS("Two panels"),
}

data class PanelState(val dock: Dock, val collapsed: Boolean = false)

/**
 * Everything the user can shape. [previewFraction] is the preview's share of the room that the preview and
 * the timeline split between; the side widths are in dp. Pure data: persisted per [LayoutKey] and changed
 * only through [reduce], which keeps it inside the limits of the window.
 */
data class LayoutState(
    val previewFraction: Float = DEFAULT_PREVIEW_FRACTION,
    val leftWidthDp: Float = DEFAULT_SIDE_WIDTH_DP,
    val rightWidthDp: Float = DEFAULT_SIDE_WIDTH_DP,
    val tray: PanelState = PanelState(Dock.BOTTOM),
    val inspector: PanelState = PanelState(Dock.OVERLAY),
    val laneHeight: LaneHeight = LaneHeight.MEDIUM,
    /** The preset last applied, null once something was changed by hand. */
    val preset: LayoutPreset? = LayoutPreset.DEFAULT,
    /** Shows the dividers and handles prominently so they are easy to find; not remembered. */
    val customising: Boolean = false,
) {
    fun panel(panel: Panel): PanelState = if (panel == Panel.TRAY) tray else inspector

    fun withPanel(panel: Panel, state: PanelState): LayoutState =
        if (panel == Panel.TRAY) copy(tray = state) else copy(inspector = state)

    /** Panels docked to [side], tray first. */
    fun panelsAt(side: Dock): List<Panel> = Panel.entries.filter { panel(it).dock == side }

    /** The same layout forced into what [window] allows: fractions and widths in range, side docks only if there is room. */
    fun clamped(window: WindowMetrics): LayoutState {
        val sideOk = window.sideDocksAllowed
        fun PanelState.fit(panel: Panel) = if (dock.isSide && !sideOk) copy(dock = panel.fallbackDock()) else this
        val maxSide = window.maxSideWidthDp
        return copy(
            previewFraction = previewFraction.coerceIn(MIN_PREVIEW_FRACTION, MAX_PREVIEW_FRACTION),
            leftWidthDp = leftWidthDp.coerceIn(WindowMetrics.MIN_SIDE_WIDTH_DP, maxSide),
            rightWidthDp = rightWidthDp.coerceIn(WindowMetrics.MIN_SIDE_WIDTH_DP, maxSide),
            tray = tray.fit(Panel.TRAY),
            inspector = inspector.fit(Panel.INSPECTOR),
        )
    }

    companion object {
        const val DEFAULT_PREVIEW_FRACTION = 0.4f
        const val MIN_PREVIEW_FRACTION = 0.15f
        const val MAX_PREVIEW_FRACTION = 0.75f
        const val DEFAULT_SIDE_WIDTH_DP = 320f

        /** What a fresh install shows: the tray beside the editor on wide windows, under it otherwise. */
        fun defaultFor(window: WindowMetrics): LayoutState {
            val wide = window.widthClass == WidthClass.EXPANDED
            return LayoutState(tray = PanelState(if (wide) Dock.LEFT else Dock.BOTTOM)).clamped(window)
        }

        fun preset(preset: LayoutPreset, window: WindowMetrics): LayoutState {
            val base = defaultFor(window)
            val state = when (preset) {
                LayoutPreset.DEFAULT -> base
                LayoutPreset.TIMELINE_FOCUS -> base.copy(
                    previewFraction = 0.25f,
                    laneHeight = LaneHeight.LARGE,
                    tray = base.tray.copy(collapsed = true),
                )
                LayoutPreset.PREVIEW_FOCUS -> base.copy(
                    previewFraction = 0.65f,
                    laneHeight = LaneHeight.SMALL,
                    tray = base.tray.copy(collapsed = true),
                )
                LayoutPreset.TWO_PANELS -> base.copy(
                    previewFraction = 0.5f,
                    tray = PanelState(Dock.LEFT),
                    inspector = PanelState(Dock.RIGHT),
                )
            }
            return state.copy(preset = preset).clamped(window)
        }
    }
}

enum class Side {
    LEFT,
    RIGHT,
    ;

    val dock: Dock get() = if (this == LEFT) Dock.LEFT else Dock.RIGHT
}

/**
 * The panels a side column shows now: the tray whenever it is docked there, the inspector only while it is
 * open. Empty means the column is not there at all.
 */
fun visiblePanels(state: LayoutState, side: Side, inspectorOpen: Boolean): List<Panel> =
    Panel.entries.filter { panel ->
        state.panel(panel).dock == side.dock && (panel != Panel.INSPECTOR || inspectorOpen)
    }

/** A side column is collapsed to its strip when every panel in it is. */
fun sideCollapsed(state: LayoutState, panels: List<Panel>): Boolean = panels.isNotEmpty() && panels.all { state.panel(it).collapsed }

/** Everything that can happen to the layout. */
sealed interface LayoutAction {
    /** A divider moved: the new preview share. */
    data class SetPreviewFraction(val fraction: Float) : LayoutAction

    data object ResetPreviewDivider : LayoutAction

    data class SetSideWidth(val side: Side, val widthDp: Float) : LayoutAction

    data class ResetSideDivider(val side: Side) : LayoutAction

    data class SetLaneHeight(val laneHeight: LaneHeight) : LayoutAction

    /** One step taller (+1) or shorter (-1) through the lane heights. */
    data class StepLaneHeight(val delta: Int) : LayoutAction

    data class ApplyPreset(val preset: LayoutPreset) : LayoutAction

    data class SetDock(val panel: Panel, val dock: Dock) : LayoutAction

    data class SetCollapsed(val panel: Panel, val collapsed: Boolean) : LayoutAction

    data class SetCustomising(val on: Boolean) : LayoutAction

    /** Back to the defaults of this window, and out of customise mode. */
    data object Reset : LayoutAction
}

/** The pure reducer: the next layout for [action] in [window]. Never leaves the limits of the window. */
fun LayoutState.reduce(action: LayoutAction, window: WindowMetrics): LayoutState {
    val manual = { next: LayoutState -> next.copy(preset = null).clamped(window) }
    return when (action) {
        is LayoutAction.SetPreviewFraction -> manual(copy(previewFraction = action.fraction))
        LayoutAction.ResetPreviewDivider -> manual(copy(previewFraction = LayoutState.DEFAULT_PREVIEW_FRACTION))
        is LayoutAction.SetSideWidth -> manual(
            if (action.side == Side.LEFT) copy(leftWidthDp = action.widthDp) else copy(rightWidthDp = action.widthDp),
        )
        is LayoutAction.ResetSideDivider -> manual(
            if (action.side == Side.LEFT) copy(leftWidthDp = LayoutState.DEFAULT_SIDE_WIDTH_DP)
            else copy(rightWidthDp = LayoutState.DEFAULT_SIDE_WIDTH_DP),
        )
        is LayoutAction.SetLaneHeight -> manual(copy(laneHeight = action.laneHeight))
        is LayoutAction.StepLaneHeight -> {
            val index = (laneHeight.ordinal + action.delta).coerceIn(0, LaneHeight.entries.lastIndex)
            manual(copy(laneHeight = LaneHeight.entries[index]))
        }
        is LayoutAction.ApplyPreset -> LayoutState.preset(action.preset, window).copy(customising = customising)
        is LayoutAction.SetDock -> {
            // A side dock the window cannot hold, or one the panel never uses, leaves the layout as it was.
            val allowed = action.panel.allowedDocks().filter { !it.isSide || window.sideDocksAllowed }
            if (action.dock !in allowed) this else manual(withPanel(action.panel, panel(action.panel).copy(dock = action.dock)))
        }
        is LayoutAction.SetCollapsed -> manual(withPanel(action.panel, panel(action.panel).copy(collapsed = action.collapsed)))
        is LayoutAction.SetCustomising -> copy(customising = action.on)
        LayoutAction.Reset -> LayoutState.defaultFor(window)
    }
}

/**
 * True when a divider moving from [previous] to [next] reaches [target] (within [tolerance]) from outside
 * it: the moment to give a haptic tick. Staying on the target does not tick again.
 */
fun crossesTarget(previous: Float, next: Float, target: Float, tolerance: Float): Boolean {
    val wasOn = kotlin.math.abs(previous - target) <= tolerance
    val isOn = kotlin.math.abs(next - target) <= tolerance
    return !wasOn && isOn
}

/**
 * Passes a stream of divider movements on at most once per [minIntervalMs]: the surfaces under the editor
 * resize each time the layout changes, so a drag is applied in coarser steps and flushed when it ends.
 */
class DragCoalescer(private val minIntervalMs: Long) {
    private var pending = 0f
    private var lastAppliedMs = Long.MIN_VALUE

    /** Adds [delta] and returns the total to apply now, or null if it is too soon. */
    fun offer(delta: Float, nowMs: Long): Float? {
        pending += delta
        if (lastAppliedMs != Long.MIN_VALUE && nowMs - lastAppliedMs < minIntervalMs) return null
        lastAppliedMs = nowMs
        return take()
    }

    /** What is left when the drag ends. */
    fun flush(): Float = take().also { lastAppliedMs = Long.MIN_VALUE }

    private fun take(): Float = pending.also { pending = 0f }
}
