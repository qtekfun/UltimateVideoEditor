package com.qtekfun.ultimatevideoeditor.ui.editor.layout

import com.qtekfun.ultimatevideoeditor.R
import androidx.annotation.StringRes

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

/**
 * How a foldable is held. [NONE] is any window that is not split by a half-open fold (a phone, a tablet, a fully open foldable).
 * [TABLETOP] has a horizontal hinge: the upper half is the picture, the lower half the controls. [BOOK] has a vertical hinge:
 * one half on each side of it.
 */
enum class FoldPosture {
    NONE,
    TABLETOP,
    BOOK,
    ;

    companion object {
        /** The posture for a half-open fold whose hinge runs [horizontal]ly or not; a fold that is not half open is [NONE]. */
        fun of(halfOpen: Boolean, horizontal: Boolean): FoldPosture = when {
            !halfOpen -> NONE
            horizontal -> TABLETOP
            else -> BOOK
        }
    }
}

/**
 * How the preview, the timeline and the side panels share the window.
 *
 * - [STACKED]: the preview above the timeline, side panels in full-height columns (the original layout; best on a phone).
 * - [WORKBENCH]: a band on top holding the side panels and the preview, the timeline under it across the whole width.
 * - [SIDE_BY_SIDE]: the preview in a column of its own, the controls and the timeline beside it (a wide, short window).
 */
enum class Workspace(@StringRes val labelRes: Int, @StringRes val descriptionRes: Int) {
    STACKED(R.string.ed_ws_stacked, R.string.ed_ws_stacked_desc),
    WORKBENCH(R.string.ed_ws_workbench, R.string.ed_ws_workbench_desc),
    SIDE_BY_SIDE(R.string.ed_ws_side_by_side, R.string.ed_ws_side_by_side_desc),
}

/** The window the editor is laid out in. */
data class WindowMetrics(val widthDp: Float, val heightDp: Float, val posture: FoldPosture = FoldPosture.NONE) {
    val widthClass: WidthClass get() = WidthClass.of(widthDp)
    val landscape: Boolean get() = widthDp > heightDp

    /** Whether [workspace] has the room it needs here; [Workspace.STACKED] always does. */
    fun allows(workspace: Workspace): Boolean = when (workspace) {
        Workspace.STACKED -> true
        // The band needs the width for a panel beside a picture and the height for a timeline under it. A tall narrow window
        // (a tablet upright) has the room below, not beside, so it stays stacked with the tray at the bottom.
        Workspace.WORKBENCH -> widthDp >= WORKBENCH_MIN_WIDTH_DP && heightDp >= WORKBENCH_MIN_HEIGHT_DP && widthDp >= heightDp * WORKBENCH_MIN_ASPECT
        // A column for the picture and one for the timeline: only a window that is wider than it is tall.
        Workspace.SIDE_BY_SIDE -> widthDp >= SIDE_DOCK_MIN_WIDTH_DP && landscape
    }

    /** What a layout that was never saved uses here. A half-open fold decides first, then the shape of the window. */
    val defaultWorkspace: Workspace
        get() = when {
            posture == FoldPosture.TABLETOP -> Workspace.STACKED
            posture == FoldPosture.BOOK && allows(Workspace.SIDE_BY_SIDE) -> Workspace.SIDE_BY_SIDE
            allows(Workspace.WORKBENCH) -> Workspace.WORKBENCH
            allows(Workspace.SIDE_BY_SIDE) -> Workspace.SIDE_BY_SIDE
            else -> Workspace.STACKED
        }

    /** Side docks need room: below this width everything stays at the bottom or over the timeline. */
    val sideDocksAllowed: Boolean get() = widthDp >= SIDE_DOCK_MIN_WIDTH_DP

    /** The widest a side panel may be, so the editor itself always keeps most of the window. */
    val maxSideWidthDp: Float get() = minOf(MAX_SIDE_WIDTH_DP, widthDp * MAX_SIDE_SHARE).coerceAtLeast(MIN_SIDE_WIDTH_DP)

