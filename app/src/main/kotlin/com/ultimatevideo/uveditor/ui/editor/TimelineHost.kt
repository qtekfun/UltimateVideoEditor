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
) {
    val currentOnTap = rememberUpdatedState(onTap)
    AndroidView(
        modifier = modifier,
        factory = { context -> TimelineSurfaceView(context, engine) { currentOnTap.value(it) } },
    )
}
