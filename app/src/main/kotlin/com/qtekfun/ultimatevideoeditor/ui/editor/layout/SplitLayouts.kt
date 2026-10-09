package com.qtekfun.ultimatevideoeditor.ui.editor.layout

import com.qtekfun.ultimatevideoeditor.R
import androidx.compose.ui.res.stringResource
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** How long a divider drag is held back between layout updates; the native surfaces resize each time. */
private const val DRAG_STEP_MS = 40L

/** Thickness of the bar that carries a divider, in dp. */
private val HANDLE_THICKNESS = 18.dp
private val HANDLE_THICKNESS_CUSTOMISING = 28.dp

/** What the preview/timeline layout measured last, read by the divider to turn a drag in pixels into a share. */
class SplitMetrics {
    /** Room shared by the preview and the timeline, in px (the block minus the controls and the handle). */
    var flexiblePx: Int = 0
}

private const val PREVIEW = "preview"
private const val HANDLE = "handle"
private const val CHROME = "chrome"
private const val TIMELINE = "timeline"

/**
 * The editor's middle block, top to bottom: the preview, the divider handle, the controls (transport and
 * toolbar, at their natural height) and the timeline. The preview and the timeline split what is left by
 * [fraction], which is read while measuring: a drag changes the layout without recomposing anything.
 *
 * With [fullscreen] the preview takes the whole block. The other three are still composed and measured as
 * before, but placed below the block (outside the window) instead of being removed: the native timeline view
 * keeps its surface, so playback and the timeline renderer are never torn down by the toggle.
 */