    companion object {
        const val SIDE_DOCK_MIN_WIDTH_DP = 600f
        const val WORKBENCH_MIN_WIDTH_DP = 720f
        const val WORKBENCH_MIN_HEIGHT_DP = 480f
        const val WORKBENCH_MIN_ASPECT = 0.9f
        const val MIN_SIDE_WIDTH_DP = 200f
        const val MAX_SIDE_WIDTH_DP = 520f
        const val MAX_SIDE_SHARE = 0.45f
    }
}

/**
 * Layouts are remembered per window size class, orientation and fold posture (a phone upright and sideways differ, and so
 * does a foldable standing half open). A window with no half-open fold keeps the ids it always had.
 */
data class LayoutKey(val widthClass: WidthClass, val landscape: Boolean, val posture: FoldPosture = FoldPosture.NONE) {
    val id: String
        get() = "${widthClass.name.lowercase()}-${if (landscape) "landscape" else "portrait"}" +
            if (posture == FoldPosture.NONE) "" else "-${posture.name.lowercase()}"

    companion object {
        fun of(window: WindowMetrics) = LayoutKey(window.widthClass, window.landscape, window.posture)
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
enum class LaneHeight(val scale: Float, @StringRes val labelRes: Int) {
    SMALL(0.75f, R.string.ed_2a_lane_small),
    MEDIUM(1.0f, R.string.ed_2a_lane_medium),
    LARGE(1.4f, R.string.ed_2a_lane_large),
}

/**
 * How tall audio lanes are next to the other lanes: [factor] multiplies the lane height of the [LaneHeight] preset, so the
 * waveform and the fade / volume handles have more room to see and grab. [SAME] keeps every lane one height.
 */
enum class AudioLaneHeight(val factor: Float, @StringRes val labelRes: Int) {
    SAME(1.0f, R.string.ed_2a_audio_same),
    TALL(1.5f, R.string.ed_2a_audio_1_5x),
    TALLER(2.0f, R.string.ed_2a_audio_2x),
    ;

    companion object {
        /** What a layout that was never saved gets: tall enough for the waveform and the fade / volume handles. */
        val DEFAULT = TALL
    }
}

/** [labelRes] is the short name on the chip, [descriptionRes] the full name for the spoken description. */
enum class LayoutPreset(@StringRes val labelRes: Int, @StringRes val descriptionRes: Int) {
    DEFAULT(R.string.ed_2a_preset_default, R.string.ed_2b_preset_default_desc),
    TIMELINE_FOCUS(R.string.ed_2a_preset_timeline_focus, R.string.ed_2b_preset_timeline_desc),
    PREVIEW_FOCUS(R.string.ed_2a_preset_preview_focus, R.string.ed_2b_preset_preview_desc),
    TWO_PANELS(R.string.ed_2a_preset_two_panels, R.string.ed_2b_preset_two_desc),
}

data class PanelState(val dock: Dock, val collapsed: Boolean = false)

/**
 * Everything the user can shape. [previewFraction] is the preview's share of the room that the preview and
 * the timeline split between; the side widths are in dp. Pure data: persisted per [LayoutKey] and changed
 * only through [reduce], which keeps it inside the limits of the window.
 */
data class LayoutState(
    /** How the preview, the timeline and the panels share the window; see [Workspace]. */
    val workspace: Workspace = Workspace.STACKED,
    /** The preview's share of the height that the preview and the timeline split ([Workspace.STACKED], [Workspace.WORKBENCH]). */
    val previewFraction: Float = DEFAULT_PREVIEW_FRACTION,
    /** The preview's share of the width of the editor ([Workspace.SIDE_BY_SIDE]). */
    val previewWidthFraction: Float = DEFAULT_PREVIEW_WIDTH_FRACTION,
    val leftWidthDp: Float = DEFAULT_SIDE_WIDTH_DP,
    val rightWidthDp: Float = DEFAULT_SIDE_WIDTH_DP,
    val tray: PanelState = PanelState(Dock.BOTTOM),
    val inspector: PanelState = PanelState(Dock.OVERLAY),
    val laneHeight: LaneHeight = LaneHeight.MEDIUM,
    /**
     * Audio lanes relative to [laneHeight]; saved with it. A layout never saved starts at [AudioLaneHeight.DEFAULT] (1.5x);
     * a saved layout from before the setting existed reads as Same (see [LayoutCodec.decode]), so nobody's timeline changes.
     */
    val audioLaneHeight: AudioLaneHeight = AudioLaneHeight.DEFAULT,
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
        val fits = window.allows(workspace)
        return copy(
            // A workspace the window has no room for goes back to the stacked layout, with that layout's own divider.
            workspace = if (fits) workspace else Workspace.STACKED,
            previewFraction = (if (fits) previewFraction else DEFAULT_PREVIEW_FRACTION).coerceIn(MIN_PREVIEW_FRACTION, MAX_PREVIEW_FRACTION),
            previewWidthFraction = previewWidthFraction.coerceIn(MIN_PREVIEW_WIDTH_FRACTION, MAX_PREVIEW_WIDTH_FRACTION),
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
        const val DEFAULT_PREVIEW_WIDTH_FRACTION = 0.4f
        const val MIN_PREVIEW_WIDTH_FRACTION = 0.25f
        const val MAX_PREVIEW_WIDTH_FRACTION = 0.7f

        /** The height share the preview gets in the band of [Workspace.WORKBENCH]: the picture and the panels beside it. */
        const val WORKBENCH_PREVIEW_FRACTION = 0.5f

        /** The preview's height share when [workspace] is first chosen; [Workspace.SIDE_BY_SIDE] does not use it. */
        fun defaultPreviewFraction(workspace: Workspace): Float =
            if (workspace == Workspace.WORKBENCH) WORKBENCH_PREVIEW_FRACTION else DEFAULT_PREVIEW_FRACTION

        /**
         * What a layout that was never saved shows. A wide, tall window gets the workbench (the tray beside the picture); a wide,
         * short one puts the picture beside the timeline with the tray folded away, because height is what it lacks; a half-open
         * foldable splits at its hinge. A phone upright keeps the stacked layout with the tray under it.
         */
        fun defaultFor(window: WindowMetrics): LayoutState {
            val workspace = window.defaultWorkspace
            val tray = when (workspace) {
                Workspace.WORKBENCH -> PanelState(Dock.LEFT)
                Workspace.SIDE_BY_SIDE -> PanelState(Dock.BOTTOM, collapsed = true)
                Workspace.STACKED -> PanelState(if (window.widthClass == WidthClass.EXPANDED && window.posture == FoldPosture.NONE) Dock.LEFT else Dock.BOTTOM)
            }
            return LayoutState(
                workspace = workspace,
                previewFraction = defaultPreviewFraction(workspace),
                // A book fold splits the window in two equal halves; the hinge is where the divider belongs.
                previewWidthFraction = if (window.posture == FoldPosture.BOOK) 0.5f else DEFAULT_PREVIEW_WIDTH_FRACTION,
                tray = tray,
            ).let { if (window.posture == FoldPosture.TABLETOP) it.copy(previewFraction = TABLETOP_PREVIEW_FRACTION) else it }.clamped(window)
        }

        /** Stacked with the hinge across the middle: the picture fills the upper half, the controls the lower. */
        const val TABLETOP_PREVIEW_FRACTION = 0.5f

        fun preset(preset: LayoutPreset, window: WindowMetrics): LayoutState {
            // A preset shapes the panels and the divider; it keeps the workspace the window would pick (the two-panel one chooses its own).
            val base = defaultFor(window)
            val state = when (preset) {
                LayoutPreset.DEFAULT -> base
                LayoutPreset.TIMELINE_FOCUS -> base.copy(
                    // The band of the workbench holds the panels too, so it never gets as short as a stacked preview.
                    previewFraction = if (base.workspace == Workspace.WORKBENCH) 0.35f else 0.25f,
                    previewWidthFraction = 0.3f,
                    laneHeight = LaneHeight.LARGE,
                    tray = base.tray.copy(collapsed = true),
                )
                LayoutPreset.PREVIEW_FOCUS -> base.copy(
                    previewFraction = 0.65f,
                    previewWidthFraction = 0.6f,
                    laneHeight = LaneHeight.SMALL,
                    tray = base.tray.copy(collapsed = true),
                )
                LayoutPreset.TWO_PANELS -> {
                    // Two side columns need the band to sit in, so it asks for the workbench wherever there is room for it.
                    val workspace = if (window.allows(Workspace.WORKBENCH)) Workspace.WORKBENCH else base.workspace
                    base.copy(
                        workspace = workspace,
                        previewFraction = if (workspace == Workspace.WORKBENCH) WORKBENCH_PREVIEW_FRACTION else 0.5f,
                        tray = PanelState(Dock.LEFT),
                        inspector = PanelState(Dock.RIGHT),
                    )
                }
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

/**
 * Whether the tray docked at the bottom is drawn. While the inspector is open as an overlay it covers the
 * timeline pane, which the tray would squeeze to a sliver on a phone, so the tray gives way until the
 * inspector closes (its state, tab and search are kept). Side-docked panels never compete for that height.
 */
fun bottomTrayShown(state: LayoutState, inspectorOpen: Boolean): Boolean =
    state.tray.dock == Dock.BOTTOM && !(inspectorOpen && state.inspector.dock == Dock.OVERLAY)

/** A side column is collapsed to its strip when every panel in it is. */
fun sideCollapsed(state: LayoutState, panels: List<Panel>): Boolean = panels.isNotEmpty() && panels.all { state.panel(it).collapsed }

/** Everything that can happen to the layout. */
sealed interface LayoutAction {
    /** A divider moved: the new preview share. */
    data class SetPreviewFraction(val fraction: Float) : LayoutAction

    data object ResetPreviewDivider : LayoutAction

    /** The divider between the preview and the timeline in [Workspace.SIDE_BY_SIDE]: the preview's new share of the width. */
    data class SetPreviewWidthFraction(val fraction: Float) : LayoutAction

    data object ResetPreviewWidthDivider : LayoutAction

    data class SetWorkspace(val workspace: Workspace) : LayoutAction

    data class SetSideWidth(val side: Side, val widthDp: Float) : LayoutAction

    data class ResetSideDivider(val side: Side) : LayoutAction

    data class SetLaneHeight(val laneHeight: LaneHeight) : LayoutAction

    /** One step taller (+1) or shorter (-1) through the lane heights. */
    data class StepLaneHeight(val delta: Int) : LayoutAction

    data class SetAudioLaneHeight(val audioLaneHeight: AudioLaneHeight) : LayoutAction

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
        LayoutAction.ResetPreviewDivider -> manual(copy(previewFraction = LayoutState.defaultPreviewFraction(workspace)))
        is LayoutAction.SetPreviewWidthFraction -> manual(copy(previewWidthFraction = action.fraction))
        LayoutAction.ResetPreviewWidthDivider -> manual(copy(previewWidthFraction = LayoutState.DEFAULT_PREVIEW_WIDTH_FRACTION))
        is LayoutAction.SetWorkspace ->
            // One the window has no room for leaves the layout as it was. Each workspace starts with its own divider.
            if (!window.allows(action.workspace) || action.workspace == workspace) this
            else manual(copy(workspace = action.workspace, previewFraction = LayoutState.defaultPreviewFraction(action.workspace)))
        is LayoutAction.SetSideWidth -> manual(
            if (action.side == Side.LEFT) copy(leftWidthDp = action.widthDp) else copy(rightWidthDp = action.widthDp),
        )
        is LayoutAction.ResetSideDivider -> manual(
            if (action.side == Side.LEFT) copy(leftWidthDp = LayoutState.DEFAULT_SIDE_WIDTH_DP)
            else copy(rightWidthDp = LayoutState.DEFAULT_SIDE_WIDTH_DP),
        )
        is LayoutAction.SetLaneHeight -> manual(copy(laneHeight = action.laneHeight))
        is LayoutAction.SetAudioLaneHeight -> manual(copy(audioLaneHeight = action.audioLaneHeight))
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
