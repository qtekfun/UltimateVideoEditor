package com.qtekfun.ultimatevideoeditor.ui.editor.layout

import kotlin.math.roundToInt

/** Widths, in px, of the two side columns, their dividers and what is left between them. */
internal data class ColumnsPx(val left: Int, val right: Int, val leftHandle: Int, val rightHandle: Int, val main: Int)

/**
 * Shares [width] between two side columns and the content between them. A column asks for [leftWanted] / [rightWanted] px
 * (0 when it is absent; a collapsed one asks for its strip) and has a divider only while it is present and open. Whatever the
 * saved widths say, the content keeps at least 30 % of the window; open columns shrink together to make that so.
 * With [fullscreen] the columns keep their place in the tree but get no room.
 */
internal fun resolveColumns(
    width: Int,
    leftWanted: Int,
    rightWanted: Int,
    leftOpen: Boolean,
    rightOpen: Boolean,
    handlePx: Int,
    fullscreen: Boolean,
): ColumnsPx {
    var left = if (fullscreen) 0 else leftWanted
    var right = if (fullscreen) 0 else rightWanted
    val leftHandle = if (!fullscreen && leftOpen) handlePx else 0
    val rightHandle = if (!fullscreen && rightOpen) handlePx else 0
    val room = (width * 0.7f).roundToInt() - leftHandle - rightHandle
    if (left + right > room && left + right > 0) {
        val scale = room.toFloat() / (left + right)
        if (leftOpen) left = (left * scale).roundToInt()
        if (rightOpen) right = (right * scale).roundToInt()
    }
    return ColumnsPx(left, right, leftHandle, rightHandle, (width - left - right - leftHandle - rightHandle).coerceAtLeast(0))
}

/** The preview's width and the width left for the controls and the timeline in [Workspace.SIDE_BY_SIDE]. */
internal data class SidePx(val preview: Int, val rest: Int)

/** Splits [width] around a divider of [handlePx]: the preview takes [fraction] of what the divider leaves. */
internal fun resolveSideBySide(width: Int, handlePx: Int, fraction: Float): SidePx {
    val room = (width - handlePx).coerceAtLeast(0)
    val preview = (room * fraction).roundToInt().coerceIn(0, room)
    return SidePx(preview, room - preview)
}
