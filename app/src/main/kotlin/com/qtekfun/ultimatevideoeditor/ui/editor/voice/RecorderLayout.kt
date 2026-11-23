package com.qtekfun.ultimatevideoeditor.ui.editor.voice

/**
 * How the recorder panel arranges its action buttons, as plain arithmetic so it is tested without a screen. The buttons share a row
 * while each still has room for its label; with a narrow window or a large font they wrap onto more rows (down to one per row), so
 * nothing is clipped and every button keeps a 48 dp touch target.
 */
internal object RecorderLayout {
    /** The least width one button needs at normal font size, in dp: its padding plus a short label. */
    private const val CELL_BASE_DP = 60f

    /** What each unit of font scale adds to that width, in dp. */
    private const val CELL_PER_SCALE_DP = 34f

    /** The height every button has at least, in dp. */
    const val MIN_TOUCH_DP = 48f

    /** How many buttons fit side by side in [availableDp] at [fontScale]; at least 1, at most [buttons]. */
    fun columns(availableDp: Float, fontScale: Float, buttons: Int): Int {
        if (buttons <= 1) return 1
        val cell = CELL_BASE_DP + CELL_PER_SCALE_DP * fontScale.coerceAtLeast(1f)
        return (availableDp / cell).toInt().coerceIn(1, buttons)
    }

    /** [items] cut into rows of at most [columns], in order. */
    fun <T> rows(items: List<T>, columns: Int): List<List<T>> = items.chunked(columns.coerceAtLeast(1))
}
