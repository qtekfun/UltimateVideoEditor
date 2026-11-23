package com.qtekfun.ultimatevideoeditor.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.width
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/**
 * Sizes for sheets, dialogs and fixed-width labels as pure functions of the window and the font scale, so a short landscape
 * window, a narrow one or enlarged text never cuts content off. All values are dp.
 */
internal object SheetSizing {
    /** What a dialog spends on its title, its buttons and the system's margins at font scale 1. */
    const val DIALOG_CHROME_DP = 180f
    const val MIN_LIST_DP = 96f
    private const val LABEL_MAX_SCALE = 1.6f
    private const val SCALE_CAP = 2f

    /**
     * The tallest a list inside a dialog or sheet may be: [preferredDp] when the window has the room, otherwise what is left
     * after the title and buttons, but never below [MIN_LIST_DP] (the rest of the content scrolls around it).
     */
    fun listMaxHeightDp(windowHeightDp: Float, fontScale: Float, preferredDp: Float): Float {
        val room = windowHeightDp - DIALOG_CHROME_DP * fontScale.coerceIn(1f, SCALE_CAP)
        return min(preferredDp, max(room, MIN_LIST_DP))
    }

    /** The width of a fixed label column: it grows with the text so a label keeps its line, within [LABEL_MAX_SCALE]. */
    fun labelWidthDp(baseDp: Float, fontScale: Float): Float = baseDp * fontScale.coerceIn(1f, LABEL_MAX_SCALE)

    /** The tallest a card floating over the timeline may be: most of a short window, so its own controls scroll instead of leaving. */
    fun popupMaxHeightDp(windowHeightDp: Float): Float = (windowHeightDp * 0.6f).coerceAtLeast(120f)

    /** The tallest a picture in a sheet may be: a share of the window, so the controls under it stay reachable. */
    fun pictureMaxHeightDp(windowHeightDp: Float): Float = (windowHeightDp * 0.4f).coerceIn(96f, 360f)
}

/** A fixed-width label column that grows with the font scale. */
@Composable
internal fun Modifier.labelWidth(base: Dp): Modifier = width(SheetSizing.labelWidthDp(base.value, LocalDensity.current.fontScale).dp)

/** The room for a list in a dialog or sheet in the current window; see [SheetSizing.listMaxHeightDp]. */
@Composable
internal fun rememberListMaxHeight(preferred: Dp): Dp =
    SheetSizing.listMaxHeightDp(LocalConfiguration.current.screenHeightDp.toFloat(), LocalDensity.current.fontScale, preferred.value).dp
