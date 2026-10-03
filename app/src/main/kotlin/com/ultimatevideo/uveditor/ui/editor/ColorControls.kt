package com.ultimatevideo.uveditor.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ultimatevideo.uveditor.domain.Effect
import com.ultimatevideo.uveditor.domain.EffectType
import com.ultimatevideo.uveditor.domain.GradeCurve
import com.ultimatevideo.uveditor.domain.GradeCurves
import kotlin.math.roundToInt

/** What the colour section needs from the look library; null where no library is provided (looks are hidden). */
internal data class LookActions(
    val state: LookLibraryState,
    val save: (String, Effect) -> Unit,
    val delete: (String) -> Unit,
    val copy: (Effect) -> Unit,
    val clearError: () -> Unit,
)

internal val LocalLookActions = staticCompositionLocalOf<LookActions?> { null }

private val GRADE_DEFAULTS = EffectType.COLOR_GRADE.defaults

// Indices into the colour grade's values; see render/grade_math.h.
private const val LIFT = 0
private const val GAMMA = 4
private const val GAIN = 8
private const val OFFSET = 12

/**
 * The editor of a colour grade effect: looks, lift / gamma / gain wheels with master sliders, the primary
 * sliders and the tone curves. Every drag is shown live and committed as one undo step when released
 * (`EndFxEdit`), like the other effect sliders.
 */
@Composable
internal fun ColorGradeEditor(effect: Effect, onIntent: (EditorIntent) -> Unit) {
    val finish = EditorIntent.EndFxEdit(commit = true)
    fun change(index: Int, value: Double) {
        onIntent(EditorIntent.UpdateGrade(effect.id, effect.values.toMutableList().also { it[index] = value }, effect.curves))
    }
    LookRow(effect, onIntent)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        ColorWheel("Lift", effect, LIFT, onIntent, Modifier.weight(1f))
        ColorWheel("Gamma", effect, GAMMA, onIntent, Modifier.weight(1f))
        ColorWheel("Gain", effect, GAIN, onIntent, Modifier.weight(1f))
    }
    for (channel in 0 until 3) {
        val index = OFFSET + channel
        val param = EffectType.COLOR_GRADE.params[index]
        GradeSlider(param.name, effect.values[index], param.min, param.max, onIntent, finish) { change(index, it) }
    }
    for (index in 15..20) {
        val param = EffectType.COLOR_GRADE.params[index]
        GradeSlider(param.name, effect.values[index], param.min, param.max, onIntent, finish) { change(index, it) }
    }
    CurvesEditor(effect, onIntent)
    TextButton(
        onClick = {
            onIntent(EditorIntent.UpdateGrade(effect.id, GRADE_DEFAULTS, null))
            onIntent(finish)
        },
        enabled = effect.values != GRADE_DEFAULTS || effect.curves != null,
    ) { Text("Reset colour grade") }
}

@Composable
private fun GradeSlider(
    label: String,
    value: Double,
    min: Double,
    max: Double,
    onIntent: (EditorIntent) -> Unit,
    finish: EditorIntent,
    onChange: (Double) -> Unit,
) {
    InspectorSlider(
        label = label,
        value = value.toFloat(),
        range = min.toFloat()..max.toFloat(),
        readout = readout(value, max - min),
        onIntent = onIntent,
        finish = finish,
    ) { onChange(it.toDouble()) }
}

private fun readout(value: Double, span: Double): String =
    if (span <= 2.0) "%.2f".format(value) else "${value.roundToInt()}"

// --- looks, copy and paste ---------------------------------------------------------------

@Composable
private fun LookRow(effect: Effect, onIntent: (EditorIntent) -> Unit) {
    val actions = LocalLookActions.current ?: return
    var saving by remember { mutableStateOf(false) }
    var browsing by remember { mutableStateOf(false) }
    val clipboard = actions.state.clipboard
    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { browsing = true }) { Text("Looks · ${actions.state.looks.size}") }
        TextButton(onClick = { saving = true }) { Text("Save look") }
        TextButton(onClick = { actions.copy(effect) }) { Text("Copy grade") }
        TextButton(
            onClick = { clipboard?.let { onIntent(EditorIntent.ApplyGrade(it.values, it.curves)) } },
            enabled = clipboard != null,
        ) { Text("Paste grade") }
    }
    if (saving) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { saving = false },
            title = { Text("Save look") },
            text = {
                Column {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
                    actions.state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        actions.save(name, effect)
                        saving = false
                    },
                    enabled = name.isNotBlank(),
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { saving = false }) { Text("Cancel") } },
        )
    }
    if (browsing) {
        AlertDialog(
            onDismissRequest = { browsing = false },
            title = { Text("Looks") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (actions.state.looks.isEmpty()) {
                        Text("No looks yet. Grade a clip and choose Save look.", style = MaterialTheme.typography.bodyMedium)
                    }
                    for (look in actions.state.looks) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text(look.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            TextButton(
                                onClick = {
                                    onIntent(EditorIntent.ApplyGrade(look.values, look.curves))
                                    browsing = false
                                },
                                modifier = Modifier.semantics { contentDescription = "Apply look ${look.name}" },
                            ) { Text("Apply") }
                            TextButton(
                                onClick = { actions.delete(look.id) },
                                modifier = Modifier.semantics { contentDescription = "Delete look ${look.name}" },
                            ) { Text("Delete") }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { browsing = false }) { Text("Close") } },
        )
    }
}

