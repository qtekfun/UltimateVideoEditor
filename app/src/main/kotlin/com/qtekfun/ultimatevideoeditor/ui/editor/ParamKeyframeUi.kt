package com.qtekfun.ultimatevideoeditor.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatevideoeditor.domain.BezierHandle
import com.qtekfun.ultimatevideoeditor.domain.Clip
import com.qtekfun.ultimatevideoeditor.domain.Interpolation
import com.qtekfun.ultimatevideoeditor.domain.ParamKey
import com.qtekfun.ultimatevideoeditor.domain.ParamSpec
import com.qtekfun.ultimatevideoeditor.domain.ParamTracks
import com.qtekfun.ultimatevideoeditor.domain.PoseParams
import com.qtekfun.ultimatevideoeditor.domain.paramKeys
import com.qtekfun.ultimatevideoeditor.domain.paramSpec
import com.qtekfun.ultimatevideoeditor.domain.staticParamValue
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * What a control needs to draw the diamond of its parameter: whether the parameter is animated at all and
 * whether the playhead is on one of its keys.
 */
internal data class ParamKeyUi(val paramId: String, val animated: Boolean, val keyHere: Boolean)

/** The key state of [paramId] for the selected clip, or null when that parameter cannot be animated on it. */
internal fun EditorState.keyUi(paramId: String): ParamKeyUi? {
    val clip = selectedClip ?: return null
    if (clip.paramSpec(paramId) == null) return null
    val keys = clip.paramKeys(paramId)
    val frame = selectedFrame
    return ParamKeyUi(paramId, keys.isNotEmpty(), frame != null && ParamTracks.at(keys, frame) != null)
}

/** Provided by the inspector: how a slider finds the diamond state of its parameter. */
internal val LocalParamKeys = compositionLocalOf<(String) -> ParamKeyUi?> { { null } }

