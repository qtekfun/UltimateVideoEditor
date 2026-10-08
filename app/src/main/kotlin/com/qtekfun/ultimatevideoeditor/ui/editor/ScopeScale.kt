package com.qtekfun.ultimatevideoeditor.ui.editor

import com.qtekfun.ultimatevideoeditor.domain.ProjectColorSpace
import com.qtekfun.ultimatevideoeditor.engine.preview.ScopeMode

/**
 * The scale labels drawn over a scope. The native renderer draws the graticule lines (quarters of the
 * height or width); the labels depend on the project colour space, so the app draws them as text.
 */
internal object ScopeScale {
    /** Labels from the top line to the bottom line of a waveform or parade, as percent of the signal. */
    fun verticalLabels(mode: ScopeMode, space: ProjectColorSpace): List<String> = when (mode) {
        ScopeMode.WAVEFORM, ScopeMode.PARADE ->
            if (space.isHdr) listOf("100 · 1000 nit", "75 · 203 nit", "50", "25", "0") else listOf("100", "75", "50", "25", "0")
        ScopeMode.VECTORSCOPE, ScopeMode.HISTOGRAM -> emptyList()
    }

    /** Labels from the left line to the right line of a histogram, as percent of the signal range. */
    fun horizontalLabels(mode: ScopeMode): List<String> =
        if (mode == ScopeMode.HISTOGRAM) listOf("0", "25", "50", "75", "100") else emptyList()

    /** The short line under the mode name that says what the scope shows in this project. */
    fun caption(mode: ScopeMode, space: ProjectColorSpace): String {
        val signal = if (space.isHdr) "HLG signal" else "Rec.709 signal"
        return when (mode) {
            ScopeMode.WAVEFORM -> "Luma by column, $signal %"
            ScopeMode.PARADE -> "Red, green, blue side by side, $signal %"
            ScopeMode.VECTORSCOPE -> "Chroma: blue right, red up. Ring is full saturation; the line is skin tone"
            ScopeMode.HISTOGRAM -> "Samples per level: red, green, blue and luma (white line)"
        }
    }
}
