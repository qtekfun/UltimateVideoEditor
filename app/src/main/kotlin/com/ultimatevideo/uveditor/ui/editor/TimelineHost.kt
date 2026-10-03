package com.ultimatevideo.uveditor.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.ultimatevideo.uveditor.engine.timeline.TimelineEngine
import com.ultimatevideo.uveditor.engine.timeline.TimelineHit

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
) {
    val currentOnTap = rememberUpdatedState(onTap)
    val currentEditing = rememberUpdatedState(editing)
    val currentDropTarget = rememberUpdatedState(dropTarget)
    AndroidView(
        modifier = modifier,
        factory = { context ->
            TimelineSurfaceView(context, engine, { currentOnTap.value(it) }, { currentEditing.value }, { currentDropTarget.value })
        },
    )
}