// --- wheels ---------------------------------------------------------------------------------

private val WHEEL_RING = listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)

@Composable
private fun ColorWheel(title: String, effect: Effect, first: Int, onIntent: (EditorIntent) -> Unit, modifier: Modifier) {
    val current by rememberUpdatedState(effect)
    val (storedX, storedY) = WheelMath.toPuck(effect.values[first], effect.values[first + 1], effect.values[first + 2])
    var dragging by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    val (puckX, puckY) = dragging ?: (storedX to storedY)
    val finish = EditorIntent.EndFxEdit(commit = true)

    fun push(x: Double, y: Double) {
        val (r, g, b) = WheelMath.toRgb(x, y)
        val values = current.values.toMutableList().also {
            it[first] = r
            it[first + 1] = g
            it[first + 2] = b
        }
        onIntent(EditorIntent.UpdateGrade(current.id, values, current.curves))
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Text(title, style = MaterialTheme.typography.labelMedium)
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .semantics {
                    contentDescription = "$title wheel, red %.2f green %.2f blue %.2f".format(
                        effect.values[first], effect.values[first + 1], effect.values[first + 2],
                    )
                }
                .pointerInput(first) {
                    detectDragGestures(
                        onDragStart = { o ->
                            val p = WheelMath.fromTouch(o.x, o.y, size.width.toFloat())
                            dragging = p
                            push(p.first, p.second)
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val p = WheelMath.fromTouch(change.position.x, change.position.y, size.width.toFloat())
                            dragging = p
                            push(p.first, p.second)
                        },
                        onDragEnd = {
                            dragging = null
                            onIntent(finish)
                        },
                        onDragCancel = {
                            dragging = null
                            onIntent(finish)
                        },
                    )
                }
                .pointerInput(first) {
                    detectTapGestures(
                        onDoubleTap = {
                            push(0.0, 0.0)
                            onIntent(finish)
                        },
                        onTap = { o ->
                            val p = WheelMath.fromTouch(o.x, o.y, size.width.toFloat())
                            push(p.first, p.second)
                            onIntent(finish)
                        },
                    )
                },
        ) {
            val radius = size.minDimension / 2f
            val centre = Offset(size.width / 2f, size.height / 2f)
            drawCircle(brush = Brush.sweepGradient(WHEEL_RING, centre), radius = radius, center = centre, alpha = 0.75f)
            drawCircle(
                brush = Brush.radialGradient(listOf(Color(0xFF303030), Color.Transparent), centre, radius),
                radius = radius,
                center = centre,
            )
            drawCircle(color = Color.White.copy(alpha = 0.5f), radius = radius, center = centre, style = Stroke(width = 2f))
            val puck = Offset(centre.x + (puckX * radius).toFloat(), centre.y + (puckY * radius).toFloat())
            drawCircle(color = Color.Black, radius = 9f, center = puck)
            drawCircle(color = Color.White, radius = 7f, center = puck)
        }
        val master = effect.values[first + 3]
        Slider(
            value = master.toFloat(),
            onValueChange = { v ->
                onIntent(EditorIntent.UpdateGrade(current.id, current.values.toMutableList().also { it[first + 3] = v.toDouble() }, current.curves))
            },
            onValueChangeFinished = { onIntent(finish) },
            valueRange = -1f..1f,
            modifier = Modifier.semantics { contentDescription = "$title master %.2f".format(master) },
        )
        TextButton(
            onClick = {
                onIntent(
                    EditorIntent.UpdateGrade(
                        current.id,
                        current.values.toMutableList().also { for (i in 0..3) it[first + i] = 0.0 },
                        current.curves,
                    ),
                )
                onIntent(finish)
            },
            enabled = (0..3).any { effect.values[first + it] != 0.0 },
            modifier = Modifier.semantics { contentDescription = "Reset $title" },
        ) { Text("Reset") }
    }
}

// --- curves ---------------------------------------------------------------------------------

private val CURVE_COLOURS = listOf(Color.White, Color(0xFFFF5A52), Color(0xFF4CD964), Color(0xFF4DA3FF))
private val CURVE_NAMES = listOf("Master", "Red", "Green", "Blue")
private const val GRAB_RADIUS = 0.08
private const val DRAW_STEPS = 64