/** The diamond next to a control: tap to add a key holding the shown value at the playhead, or to remove the one there. */
@Composable
internal fun KeyDiamond(ui: ParamKeyUi, onIntent: (EditorIntent) -> Unit) {
    IconButton(onClick = { onIntent(EditorIntent.ToggleParamKey(ui.paramId)) }, modifier = Modifier.size(32.dp)) {
        Icon(
            imageVector = if (ui.keyHere) EditorIcons.KeyframeOn else EditorIcons.KeyframeOff,
            contentDescription = when {
                ui.keyHere -> "Remove the keyframe at the playhead"
                ui.animated -> "Add a keyframe at the playhead"
                else -> "Animate this value: add a keyframe at the playhead"
            },
            tint = if (ui.animated) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

private class LaneRowSpec(val paramId: String, val label: String, val isPose: Boolean)

private fun laneRows(clip: Clip): List<LaneRowSpec> = buildList {
    if (clip.keyframes.isNotEmpty()) add(LaneRowSpec(PoseParams.POSITION_X.id, "Pose", isPose = true))
    for (track in clip.params) {
        val spec = clip.paramSpec(track.paramId) ?: continue
        add(LaneRowSpec(track.paramId, spec.label, isPose = false))
    }
}

/**
 * The keyframes of every animated value of the selected clip: one row per parameter with its curve over the clip's
 * length, the playhead, and the keys. Drag a key sideways to move it in time and up or down to change its value
 * (the pose row only moves in time: its values are edited with the sliders above); tap a key to shape the
 * curve that leaves it. Each row can jump between its keys and copy, paste or clear them.
 */
@Composable
internal fun KeyframeLane(state: EditorState, onIntent: (EditorIntent) -> Unit) {
    val clip = state.selectedClip ?: return
    val rows = laneRows(clip)
    if (rows.isEmpty()) return
    Text(text = "Keyframes", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    for (row in rows) LaneRow(state, clip, row, onIntent)
    val selected = state.selectedParamKey
    if (selected != null && rows.any { it.paramId == selected.first }) {
        val key = clip.paramKeys(selected.first).firstOrNull { it.frame == selected.second }
        if (key != null) {
            CurveControls(
                title = rows.first { it.paramId == selected.first }.label,
                key = key,
                onShape = { mode, out, inn -> onIntent(EditorIntent.SetParamKeyShape(selected.first, key.frame, mode, out, inn)) },
            )
        }
    }
}

@Composable
private fun LaneRow(state: EditorState, clip: Clip, row: LaneRowSpec, onIntent: (EditorIntent) -> Unit) {
    val keys = clip.paramKeys(row.paramId)
    val spec = clip.paramSpec(row.paramId) ?: return
    val ui = state.keyUi(row.paramId)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Text(
            text = "${row.label} · ${keys.size}",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(120.dp),
            maxLines = 1,
        )
        IconButton(onClick = { onIntent(EditorIntent.JumpToParamKey(row.paramId, forward = false)) }, modifier = Modifier.size(32.dp), enabled = keys.isNotEmpty()) {
            Icon(EditorIcons.SkipPrevious, contentDescription = "Previous keyframe of ${row.label}", modifier = Modifier.size(16.dp))
        }
        if (ui != null && !row.isPose) KeyDiamond(ui, onIntent)
        IconButton(onClick = { onIntent(EditorIntent.JumpToParamKey(row.paramId, forward = true)) }, modifier = Modifier.size(32.dp), enabled = keys.isNotEmpty()) {
            Icon(EditorIcons.SkipNext, contentDescription = "Next keyframe of ${row.label}", modifier = Modifier.size(16.dp))
        }
        if (!row.isPose) {
            TextButton(onClick = { onIntent(EditorIntent.CopyParamKeys(row.paramId)) }, enabled = keys.isNotEmpty()) { Text("Copy") }
            TextButton(onClick = { onIntent(EditorIntent.PasteParamKeys(row.paramId)) }, enabled = state.paramClipboard != null) { Text("Paste") }
            TextButton(onClick = { onIntent(EditorIntent.ClearParamTrack(row.paramId)) }, enabled = keys.isNotEmpty()) { Text("Clear") }
        }
    }
    LaneCanvas(state, clip, row, keys, spec, onIntent)
}

private val LaneHeight = 56.dp
private val LanePad = 8.dp
private val HitRadius = 22.dp

@Composable
private fun LaneCanvas(
    state: EditorState,
    clip: Clip,
    row: LaneRowSpec,
    keys: List<ParamKey>,
    spec: ParamSpec,
    onIntent: (EditorIntent) -> Unit,
) {
    val duration = clip.durationFrames
    val last = (duration - 1).coerceAtLeast(1)
    val density = LocalDensity.current
    val pad = with(density) { LanePad.toPx() }
    val hit = with(density) { HitRadius.toPx() }
    val staticValue = clip.staticParamValue(row.paramId) ?: spec.min
    val range = (spec.max - spec.min).takeIf { it > 0.0 } ?: 1.0
    val lineColor = MaterialTheme.colorScheme.primary
    val axisColor = MaterialTheme.colorScheme.outline
    val playheadColor = Color(0xFFFF453A)
    val selectedColor = MaterialTheme.colorScheme.tertiary
    val frame = state.selectedFrame
    val selected = state.selectedParamKey?.takeIf { it.first == row.paramId }?.second
    var dragging by remember(row.paramId, clip.id) { mutableStateOf<ParamKey?>(null) }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(LaneHeight)
            .semantics { contentDescription = "Keyframes of ${row.label}, ${keys.size} keys" }
            .pointerInput(clip.id, row.paramId, keys, duration) {
                fun xOf(f: Long) = pad + (size.width - 2 * pad) * (f.toFloat() / last.toFloat())
                fun yOf(v: Double) = pad + (size.height - 2 * pad) * (1f - ((v - spec.min) / range).toFloat())
                fun nearest(at: Offset): ParamKey? = keys
                    .minByOrNull { abs(xOf(it.frame) - at.x) + if (row.isPose) 0f else abs(yOf(it.value) - at.y) * 0.5f }
                    ?.takeIf { abs(xOf(it.frame) - at.x) <= hit }
                detectTapGestures { at ->
                    val key = nearest(at)
                    onIntent(EditorIntent.SelectParamKey(key?.let { row.paramId }, key?.frame))
                }
            }
            .pointerInput(clip.id, row.paramId, keys, duration) {
                fun frameAt(x: Float): Long =
                    (((x - pad) / (size.width - 2 * pad)).coerceIn(0f, 1f) * last).roundToLong().coerceIn(0L, duration - 1)
                fun valueAt(y: Float): Double =
                    spec.min + (1.0 - ((y - pad) / (size.height - 2 * pad)).toDouble().coerceIn(0.0, 1.0)) * range
                fun xOf(f: Long) = pad + (size.width - 2 * pad) * (f.toFloat() / last.toFloat())
                detectDragGestures(
                    onDragStart = { at ->
                        dragging = keys.minByOrNull { abs(xOf(it.frame) - at.x) }?.takeIf { abs(xOf(it.frame) - at.x) <= hit }
                    },
                    onDrag = { change, _ ->
                        val key = dragging ?: return@detectDragGestures
                        change.consume()
                        val toFrame = frameAt(change.position.x)
                        val value = if (row.isPose) key.value else valueAt(change.position.y)
                        onIntent(EditorIntent.UpdateParamKey(row.paramId, key.frame, toFrame, value))
                    },
                    onDragEnd = {
                        if (dragging != null) onIntent(EditorIntent.EndParamKeyEdit(commit = true))
                        dragging = null
                    },
                    onDragCancel = {
                        if (dragging != null) onIntent(EditorIntent.EndParamKeyEdit(commit = false))
                        dragging = null
                    },
                )
            },
    ) {
        val width = size.width
        val height = size.height
        fun x(f: Long) = pad + (width - 2 * pad) * (f.toFloat() / last.toFloat())
        fun y(v: Double) = pad + (height - 2 * pad) * (1f - ((v - spec.min) / range).toFloat())
        // The axis: the clip from first to last frame.
        drawLine(axisColor, Offset(pad, height - pad), Offset(width - pad, height - pad), strokeWidth = 1f)
        // The curve over the whole clip, sampled every few pixels.
        if (!row.isPose) {
            val path = Path()
            val steps = ((width - 2 * pad) / 4f).toInt().coerceAtLeast(8)
            for (i in 0..steps) {
                val f = (last * i.toLong()) / steps
                val py = y(ParamTracks.evaluate(keys, f, staticValue).coerceIn(spec.min, spec.max))
                if (i == 0) path.moveTo(x(f), py) else path.lineTo(x(f), py)
            }
            drawPath(path, lineColor, style = Stroke(width = 2.5f))
        }
        if (frame != null) drawLine(playheadColor, Offset(x(frame), 0f), Offset(x(frame), height), strokeWidth = 2f)
        for (key in keys) {
            val cx = x(key.frame)
            val cy = if (row.isPose) height / 2f else y(key.value.coerceIn(spec.min, spec.max))
            val r = if (key.frame == selected || key.frame == dragging?.frame) 9f else 7f
            val diamond = Path().apply {
                moveTo(cx, cy - r)
                lineTo(cx + r, cy)
                lineTo(cx, cy + r)
                lineTo(cx - r, cy)
                close()
            }
            drawPath(diamond, if (key.frame == selected) selectedColor else lineColor)
        }
    }
}

/** How the value moves on from the selected key: the three simple modes or a Bezier with draggable-by-slider handles. */
@Composable
internal fun CurveControls(title: String, key: ParamKey, onShape: (Interpolation, BezierHandle?, BezierHandle?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(0.dp), modifier = Modifier.fillMaxWidth()) {
        Text(text = "$title · key at frame ${key.frame}", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            for ((mode, label) in listOf(
                Interpolation.LINEAR to "Linear",
                Interpolation.EASE to "Ease",
                Interpolation.HOLD to "Hold",
                Interpolation.BEZIER to "Bezier",
            )) {
                FilterChip(
                    selected = key.interpolation == mode,
                    onClick = { onShape(mode, key.out, key.inn) },
                    label = { Text(label) },
                )
            }
        }
        if (key.interpolation == Interpolation.BEZIER) {
            val out = key.out ?: BezierHandle.DEFAULT
            val inn = key.inn ?: BezierHandle.DEFAULT
            HandleSlider("Out time", out.x, 0f..1f) { onShape(Interpolation.BEZIER, BezierHandle(it.toDouble(), out.y), key.inn) }
            HandleSlider("Out value", out.y, BezierHandle.MIN_Y.toFloat()..BezierHandle.MAX_Y.toFloat()) {
                onShape(Interpolation.BEZIER, BezierHandle(out.x, it.toDouble()), key.inn)
            }
            HandleSlider("In time", inn.x, 0f..1f) { onShape(Interpolation.BEZIER, key.out, BezierHandle(it.toDouble(), inn.y)) }
            HandleSlider("In value", inn.y, BezierHandle.MIN_Y.toFloat()..BezierHandle.MAX_Y.toFloat()) {
                onShape(Interpolation.BEZIER, key.out, BezierHandle(inn.x, it.toDouble()))
            }
        }
    }
}

/** A handle coordinate: moves freely while dragged and is applied (one undo step) when released. */
@Composable
private fun HandleSlider(label: String, value: Double, range: ClosedFloatingPointRange<Float>, onApply: (Float) -> Unit) {
    var shown by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(76.dp))
        Slider(
            value = shown.coerceIn(range),
            onValueChange = { shown = it },
            onValueChangeFinished = { onApply(shown) },
            valueRange = range,
            modifier = Modifier.weight(1f).semantics { contentDescription = "$label %.2f".format(shown) },
        )
        Text(text = "%.2f".format(shown), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(48.dp))
    }
}
