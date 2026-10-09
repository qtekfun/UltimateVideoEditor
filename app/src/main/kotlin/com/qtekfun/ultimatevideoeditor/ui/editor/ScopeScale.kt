package com.qtekfun.ultimatevideoeditor.ui.editor

import com.qtekfun.ultimatevideoeditor.ui.text.UiText
import com.qtekfun.ultimatevideoeditor.R
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
            if (space.isHdr) listOf("100 · 1000 nit", "75 · 203 nit", "50", "25", "0") // i18n-ok: a unit symbol else listOf("100", "75", "50", "25", "0")
        ScopeMode.VECTORSCOPE, ScopeMode.HISTOGRAM -> emptyList()
    }

    /** Labels from the left line to the right line of a histogram, as percent of the signal range. */
    fun horizontalLabels(mode: ScopeMode): List<String> =
        if (mode == ScopeMode.HISTOGRAM) listOf("0", "25", "50", "75", "100") else emptyList()

    /** The short line under the mode name that says what the scope shows in this project. */
    fun caption(mode: ScopeMode, space: ProjectColorSpace): UiText {
        val signal = UiText.res(if (space.isHdr) R.string.ed_s3_signal_hlg else R.string.ed_s3_signal_rec709)
        return when (mode) {
            ScopeMode.WAVEFORM -> UiText.res(R.string.ed_s3_cap_waveform, signal)
            ScopeMode.PARADE -> UiText.res(R.string.ed_s3_cap_parade, signal)
            ScopeMode.VECTORSCOPE -> UiText.res(R.string.ed_s3_cap_vectorscope)
            ScopeMode.HISTOGRAM -> UiText.res(R.string.ed_s3_cap_histogram)
        }
    }
}