private fun GradeCurves.channel(index: Int): GradeCurve = when (index) {
    0 -> master
    1 -> red
    2 -> green
    else -> blue
}

private fun GradeCurves.with(index: Int, curve: GradeCurve): GradeCurves = when (index) {
    0 -> copy(master = curve)
    1 -> copy(red = curve)
    2 -> copy(green = curve)
    else -> copy(blue = curve)
}

@Composable
private fun CurvesEditor(effect: Effect, onIntent: (EditorIntent) -> Unit) {
    val current by rememberUpdatedState(effect)
    var channel by remember { mutableIntStateOf(0) }
    var working by remember { mutableStateOf<GradeCurve?>(null) }
    val curves = effect.curves ?: GradeCurves.IDENTITY
    val curve = working ?: curves.channel(channel)
    val finish = EditorIntent.EndFxEdit(commit = true)

    fun push(next: GradeCurve) {
        val updated = (current.curves ?: GradeCurves.IDENTITY).with(channel, next)
        onIntent(EditorIntent.UpdateGrade(current.id, current.values, updated))
    }

    Text("Curves", style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
        for (i in CURVE_NAMES.indices) {
            FilterChip(selected = channel == i, onClick = { channel = i }, label = { Text(CURVE_NAMES[i]) })
        }
    }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .padding(horizontal = 24.dp)
            .semantics { contentDescription = "${CURVE_NAMES[channel]} curve, ${curve.points.size} points. Tap to add a point, long press a point to remove it." }
            .pointerInput(channel) {
                var index: Int? = null
                detectDragGestures(
                    onDragStart = { o ->
                        val x = o.x / size.width.toDouble()
                        val y = 1.0 - o.y / size.height.toDouble()
                        val base = current.curves?.channel(channel) ?: GradeCurve()
                        index = CurveEdit.nearest(base, x, y, GRAB_RADIUS)
                        working = base
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val i = index
                        val base = working
                        if (i != null && base != null) {
                            val moved = CurveEdit.move(
                                base,
                                i,
                                change.position.x / size.width.toDouble(),
                                1.0 - change.position.y / size.height.toDouble(),
                            )
                            working = moved
                            push(moved)
                        }
                    },
                    onDragEnd = {
                        working = null
                        index = null
                        onIntent(finish)
                    },
                    onDragCancel = {
                        working = null
                        index = null
                        onIntent(finish)
                    },
                )
            }
            .pointerInput(channel) {
                detectTapGestures(
                    onTap = { o ->
                        val base = current.curves?.channel(channel) ?: GradeCurve()
                        val added = CurveEdit.add(base, o.x / size.width.toDouble(), 1.0 - o.y / size.height.toDouble())
                        if (added != null) {
                            push(added)
                            onIntent(finish)
                        }
                    },
                    onLongPress = { o ->
                        val base = current.curves?.channel(channel) ?: GradeCurve()
                        val hit = CurveEdit.nearest(base, o.x / size.width.toDouble(), 1.0 - o.y / size.height.toDouble(), GRAB_RADIUS)
                        if (hit != null) {
                            val removed = CurveEdit.remove(base, hit)
                            if (removed != base) {
                                push(removed)
                                onIntent(finish)
                            }
                        }
                    },
                )
            },
    ) {
        val w = size.width
        val h = size.height
        drawRect(Color(0xFF1B1B1B))
        for (i in 1..3) {
            val f = i / 4f
            drawLine(Color(0x33FFFFFF), Offset(w * f, 0f), Offset(w * f, h))
            drawLine(Color(0x33FFFFFF), Offset(0f, h * f), Offset(w, h * f))
        }
        drawLine(Color(0x55FFFFFF), Offset(0f, h), Offset(w, 0f))
        val line = Path()
        for (s in 0..DRAW_STEPS) {
            val x = s / DRAW_STEPS.toDouble()
            val p = Offset((x * w).toFloat(), ((1.0 - curve.valueAt(x)) * h).toFloat())
            if (s == 0) line.moveTo(p.x, p.y) else line.lineTo(p.x, p.y)
        }
        drawPath(line, CURVE_COLOURS[channel], style = Stroke(width = 4f))
        for (p in curve.points) {
            val centre = Offset((p.x * w).toFloat(), ((1.0 - p.y) * h).toFloat())
            drawCircle(Color.Black, 11f, centre)
            drawCircle(CURVE_COLOURS[channel], 8f, centre)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            onClick = {
                push(GradeCurve())
                onIntent(finish)
            },
            enabled = !curve.isIdentity,
        ) { Text("Reset ${CURVE_NAMES[channel].lowercase()} curve") }
        Box(modifier = Modifier.weight(1f))
    }
}
