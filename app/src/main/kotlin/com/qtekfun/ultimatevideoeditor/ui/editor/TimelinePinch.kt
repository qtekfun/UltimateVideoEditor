package com.qtekfun.ultimatevideoeditor.ui.editor

/**
 * What a two-finger pinch on the timeline does: it zooms the time axis, whichever way the fingers are spread. There is no
 * vertical zoom of the lanes (DECISIONS "No vertical zoom"): lane height is the layout sheet's Small / Medium / Large preset,
 * so this class has nothing to call but [zoomTime]. Pure, no Android types, so the rule is testable without a device.
 */
class TimelinePinch(private val zoomTime: (factor: Float, focusX: Float) -> Unit) {
    /** One step of the pinch: [scaleFactor] is the change of the finger span since the last step, [focusX] the midpoint. */
    fun onScale(scaleFactor: Float, focusX: Float) {
        if (!(scaleFactor > 0f) || scaleFactor.isInfinite()) return
        zoomTime(scaleFactor, focusX)
    }
}
