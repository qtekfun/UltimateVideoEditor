package com.qtekfun.ultimatevideoeditor.ui.editor

import com.qtekfun.ultimatevideoeditor.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatevideoeditor.domain.SpeedKey

/**
 * The graphical editor of a clip's speed curve: relative speed over the clip, one dot per [SpeedKey]. Tap an empty spot
 * to add a key on the curve, tap a dot to select it, drag a dot to move it (the change is one undo step, committed when
 * the finger lifts). The selected key can ease into the next one (a rounded segment instead of a straight one) or be
 * deleted. Deleting down to fewer than two keys removes the curve. [onCommit] receives the new key list (empty for none).
 */
@Composable
internal fun SpeedCurveEditor(
    keys: List<SpeedKey>,
    durationFrames: Long,
    onCommit: (List<SpeedKey>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var live by remember(keys) { mutableStateOf(keys) }
    var selected by remember(keys.size) { mutableIntStateOf(-1) }
    var dragging by remember { mutableIntStateOf(-1) }
    val density = LocalDensity.current
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val dotColor = MaterialTheme.colorScheme.tertiary
    val selectedColor = MaterialTheme.colorScheme.error
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .described(stringResource(R.string.ed_2b_speed_curve_editor_tap_to))
                .pointerInput(live, durationFrames) {
                    detectTapGestures { tap ->
                        val x = tap.x / size.width
                        val y = 1f - tap.y / size.height
                        val aspect = size.width.toFloat() / size.height
                        val hit = SpeedCurveModel.nearestKey(live, x, y, durationFrames, aspect, HIT_RADIUS)
                        if (hit != null) {
                            selected = hit
                        } else {
                            val (added, index) = SpeedCurveModel.add(live, SpeedCurveModel.fractionToFrame(x, durationFrames), durationFrames)
                            if (index >= 0) {
                                selected = index
                                if (added != live) onCommit(added)
                            }
                        }
                    }
                }
                .pointerInput(durationFrames) {
                    detectDragGestures(
                        onDragStart = { start ->
                            val aspect = size.width.toFloat() / size.height
                            val hit = SpeedCurveModel.nearestKey(live, start.x / size.width, 1f - start.y / size.height, durationFrames, aspect, HIT_RADIUS)
                            dragging = hit ?: -1
                            if (hit != null) selected = hit
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val index = dragging
                            if (index >= 0) {
                                val frame = SpeedCurveModel.fractionToFrame(change.position.x / size.width, durationFrames)
                                val weight = SpeedCurveModel.fractionToWeight(1f - change.position.y / size.height)
                                live = SpeedCurveModel.move(live, index, frame, weight, durationFrames)
                            }
                        },
                        onDragEnd = {
                            if (dragging >= 0 && !SpeedCurveModel.same(live, keys)) onCommit(live)
                            dragging = -1
                        },
                        onDragCancel = {
                            live = keys
                            dragging = -1
                        },
                    )
                },
        ) {
            val w = size.width
            val h = size.height
            val label = android.graphics.Paint().apply {
                color = labelColor.toArgb()
                textSize = with(density) { 10.dp.toPx() }
                isAntiAlias = true
            }
            for (weight in SpeedCurveModel.GRID_WEIGHTS) {
                val y = h * (1f - SpeedCurveModel.weightToFraction(weight))
                drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = if (weight == 1000) 2f else 1f)
                drawContext.canvas.nativeCanvas.drawText("${weight / 1000f}x".replace(".0x", "x"), 4f, y - 3f, label)
            }
            val points = SpeedCurveModel.polyline(live, durationFrames)
            if (points.isNotEmpty()) {
                val path = Path()
                points.forEachIndexed { i, p ->
                    val px = p.first * w
                    val py = h * (1f - p.second)
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                drawPath(path, lineColor, style = Stroke(width = 4f))
            }
            live.forEachIndexed { i, key ->
                val center = Offset(
                    SpeedCurveModel.frameToFraction(key.frame, durationFrames) * w,
                    h * (1f - SpeedCurveModel.weightToFraction(key.weightPermille)),
                )
                drawCircle(if (i == selected) selectedColor else dotColor, radius = if (i == selected) 14f else 10f, center = center)
            }
        }
        val key = live.getOrNull(selected)
        if (key != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.ed_2b_speed_key_readout, key.frame, "%.2f".format(key.weightPermille / 1000f).trimEnd('0').trimEnd('.')),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(stringResource(R.string.ed_2b_ease), style = MaterialTheme.typography.labelMedium)
                Switch(
                    checked = key.smooth,
                    onCheckedChange = { onCommit(SpeedCurveModel.toggleSmooth(live, selected)) },
                    modifier = Modifier.described(stringResource(R.string.ed_2b_ease_the_segment_after_this)),
                )
                TextButton(onClick = {
                    val removed = SpeedCurveModel.remove(live, selected)
                    selected = -1
                    onCommit(removed)
                }) { Text(stringResource(R.string.ed_2b_delete_key)) }
            }
        } else {
            Text(
                text = stringResource(R.string.ed_2b_tap_the_curve_to_add),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private const val HIT_RADIUS = 0.12f
