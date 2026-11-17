package com.qtekfun.ultimatevideoeditor.ui.editor.layout

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import kotlinx.coroutines.flow.map

/** The activity behind a context, or null when there is none (a preview, a test). */
private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/**
 * How the device is folded right now, from the platform's window layout (local, no network). A device with no fold, or a fold
 * that is open flat or closed, is [FoldPosture.NONE]; the editor then lays itself out by window size alone.
 */
@Composable
internal fun rememberFoldPosture(): State<FoldPosture> {
    val context = LocalContext.current
    val postures = remember(context) {
        val activity = context.activity()
        if (activity == null) {
            kotlinx.coroutines.flow.flowOf(FoldPosture.NONE)
        } else {
            WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity).map { info ->
                val fold = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
                if (fold == null) {
                    FoldPosture.NONE
                } else {
                    FoldPosture.of(
                        halfOpen = fold.state == FoldingFeature.State.HALF_OPENED,
                        horizontal = fold.orientation == FoldingFeature.Orientation.HORIZONTAL,
                    )
                }
            }
        }
    }
    return postures.collectAsState(initial = FoldPosture.NONE)
}
