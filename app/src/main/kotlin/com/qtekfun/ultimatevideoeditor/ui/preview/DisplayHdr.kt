package com.qtekfun.ultimatevideoeditor.ui.preview

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.os.Build
import com.qtekfun.ultimatevideoeditor.engine.preview.OutputSpace

/**
 * The colour space the preview should ask for: HLG only when the project is HDR and the screen can
 * show HLG, otherwise SDR (HLG sources are then tone-mapped for the preview, and the export is
 * unaffected).
 */
internal fun wantedOutputSpace(projectIsHdr: Boolean, displaySupportsHlg: Boolean): OutputSpace =
    if (projectIsHdr && displaySupportsHlg) OutputSpace.HLG_2020 else OutputSpace.SDR_709

/** Reads what the screen the app is on can show. */
internal object DisplayHdr {
    /** `Display.HdrCapabilities.HDR_TYPE_HLG`, spelled out because that class is deprecated. */
    private const val HDR_TYPE_HLG = 3

    fun supportsHlg(context: Context): Boolean {
        // A context that is not tied to a screen cannot say; staying SDR is the safe answer.
        val display = try {
            context.display
        } catch (e: UnsupportedOperationException) {
            null
        } ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            display.mode.supportedHdrTypes.contains(HDR_TYPE_HLG)
        } else {
            // API 33 only exposes "has any HDR"; every HDR panel there accepts HLG.
            display.isHdr
        }
    }

    /**
     * Lets the window use HDR while an HLG preview is shown, and back to default otherwise. Without
     * it the system composes the surface in SDR even when it is tagged HLG.
     */
    fun setWindowHdr(context: Context, enabled: Boolean) {
        val activity = context.findActivity() ?: return
        activity.window.colorMode = if (enabled) ActivityInfo.COLOR_MODE_HDR else ActivityInfo.COLOR_MODE_DEFAULT
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
