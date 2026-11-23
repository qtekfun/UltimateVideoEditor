package com.qtekfun.ultimatevideoeditor.ui.editor.tray

import kotlin.math.min

/**
 * How tall the bottom tray's body is, as a pure function of the window, so the picture and the timeline above it always
 * keep a minimum and a short, narrow or enlarged-text window never gets a tray bigger than the room it has.
 * All sizes are dp; `fontScale` is the system font scale. A body of 0 means "no room": the tray shows its header only.
 */
internal object TraySizing {
    /** What the editor's own rows (top bar, transport, tools) are given at font scale 1, plus the status bar. */
    const val CHROME_DP = 150f
    const val MIN_PREVIEW_DP = 120f
    const val MIN_TIMELINE_DP = 140f
    const val HEADER_DP = 48f

    /** A body shorter than this shows too little to use, so it is not shown at all. */
    const val MIN_BODY_DP = 96f

    /** The share of a header or control block that may scroll before the list below it is squeezed out. */
    const val CONTROLS_MAX_FRACTION = 0.5f

    private const val HALF_FRACTION = 0.32f
    private const val HALF_MIN_DP = 160f
    private const val HALF_MAX_DP = 280f
    private const val FULL_FRACTION = 0.55f
    private const val FULL_MIN_DP = 240f
    private const val FULL_MAX_DP = 520f
    private const val TILE_MIN_DP = 96f
    private const val TILE_MAX_SCALE = 1.6f
    private const val SCALE_CAP = 2f

    private fun scale(fontScale: Float): Float = fontScale.coerceIn(1f, SCALE_CAP)

    /** The room the tray's body can take: the window minus the editor rows, the minimum picture and timeline, and the tray header. */
    fun availableBodyDp(windowHeightDp: Float, fontScale: Float): Float =
        windowHeightDp - CHROME_DP * scale(fontScale) - MIN_PREVIEW_DP - MIN_TIMELINE_DP - HEADER_DP * scale(fontScale)

    /** The height of the body at [snap]; 0 when collapsed or when the window cannot spare [MIN_BODY_DP]. */
    fun bodyHeightDp(windowHeightDp: Float, fontScale: Float, snap: TrayHeight): Float {
        val available = availableBodyDp(windowHeightDp, fontScale)
        if (snap == TrayHeight.COLLAPSED || available < MIN_BODY_DP) return 0f
        val wanted = when (snap) {
            TrayHeight.HALF -> (windowHeightDp * HALF_FRACTION).coerceIn(HALF_MIN_DP, HALF_MAX_DP)
            else -> (windowHeightDp * FULL_FRACTION).coerceIn(FULL_MIN_DP, FULL_MAX_DP)
        }
        return min(wanted, available)
    }

    /** Whether the bottom tray can be opened at all in this window. */
    fun canExpand(windowHeightDp: Float, fontScale: Float): Boolean =
        bodyHeightDp(windowHeightDp, fontScale, TrayHeight.FULL) > 0f

    /** The narrowest tile of the grid: wider with larger text (its badges are text), never wider than the panel. */
    fun tileMinWidthDp(panelWidthDp: Float, fontScale: Float): Float =
        min(TILE_MIN_DP * fontScale.coerceIn(1f, TILE_MAX_SCALE), panelWidthDp.coerceAtLeast(1f))
}
