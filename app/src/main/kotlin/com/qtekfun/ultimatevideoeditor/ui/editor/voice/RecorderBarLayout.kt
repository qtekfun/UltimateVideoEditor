package com.qtekfun.ultimatevideoeditor.ui.editor.voice

import kotlin.math.sqrt

/**
 * How the recorder bar is arranged, as plain arithmetic so it is tested without a screen. In a wide bar the time, the level and the
 * three actions share one row; in a narrow window or with a large font the time and the level keep the first row and the actions
 * take a row of their own, so nothing is clipped and every action keeps a 48 dp touch target.
 */
internal object RecorderBarLayout {
    /** The height of the round part of an action, which is also its least touch size, in dp. */
    const val ACTION_DP = 48f

    private const val TIMER_BASE_DP = 64f
    private const val METER_MIN_DP = 48f
    private const val CELL_BASE_DP = 56f
    private const val CELL_PER_SCALE_DP = 20f
    private const val GAP_DP = 8f
    private const val ACTIONS = 3

    /** The width, in dp, that one row of time, level and [ACTIONS] actions needs at [fontScale]. */
    fun wideNeededDp(fontScale: Float): Float {
        val scale = fontScale.coerceIn(1f, 3f)
        val cell = CELL_BASE_DP + CELL_PER_SCALE_DP * (scale - 1f)
        return TIMER_BASE_DP * scale + METER_MIN_DP + ACTIONS * cell + GAP_DP * ACTIONS
    }

    /** True when everything fits one row in [availableDp] at [fontScale]. */
    fun isWide(availableDp: Float, fontScale: Float): Boolean = availableDp >= wideNeededDp(fontScale)
}

/** The recent sound levels the bar draws as bars, newest last. */
internal object LevelHistory {
    /** How many bars the meter shows. */
    const val SLOTS = 40

    /** [history] with [level] (0..1) added and only the last [slots] kept. A level that is not a number counts as silence. */
    fun push(history: List<Float>, level: Float, slots: Int = SLOTS): List<Float> {
        val next = history + clamp(level)
        return if (next.size > slots) next.takeLast(slots) else next
    }

    /** The share of the meter's height a [level] fills: a square root, so speech at a normal volume is clearly visible. */
    fun fill(level: Float): Float = sqrt(clamp(level))

    private fun clamp(level: Float): Float = if (level.isNaN()) 0f else level.coerceIn(0f, 1f)
}
