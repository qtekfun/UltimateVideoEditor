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

/** How long a divider drag is held back between layout updates; the native surfaces resize each time. */
private const val DRAG_STEP_MS = 40L

/** Thickness of the bar that carries a divider, in dp. */
private val HANDLE_THICKNESS = 18.dp
private val HANDLE_THICKNESS_CUSTOMISING = 28.dp

/** What the preview/timeline layout measured last, read by the dividers to turn a drag in pixels into a share. */
class SplitMetrics {
    /** Height shared by the preview (or the band) and the timeline, in px (the block minus the controls and the handle). */
    var flexiblePx: Int = 0

    /** Width shared by the preview and the controls/timeline beside it in [Workspace.SIDE_BY_SIDE], in px. */
    var sideRoomPx: Int = 0
}

private const val PREVIEW = "preview"
private const val HANDLE = "handle"
private const val SIDE_HANDLE = "sidehandle"
private const val CHROME = "chrome"
private const val TIMELINE = "timeline"
private const val BAND_LEFT = "bandleft"
private const val BAND_LEFT_HANDLE = "bandlhandle"
private const val BAND_RIGHT_HANDLE = "bandrhandle"
private const val BAND_RIGHT = "bandright"

/**
 * The editor's middle block. What goes where depends on [workspace]:
 *
 * - STACKED, top to bottom: the preview, the divider [handle], the controls ([chrome]: transport and toolbar) and the timeline.
 * - WORKBENCH: a band on top (the left panel, the preview, the right panel, with their dividers) and under it the same
 *   handle, controls and timeline across the whole width. The panels are drawn only in the band.
 * - SIDE_BY_SIDE: the preview on the left at the full height, then [sideHandle], and on the right the controls over the timeline.
 *
 * The preview and the timeline are the same two children in every workspace, only placed differently, so changing it never
 * recreates their native surfaces. The shares are read while measuring: a drag changes the layout without recomposing anything.
 *
 * With [fullscreen] the preview takes the whole block. The rest is still composed and measured as before, but placed below the
 * block (outside the window) instead of being removed, so playback and the timeline renderer are never torn down by the toggle.
 */
