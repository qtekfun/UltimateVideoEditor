package com.qtekfun.ultimatevideoeditor.ui.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.qtekfun.ultimatevideoeditor.ui.editor.tray.findActivity

/** Only whether the preview is fullscreen survives rotation and process recreation; the overlay starts hidden. */
internal val FullscreenSaver = Saver<FullscreenState, Boolean>(
    save = { it.active },
    restore = { FullscreenState(active = it) },
)

/** How far apart (in touch slops) the two taps of a double tap may land. */
private const val DOUBLE_TAP_SLOPS = 8f

/**
 * Reports taps on the preview without consuming anything that matters to others: it watches in the initial
 * pass, so it sees every touch before the drag/pinch layer, the track picker or the eyedropper. A touch that is
 * not a tap (moves, lasts long, or has a second finger) is never reported. The second tap of a double tap has its
 * down consumed, so a layer that honours consumption does not also act on it. Touches that start inside the
 * rectangle [ignoreIn] returns (the overlay's own buttons) are left alone.
 */
@Composable
internal fun Modifier.previewTapGestures(
    ignoreIn: () -> Rect?,
    onTap: () -> Unit,
    onDoubleTap: () -> Unit,
): Modifier {
    val currentIgnore by rememberUpdatedState(ignoreIn)
    val currentTap by rememberUpdatedState(onTap)
    val currentDouble by rememberUpdatedState(onDoubleTap)
    return pointerInput(Unit) {
        val tracker = DoubleTapTracker(viewConfiguration.doubleTapTimeoutMillis, viewConfiguration.touchSlop * DOUBLE_TAP_SLOPS)
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val ignored = currentIgnore()?.contains(down.position) == true
            val second = !ignored && tracker.continuesTap(down.uptimeMillis, down.position.x, down.position.y)
            if (second) down.consume()
            var isTap = !ignored
            var upMs = down.uptimeMillis
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                upMs = event.changes.maxOf { it.uptimeMillis }
                if (event.changes.size > 1) isTap = false
                val mine = event.changes.firstOrNull { it.id == down.id }
                if (mine != null && (mine.position - down.position).getDistance() > viewConfiguration.touchSlop) isTap = false
            } while (event.changes.any { it.pressed })
            when {
                ignored -> tracker.reset()
                isTap && upMs - down.uptimeMillis <= viewConfiguration.longPressTimeoutMillis -> {
                    if (tracker.tap(down.uptimeMillis, upMs, down.position.x, down.position.y)) currentDouble() else currentTap()
                }
                else -> tracker.reset()
            }
        }
    }
}

/**
 * The small control strip over a fullscreen preview: play/pause and the way out. It fades with [visible];
 * [onBounds] reports where it sits (in its parent) so a tap on it is not mistaken for a tap on the picture.
 */
@Composable
internal fun FullscreenControls(
    visible: Boolean,
    playing: Boolean,
    onPlayPause: () -> Unit,
    onExit: () -> Unit,
    onBounds: (Rect?) -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        DisposableEffect(Unit) { onDispose { onBounds(null) } }
        CompositionLocalProvider(LocalContentColor provides Color.White) {
            Row(
                modifier = Modifier
                    .padding(bottom = 24.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(28.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .onGloballyPositioned { onBounds(it.boundsInParent()) },
            ) {
                ToolButton(
                    icon = if (playing) EditorIcons.Pause else EditorIcons.Play,
                    description = if (playing) "Pause" else "Play",
                    onClick = onPlayPause,
                )
                ToolButton(EditorIcons.FullscreenExit, "Leave fullscreen", onClick = onExit)
            }
        }
    }
}

/**
 * Hides the system bars while [active] (a swipe from the edge shows them for a moment) and brings them back when
 * it ends or the editor leaves. Re-applied on resume, because the system may show them again after another
 * activity (a file picker) was on top.
 */
@Composable
internal fun ImmersiveWhile(active: Boolean) {
    val view = LocalView.current
    val window = view.context.findActivity()?.window
    DisposableEffect(active, window) {
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (active) hideBars(controller)
        onDispose { if (active) controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (active) hideBars(window?.let { WindowCompat.getInsetsController(it, view) })
    }
}

private fun hideBars(controller: WindowInsetsControllerCompat?) {
    controller ?: return
    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    controller.hide(WindowInsetsCompat.Type.systemBars())
}
