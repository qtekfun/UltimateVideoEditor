package com.ultimatevideo.uveditor.ui.editor

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize

/**
 * Transparent touch layer over the preview: drag to move, pinch to scale, twist to rotate the
 * selected clip. Positions are reported in project canvas pixels (the canvas is letterboxed into
 * this view the same way the native compositor does it), so the edit does not depend on the
 * phone's screen size. While [enabled] is false touches pass through untouched.
 *
 * [onStep] is called for each movement; [onEnd] when the last finger lifts, but only after at
 * least one step, so a plain tap does not create an edit.
 */
@Composable
fun PreviewGestureLayer(
    enabled: Boolean,
    canvasWidth: Int,
    canvasHeight: Int,
    onStep: (panX: Double, panY: Double, zoom: Double, rotationDegrees: Double) -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val currentOnStep by rememberUpdatedState(onStep)
    val currentOnEnd by rememberUpdatedState(onEnd)
    Box(
        modifier = modifier
            .onSizeChanged { size = it }
            .pointerInput(enabled, canvasWidth, canvasHeight, size) {
                if (!enabled) return@pointerInput
                val rect = PreviewGeometry.canvasRect(canvasWidth, canvasHeight, size.width.toFloat(), size.height.toFloat())
                val viewScale = PreviewGeometry.viewScale(rect, canvasWidth)
                if (viewScale <= 0f) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var moved = false
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        if (event.changes.any { it.positionChanged() }) {
                            val pan = event.calculatePan()
                            moved = true
                            currentOnStep(
                                (pan.x / viewScale).toDouble(),
                                (pan.y / viewScale).toDouble(),
                                event.calculateZoom().toDouble(),
                                event.calculateRotation().toDouble(),
                            )
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    if (moved) currentOnEnd()
                }
            },
    )
}