@Composable
internal fun PreviewTimelineLayout(
    workspace: () -> Workspace,
    fraction: () -> Float,
    widthFraction: () -> Float,
    metrics: SplitMetrics,
    handleThickness: () -> Dp,
    fullscreen: Boolean,
    modifier: Modifier = Modifier,
    band: BandSlots,
    preview: @Composable () -> Unit,
    handle: @Composable () -> Unit,
    sideHandle: @Composable () -> Unit,
    chrome: @Composable () -> Unit,
    timeline: @Composable () -> Unit,
) {
    Layout(
        content = {
            Box(Modifier.layoutId(PREVIEW)) { preview() }
            Box(Modifier.layoutId(HANDLE)) { handle() }
            Box(Modifier.layoutId(SIDE_HANDLE)) { sideHandle() }
            Box(Modifier.layoutId(CHROME)) { chrome() }
            Box(Modifier.layoutId(TIMELINE)) { timeline() }
            Box(Modifier.layoutId(BAND_LEFT)) { band.leftPanel() }
            Box(Modifier.layoutId(BAND_LEFT_HANDLE)) { band.leftHandle() }
            Box(Modifier.layoutId(BAND_RIGHT_HANDLE)) { band.rightHandle() }
            Box(Modifier.layoutId(BAND_RIGHT)) { band.rightPanel() }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else constraints.minHeight
        val handlePx = handleThickness().roundToPx()
        val stripPx = COLLAPSED_STRIP.roundToPx()
        val byId = measurables.associateBy { it.layoutId }
        val mode = workspace()
        // Nothing here is drawn or touched when it is not part of the current workspace: it is measured to zero.
        val none = Constraints.fixed(0, 0)

        fun columnPx(request: SideRequest): Int = when {
            !request.present -> 0
            request.collapsed -> stripPx
            else -> request.widthDp.dp.roundToPx()
        }

        when (mode) {
            Workspace.SIDE_BY_SIDE -> {
                val room = (width - handlePx).coerceAtLeast(0)
                val split = resolveSideSplit(room, widthFraction(), MIN_SIDE_PREVIEW_DP.dp.roundToPx(), MIN_SIDE_REST_DP.dp.roundToPx())
                metrics.sideRoomPx = room
                // The controls scroll instead of taking the timeline's last rows.
                val chromeCap = (height - MIN_TIMELINE_DP.dp.roundToPx()).coerceAtLeast(MIN_CHROME_DP.dp.roundToPx()).coerceAtMost(height)
                val chromePlaceable = byId.getValue(CHROME).measure(Constraints(minWidth = split.rest, maxWidth = split.rest, maxHeight = chromeCap))
                val timelineHeight = (height - chromePlaceable.height).coerceAtLeast(0)
                metrics.flexiblePx = timelineHeight
                val previewPlaceable = byId.getValue(PREVIEW).measure(
                    if (fullscreen) Constraints.fixed(width, height) else Constraints.fixed(split.preview, height),
                )
                val sideHandlePlaceable = byId.getValue(SIDE_HANDLE).measure(Constraints.fixed(handlePx, height))
                val timelinePlaceable = byId.getValue(TIMELINE).measure(Constraints.fixed(split.rest, timelineHeight))
                val hidden = byId.getValue(HANDLE).measure(none)
                val bandHidden = listOf(BAND_LEFT, BAND_LEFT_HANDLE, BAND_RIGHT_HANDLE, BAND_RIGHT).map { byId.getValue(it).measure(none) }
                val shift = if (fullscreen) height else 0
                layout(width, height) {
                    previewPlaceable.place(0, 0)
                    sideHandlePlaceable.place(split.preview + shift, 0)
                    chromePlaceable.place(split.preview + handlePx + shift, 0)
                    timelinePlaceable.place(split.preview + handlePx + shift, chromePlaceable.height)
                    hidden.place(0, 0)
                    bandHidden.forEach { it.place(0, 0) }
                }
            }

            Workspace.STACKED, Workspace.WORKBENCH -> {
                // The controls scroll rather than starve the preview and the timeline of their minimum heights.
                val minPreview = MIN_PREVIEW_DP.dp.roundToPx()
                val minTimeline = MIN_TIMELINE_DP.dp.roundToPx()
                val chromeCap = chromeMaxHeight(height, handlePx, minPreview, minTimeline, MIN_CHROME_DP.dp.roundToPx())
                val chromePlaceable = byId.getValue(CHROME).measure(Constraints(minWidth = width, maxWidth = width, maxHeight = chromeCap))
                val heights = resolveStacked(height, handlePx, chromePlaceable.height, fraction(), minPreview, minTimeline)
                val handlePlaceable = byId.getValue(HANDLE).measure(Constraints.fixed(width, minOf(handlePx, height)))
                val flexible = heights.preview + heights.timeline
                metrics.flexiblePx = flexible
                val topHeight = heights.preview
                val timelineHeight = heights.timeline
                val shownHeight = if (fullscreen) height else topHeight

                // The band's columns exist only in the workbench; stacked, the dock layout around the editor carries them.
                val banded = mode == Workspace.WORKBENCH
                val left = if (banded) band.left() else SideRequest(false, 0f, false)
                val right = if (banded) band.right() else SideRequest(false, 0f, false)
                val cols = resolveColumns(
                    width = width,
                    leftWanted = columnPx(left),
                    rightWanted = columnPx(right),
                    leftOpen = left.present && !left.collapsed,
                    rightOpen = right.present && !right.collapsed,
                    handlePx = handlePx,
                    fullscreen = fullscreen,
                )
                val bandHeight = shownHeight
                fun column(id: String, px: Int) = byId.getValue(id).measure(Constraints.fixed(px, if (px > 0) bandHeight else 0))
                val leftPlaceable = column(BAND_LEFT, cols.left)
                val leftHandlePlaceable = column(BAND_LEFT_HANDLE, cols.leftHandle)
                val rightHandlePlaceable = column(BAND_RIGHT_HANDLE, cols.rightHandle)
                val rightPlaceable = column(BAND_RIGHT, cols.right)
                val previewPlaceable = byId.getValue(PREVIEW).measure(Constraints.fixed(cols.main, shownHeight))
                val timelinePlaceable = byId.getValue(TIMELINE).measure(Constraints.fixed(width, timelineHeight))
                val hidden = byId.getValue(SIDE_HANDLE).measure(none)
                val shift = if (fullscreen) height else 0
                layout(width, height) {
                    var x = 0
                    leftPlaceable.place(x, 0)
                    x += cols.left
                    leftHandlePlaceable.place(x, 0)
                    x += cols.leftHandle
                    previewPlaceable.place(x, 0)
                    x += cols.main
                    rightHandlePlaceable.place(x, 0)
                    x += cols.rightHandle
                    rightPlaceable.place(x, 0)
                    handlePlaceable.place(0, topHeight + shift)
                    chromePlaceable.place(0, topHeight + handlePx + shift)
                    timelinePlaceable.place(0, topHeight + handlePx + chromePlaceable.height + shift)
                    hidden.place(0, 0)
                }
            }
        }
    }
}

/** What the band of [Workspace.WORKBENCH] holds besides the preview: the two side columns and their dividers. */
internal class BandSlots(
    val left: () -> SideRequest,
    val right: () -> SideRequest,
    val leftPanel: @Composable () -> Unit,
    val leftHandle: @Composable () -> Unit,
    val rightPanel: @Composable () -> Unit,
    val rightHandle: @Composable () -> Unit,
)

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
        val l = left()
        val r = right()
        val cols = resolveColumns(
            width = width,
            leftWanted = columnPx(l),
            rightWanted = columnPx(r),
            leftOpen = l.present && !l.collapsed,
            rightOpen = r.present && !r.collapsed,
            handlePx = handlePx,
            fullscreen = fullscreen,
        )
        val leftPx = cols.left
        val rightPx = cols.right
        val leftHandlePx = cols.leftHandle
        val rightHandlePx = cols.rightHandle
        val mainWidth = cols.main

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