@Composable
internal fun PreviewTimelineLayout(
    fraction: () -> Float,
    metrics: SplitMetrics,
    handleThickness: () -> Dp,
    fullscreen: Boolean,
    modifier: Modifier = Modifier,
    preview: @Composable () -> Unit,
    handle: @Composable () -> Unit,
    chrome: @Composable () -> Unit,
    timeline: @Composable () -> Unit,
) {
    Layout(
        content = {
            Box(Modifier.layoutId(PREVIEW)) { preview() }
            Box(Modifier.layoutId(HANDLE)) { handle() }
            Box(Modifier.layoutId(CHROME)) { chrome() }
            Box(Modifier.layoutId(TIMELINE)) { timeline() }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else constraints.minHeight
        val handlePx = handleThickness().roundToPx()
        val byId = measurables.associateBy { it.layoutId }
        val chromePlaceable = byId.getValue(CHROME).measure(Constraints(minWidth = width, maxWidth = width, maxHeight = height))
        val handlePlaceable = byId.getValue(HANDLE).measure(Constraints.fixed(width, handlePx))
        val flexible = (height - chromePlaceable.height - handlePx).coerceAtLeast(0)
        metrics.flexiblePx = flexible
        val previewHeight = (flexible * fraction()).roundToInt().coerceIn(0, flexible)
        val timelineHeight = flexible - previewHeight
        val shownHeight = if (fullscreen) height else previewHeight
        val previewPlaceable = byId.getValue(PREVIEW).measure(Constraints.fixed(width, shownHeight))
        val timelinePlaceable = byId.getValue(TIMELINE).measure(Constraints.fixed(width, timelineHeight))
        // Fullscreen: everything but the preview sits one block below, where nothing is drawn or touched.
        val shift = if (fullscreen) height else 0
        layout(width, height) {
            previewPlaceable.place(0, 0)
            handlePlaceable.place(0, previewHeight + shift)
            chromePlaceable.place(0, previewHeight + handlePx + shift)
            timelinePlaceable.place(0, previewHeight + handlePx + chromePlaceable.height + shift)
        }
    }
}

private const val LEFT_PANEL = "left"
private const val LEFT_HANDLE = "lhandle"
private const val MAIN = "main"
private const val RIGHT_HANDLE = "rhandle"
private const val RIGHT_PANEL = "right"

/** A side column's width request: [px] is its width, or the collapsed strip. */
class SideRequest(val present: Boolean, val widthDp: Float, val collapsed: Boolean)

/**
 * The editor between optional side columns. The main content is always composed at the same place, so a
 * change of docks never recreates the native timeline and preview views under it. Widths come from
 * lambdas read while measuring, so dragging a divider re-measures without recomposing.
 */
@Composable
internal fun DockLayout(
    left: () -> SideRequest,
    right: () -> SideRequest,
    handleThickness: () -> Dp,
    fullscreen: Boolean,
    modifier: Modifier = Modifier,
    leftPanel: @Composable () -> Unit,
    leftHandle: @Composable () -> Unit,
    rightPanel: @Composable () -> Unit,
    rightHandle: @Composable () -> Unit,
    main: @Composable () -> Unit,
) {
    Layout(
        content = {
            Box(Modifier.layoutId(LEFT_PANEL)) { leftPanel() }
            Box(Modifier.layoutId(LEFT_HANDLE)) { leftHandle() }
            Box(Modifier.layoutId(MAIN)) { main() }
            Box(Modifier.layoutId(RIGHT_HANDLE)) { rightHandle() }
            Box(Modifier.layoutId(RIGHT_PANEL)) { rightPanel() }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val handlePx = handleThickness().roundToPx()
        val stripPx = COLLAPSED_STRIP.roundToPx()
        val byId = measurables.associateBy { it.layoutId }

        fun columnPx(request: SideRequest): Int = when {
            !request.present -> 0
            request.collapsed -> stripPx
            else -> request.widthDp.dp.roundToPx()
        }
        // Fullscreen preview: the side columns keep their place in the tree but get no room.
        var leftPx = if (fullscreen) 0 else columnPx(left())
        var rightPx = if (fullscreen) 0 else columnPx(right())
        val leftHandlePx = if (!fullscreen && left().present && !left().collapsed) handlePx else 0
        val rightHandlePx = if (!fullscreen && right().present && !right().collapsed) handlePx else 0
        // Whatever the saved widths say, the editor keeps at least 30 % of the window.
        val room = (width * 0.7f).roundToInt() - leftHandlePx - rightHandlePx
        if (leftPx + rightPx > room && leftPx + rightPx > 0) {
            val scale = room.toFloat() / (leftPx + rightPx)
            if (!left().collapsed) leftPx = (leftPx * scale).roundToInt()
            if (!right().collapsed) rightPx = (rightPx * scale).roundToInt()
        }
        val mainWidth = (width - leftPx - rightPx - leftHandlePx - rightHandlePx).coerceAtLeast(0)

        val leftPlaceable = byId.getValue(LEFT_PANEL).measure(Constraints.fixed(leftPx, if (leftPx > 0) height else 0))
        val leftHandlePlaceable = byId.getValue(LEFT_HANDLE).measure(Constraints.fixed(leftHandlePx, if (leftHandlePx > 0) height else 0))
        val mainPlaceable = byId.getValue(MAIN).measure(Constraints.fixed(mainWidth, height))
        val rightHandlePlaceable = byId.getValue(RIGHT_HANDLE).measure(Constraints.fixed(rightHandlePx, if (rightHandlePx > 0) height else 0))
        val rightPlaceable = byId.getValue(RIGHT_PANEL).measure(Constraints.fixed(rightPx, if (rightPx > 0) height else 0))
        layout(width, height) {
            var x = 0
            leftPlaceable.place(x, 0)
            x += leftPx
            leftHandlePlaceable.place(x, 0)
            x += leftHandlePx
            mainPlaceable.place(x, 0)
            x += mainWidth
            rightHandlePlaceable.place(x, 0)
            x += rightHandlePx
            rightPlaceable.place(x, 0)
        }
    }
}

/** Width of a collapsed side column: just room for its expand button. */
val COLLAPSED_STRIP = 40.dp

fun handleThickness(customising: Boolean): Dp = if (customising) HANDLE_THICKNESS_CUSTOMISING else HANDLE_THICKNESS

/**
 * A grab bar between two areas. A drag reports pixels, at most every [DRAG_STEP_MS], and the last bit when
 * the finger lifts; a double tap resets to the default. [onTarget] reports each pass over the default
 * position so the caller can tick. Accessible through a custom action that resets it.
 */
@Composable
internal fun DragHandle(
    orientation: Orientation,
    customising: Boolean,
    description: String,
    onDelta: (Float) -> Unit,
    onEnd: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val coalescer = remember { DragCoalescer(DRAG_STEP_MS) }
    val currentOnDelta by rememberUpdatedState(onDelta)
    val currentOnEnd by rememberUpdatedState(onEnd)
    val currentOnReset by rememberUpdatedState(onReset)
    val vertical = orientation == Orientation.Vertical
    val resetLabel = stringResource(R.string.ed_2a_reset_to_default)
    val finish = {
        val rest = coalescer.flush()
        if (rest != 0f) currentOnDelta(rest)
        currentOnEnd()
    }
    Box(
        modifier = modifier
            .semantics {
                contentDescription = description
                customActions = listOf(CustomAccessibilityAction(resetLabel) { currentOnReset(); true })
            }
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { currentOnReset() }) }
            .pointerInput(orientation) {
                if (vertical) {
                    detectVerticalDragGestures(
                        onDragEnd = { finish() },
                        onDragCancel = { finish() },
                    ) { change, delta ->
                        change.consume()
                        coalescer.offer(delta, SystemClock.uptimeMillis())?.let { currentOnDelta(it) }
                    }
                } else {
                    detectHorizontalDragGestures(
                        onDragEnd = { finish() },
                        onDragCancel = { finish() },
                    ) { change, delta ->
                        change.consume()
                        coalescer.offer(delta, SystemClock.uptimeMillis())?.let { currentOnDelta(it) }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val color = if (customising) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        val grip = Modifier
            .clip(RoundedCornerShape(50))
            .background(color)
        if (vertical) {
            Box(grip.width(if (customising) 64.dp else 36.dp).height(if (customising) 6.dp else 4.dp))
        } else {
            Box(grip.width(if (customising) 6.dp else 4.dp).height(if (customising) 64.dp else 36.dp))
        }
    }
}

/** Does a tick when the divider passes over its default position; shared by both kinds of divider. */
@Composable
internal fun rememberTicker(): (previous: Float, next: Float, target: Float, tolerance: Float) -> Unit {
    val haptics = LocalHapticFeedback.current
    return remember(haptics) {
        { previous, next, target, tolerance ->
            if (crossesTarget(previous, next, target, tolerance)) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }
}
