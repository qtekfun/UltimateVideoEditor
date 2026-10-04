package com.ultimatevideo.uveditor.ui.editor

/**
 * The eyedropper of the HSL qualifier: arm it on a qualifier effect, tap the picture on the preview, and the key
 * (hue centre and width, saturation range, luma range) is set from the colour under the finger as one undo step.
 * The colour is read from the clip's own picture at the playhead (before any effect), with the tap mapped through the
 * clip's position, scale and rotation, like the motion tracking pick.
 */
sealed interface QualifierIntent : EditorIntent {
    /** Wait for a tap on the preview to key effect [effectId] of the selected clip. */
    data class Arm(val effectId: String) : QualifierIntent

    /** Stop waiting for a tap. */
    data object Cancel : QualifierIntent

    /** The preview was tapped at ([x], [y]) in canvas pixels from the centre of the canvas. */
    data class Pick(val x: Double, val y: Double) : QualifierIntent
}

/** The eyedropper's state: [effectId] is set while the preview waits for a tap, [busy] while the colour is being read. */
data class QualifierPickState(val effectId: String? = null, val busy: Boolean = false) {
    val armed: Boolean get() = effectId != null
}
