package com.qtekfun.ultimatevideoeditor.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatevideoeditor.domain.TrackMath
import com.qtekfun.ultimatevideoeditor.engine.track.TrackStatus
import kotlin.math.abs
import kotlin.math.roundToInt

private val BOX_SIZES = listOf("Small" to 0.07, "Medium" to 0.12, "Large" to 0.2)

/**
 * Motion tracking in the inspector (SPECS.md 9.15). For a video clip: pick a point or box on the preview and follow it
 * through the clip (analysed on the device, with progress and cancel), keep several targets, show a path on the
 * preview, or delete a target. For any other visual clip: make it follow a tracked target, which writes position
 * keyframes along the path (one undo step) that stay editable afterwards.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun TrackControls(clipId: String, track: TrackUiState, onIntent: (EditorIntent) -> Unit) {
    // Read the selected clip's tracks and their status whenever the selection changes.
    LaunchedEffect(clipId) { onIntent(EditorIntent.RefreshTrack) }
    if (!track.canTrack && track.followable.isEmpty()) return

    Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Track motion", style = MaterialTheme.typography.labelLarge)
        if (track.canTrack) {
            if (track.picking) {
                Text(
                    "Tap the point to follow on the preview, or drag a box around it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for ((label, side) in BOX_SIZES) {
                        FilterChip(
                            selected = abs(track.boxSide - side) < 0.005,
                            onClick = { onIntent(EditorIntent.SetTrackBox(side)) },
                            label = { Text(label) },
                            modifier = Modifier.semantics { contentDescription = "Tap box size $label" },
                        )
                    }
                    TextButton(onClick = { onIntent(EditorIntent.CancelTrackPick) }) { Text("Cancel") }
                }
            } else if (track.progress == null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Follow a point or a region of the picture. The playhead frame is where you point.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = { onIntent(EditorIntent.BeginTrackPick) }) { Text("Track an object") }
                }
            }
            val progress = track.progress
            if (progress != null) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Tracking ${(progress * 100).roundToInt()} percent" })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Following the target… ${(progress * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onIntent(EditorIntent.CancelTrack) }) { Text("Cancel") }
                }
            }
            for (item in track.items) {
                val shown = track.activeId == item.track.id
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(item.track.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { onIntent(EditorIntent.ShowTrack(if (shown) null else item.track.id)) }) { Text(if (shown) "Hide path" else "Show path") }
                        TextButton(onClick = { onIntent(EditorIntent.RemoveMotionTrack(item.track.id)) }) { Text("Delete") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(statusText(item.status), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        if (track.progress == null && item.status !is TrackStatus.Ready) {
                            TextButton(onClick = { onIntent(EditorIntent.ReanalyseTrack(item.track.id)) }) { Text(if (item.status is TrackStatus.Stale) "Analyse again" else "Analyse") }
                        }
                    }
                }
            }
        }
        if (track.followable.isNotEmpty()) {
            Text("Make this clip follow a track", style = MaterialTheme.typography.labelMedium)
            for (item in track.followable) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(item.track.name + if (item.ready) "" else " (not analysed)", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Button(
                        onClick = { onIntent(EditorIntent.FollowTrack(item.track.id)) },
                        enabled = item.ready,
                        modifier = Modifier.semantics { contentDescription = "Follow ${item.track.name}" },
                    ) { Text("Follow") }
                }
            }
        }
    }
}

private fun statusText(status: TrackStatus): String = when (status) {
    TrackStatus.NotAnalysed -> "Not analysed yet."
    TrackStatus.Stale -> "The clip reaches outside the analysed part."
    is TrackStatus.Ready ->
        if (status.lost == 0) "Ready: followed in all ${status.frames} frames." else "Ready: lost in ${status.lost} of ${status.frames} frames; it holds the last position there."
}

/**
 * Transparent layer over the preview that waits for the user to point at the target: a tap picks a point, a drag draws
 * a box. Positions go out in project canvas pixels from the canvas centre (the canvas is letterboxed into this view the
 * same way the compositor does it), like the clip gestures.
 */
@Composable
internal fun TrackTargetLayer(
    canvasWidth: Int,
    canvasHeight: Int,
    onPick: (x: Double, y: Double, w: Double?, h: Double?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var band by remember { mutableStateOf<Pair<Offset, Offset>?>(null) }
    val currentPick by rememberUpdatedState(onPick)
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .onSizeChanged { size = it }
            .pointerInput(canvasWidth, canvasHeight, size) {
                val rect = PreviewGeometry.canvasRect(canvasWidth, canvasHeight, size.width.toFloat(), size.height.toFloat())
                val scale = PreviewGeometry.viewScale(rect, canvasWidth)
                if (scale <= 0f) return@pointerInput
                val slop = viewConfiguration.touchSlop * 2f
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    var last = down.position
                    var dragging = false
                    var pressed = true
                    while (pressed) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        last = change.position
                        if (!dragging && (last - down.position).getDistance() > slop) dragging = true
                        if (dragging) band = down.position to last
                        change.consume()
                        pressed = change.pressed
                    }
                    band = null
                    fun toCanvasX(px: Float) = ((px - rect.x) - rect.width / 2f) / scale
                    fun toCanvasY(py: Float) = ((py - rect.y) - rect.height / 2f) / scale
                    if (dragging) {
                        val cx = (down.position.x + last.x) / 2f
                        val cy = (down.position.y + last.y) / 2f
                        currentPick(toCanvasX(cx).toDouble(), toCanvasY(cy).toDouble(), abs(last.x - down.position.x) / scale.toDouble(), abs(last.y - down.position.y) / scale.toDouble())
                    } else {
                        currentPick(toCanvasX(last.x).toDouble(), toCanvasY(last.y).toDouble(), null, null)
                    }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Color.Black.copy(alpha = 0.18f))
            band?.let { (a, b) ->
                val topLeft = Offset(minOf(a.x, b.x), minOf(a.y, b.y))
                drawRect(accent, topLeft = topLeft, size = Size(abs(a.x - b.x), abs(a.y - b.y)), style = Stroke(width = 3f))
            }
        }
        Text(
            "Tap the target, or drag a box",
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
        )
    }
}

/**
 * The tracked path over the preview: a line through the target's canvas position at each project frame (red where the
 * target was lost) and a dot where it is at [playhead]. [points] are canvas pixels from the canvas centre.
 */
@Composable
internal fun TrackPathOverlay(points: List<TrackMath.CanvasPoint>, playhead: Long, canvasWidth: Int, canvasHeight: Int, modifier: Modifier = Modifier) {
    if (points.size < 2) return
    val accent = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val rect = PreviewGeometry.canvasRect(canvasWidth, canvasHeight, size.width, size.height)
        val scale = PreviewGeometry.viewScale(rect, canvasWidth)
        if (scale <= 0f) return@Canvas
        fun at(p: TrackMath.CanvasPoint) = Offset(rect.x + rect.width / 2f + p.x.toFloat() * scale, rect.y + rect.height / 2f + p.y.toFloat() * scale)
        var previous = at(points[0])
        for (i in 1 until points.size) {
            val next = at(points[i])
            drawLine(if (points[i].lost) Color.Red else accent, previous, next, strokeWidth = 4f)
            previous = next
        }
        val current = points.minByOrNull { abs(it.frame - playhead) } ?: return@Canvas
        val centre = at(current)
        drawCircle(Color.White, radius = 14f, center = centre, style = Stroke(width = 4f))
        drawCircle(if (current.lost) Color.Red else accent, radius = 8f, center = centre)
    }
}
