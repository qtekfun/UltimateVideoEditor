package com.qtekfun.ultimatevideoeditor.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.qtekfun.ultimatevideoeditor.engine.timeline.TimelineEngine
import com.qtekfun.ultimatevideoeditor.engine.timeline.TimelineHit
import com.qtekfun.ultimatevideoeditor.ui.preview.RecreateSurfaceAfterRotation

/**
 * Compose host for the native timeline canvas. The SurfaceView is created once; later snapshot,
 * zoom and scroll updates go straight to the engine and never recompose this view.
 */
@Composable
fun TimelineHost(
    engine: TimelineEngine,
    onTap: (TimelineHit) -> Unit,
    modifier: Modifier = Modifier,
    editing: TimelineEditing? = null,
    dropTarget: TimelineDropTarget? = null,
    selecting: TimelineSelecting? = null,
    shaping: TimelineShaping? = null,
    detailZoom: Boolean = false,
) {
    val currentOnTap = rememberUpdatedState(onTap)
    val currentEditing = rememberUpdatedState(editing)
    val currentDropTarget = rememberUpdatedState(dropTarget)
    val currentSelecting = rememberUpdatedState(selecting)
    val currentShaping = rememberUpdatedState(shaping)
    val view = remember { arrayOfNulls<TimelineSurfaceView>(1) }
    RecreateSurfaceAfterRotation { view[0] }
    AndroidView(
        modifier = modifier,
        update = { it.detailZoomOnRuler = detailZoom },
        factory = { context ->
            TimelineSurfaceView(context, engine, { currentOnTap.value(it) }, { currentEditing.value }, { currentDropTarget.value }, { currentSelecting.value }, { currentShaping.value }).also { view[0] = it }
        },
    )
}
