package com.ultimatevideo.uveditor.ui.editor

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope

/**
 * A drag that claims the gesture only when it starts on a handle ([grabs] says whether the touch hit one).
 * A touch that misses is left unconsumed, so a scrolling parent (the colour panel) still scrolls under the
 * finger; a touch that hits drags precisely, with [onDrag] receiving each position and [onEnd] called when
 * the finger lifts or the gesture is cancelled. Taps are untouched (no movement past the touch slop).
 */
internal suspend fun PointerInputScope.detectGrabbedDrag(
    grabs: (Offset) -> Boolean,
    onDrag: (Offset) -> Unit,
    onEnd: () -> Unit,
) = awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false)
    if (!grabs(down.position)) return@awaitEachGesture
    val moved = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() } ?: return@awaitEachGesture
    onDrag(moved.position)
    drag(moved.id) { change ->
        change.consume()
        onDrag(change.position)
    }
    onEnd()
}
