package com.ultimatevideo.uveditor.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ultimatevideo.uveditor.domain.BlendMode
import com.ultimatevideo.uveditor.domain.ClipFx
import com.ultimatevideo.uveditor.domain.ClipMask
import com.ultimatevideo.uveditor.domain.Effect
import com.ultimatevideo.uveditor.domain.EffectType
import com.ultimatevideo.uveditor.domain.MaskShape
import com.ultimatevideo.uveditor.domain.ParamIds
import kotlin.math.roundToInt

/**
 * Effects, blend mode and mask of the selected clip. Effects run top to bottom, so order matters
 * (blur before sharpen is not the same as the reverse). Sliders are shown live and make one undo
 * step each when released.
 */
@Composable
internal fun FxControls(fx: ClipFx, onIntent: (EditorIntent) -> Unit) {
    EffectsHeader(fx, onIntent)
    fx.effects.forEachIndexed { index, effect -> EffectRow(effect, index, fx.effects.size, onIntent) }
    BlendControls(fx.blendMode, onIntent)
    MaskControls(fx.mask, onIntent)
}

@Composable
private fun EffectsHeader(fx: ClipFx, onIntent: (EditorIntent) -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = if (fx.effects.isEmpty()) "Effects" else "Effects · ${fx.effects.size}",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        Box {
            TextButton(onClick = { menuOpen = true }, enabled = fx.effects.size < ClipFx.MAX_EFFECTS) { Text("Add") }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("LUT…") },
                    onClick = {
                        menuOpen = false
                        onIntent(EditorIntent.OpenLutPicker)
                    },
                )
                // A LUT needs a library entry, so it has its own item above instead of a default-valued row.
                for (type in EffectType.entries.filter { it != EffectType.LUT }) {
                    DropdownMenuItem(
                        text = { Text(type.label) },
                        onClick = {
                            menuOpen = false
                            onIntent(EditorIntent.AddEffect(type))
                        },
                    )
                }
            }
        }
        TextButton(onClick = { onIntent(EditorIntent.ClearFx) }, enabled = !fx.isNeutral) { Text("Clear all") }
    }
}

@Composable
private fun EffectRow(effect: Effect, index: Int, count: Int, onIntent: (EditorIntent) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        val lutName = if (effect.type == EffectType.LUT) LocalLutNames.current[effect.values[0].toInt()] ?: "missing" else null
        Text(
            text = if (effect.type == EffectType.LUT) "LUT · $lutName" else effect.type.label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = { onIntent(EditorIntent.MoveEffect(effect.id, index - 1)) },
            enabled = index > 0,
            modifier = Modifier.semantics { contentDescription = "Move ${effect.type.label} up" },
        ) { Text("Up") }
        TextButton(
            onClick = { onIntent(EditorIntent.MoveEffect(effect.id, index + 1)) },
            enabled = index < count - 1,
            modifier = Modifier.semantics { contentDescription = "Move ${effect.type.label} down" },
        ) { Text("Down") }
        TextButton(onClick = { onIntent(EditorIntent.RemoveEffect(effect.id)) }) { Text("Remove") }
    }
    if (effect.type == EffectType.COLOR_GRADE) {
        ColorGradeEditor(effect, onIntent)
        return
    }
    if (effect.type == EffectType.CHROMA_KEY) KeyColourSwatches(effect, onIntent)
    effect.type.params.forEachIndexed { i, param ->
        // The key colour is picked from the swatches; its three channels have no sliders.
        if (effect.type == EffectType.CHROMA_KEY && i < KEY_CHANNELS) return@forEachIndexed
        // The LUT is chosen in the picker; only its intensity is a slider.
        if (effect.type == EffectType.LUT && i == 0) return@forEachIndexed
        InspectorSlider(
            label = param.name,
            value = effect.values[i].toFloat(),
            range = param.min.toFloat()..param.max.toFloat(),
            readout = formatValue(effect.values[i], param.max - param.min),
            onIntent = onIntent,
            finish = EditorIntent.EndFxEdit(commit = true),
            paramId = ParamIds.fx(effect.id, i),
        ) { v -> onIntent(EditorIntent.UpdateEffect(effect.id, effect.values.toMutableList().also { it[i] = v.toDouble() })) }
    }
}

/** Common backdrop colours for the key; tapping one sets red, green and blue together as one step. */
@Composable
private fun KeyColourSwatches(effect: Effect, onIntent: (EditorIntent) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = "Key colour", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(76.dp))
        for ((name, rgb) in KEY_COLOURS) {
            val selected = rgb.indices.all { kotlin.math.abs(effect.values[it] - rgb[it]) < KEY_MATCH }
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color(rgb[0].toFloat(), rgb[1].toFloat(), rgb[2].toFloat()))
                    .border(
                        if (selected) 3.dp else 1.dp,
                        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        CircleShape,
                    )
                    .clickable {
                        val values = effect.values.toMutableList()
                        for (c in rgb.indices) values[c] = rgb[c]
                        onIntent(EditorIntent.UpdateEffect(effect.id, values))
                        onIntent(EditorIntent.EndFxEdit(commit = true))
                    }
                    .semantics { contentDescription = "Key colour $name${if (selected) ", selected" else ""}" },
            )
        }
    }
}

@Composable
private fun BlendControls(mode: BlendMode, onIntent: (EditorIntent) -> Unit) {
    Text(text = "Blend", style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
        for (candidate in BlendMode.entries) {
            FilterChip(
                selected = candidate == mode,
                onClick = { onIntent(EditorIntent.SetBlendMode(candidate)) },
                label = { Text(candidate.label) },
            )
        }
    }
}

@Composable
private fun MaskControls(mask: ClipMask?, onIntent: (EditorIntent) -> Unit) {
    fun change(next: ClipMask?) {
        onIntent(EditorIntent.UpdateMask(next))
        onIntent(EditorIntent.EndFxEdit(commit = true))
    }
    Text(text = "Mask", style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = mask == null, onClick = { change(null) }, label = { Text("Off") })
        FilterChip(
            selected = mask?.shape == MaskShape.RECTANGLE,
            onClick = { change((mask ?: ClipMask()).copy(shape = MaskShape.RECTANGLE)) },
            label = { Text("Rectangle") },
        )
        FilterChip(
            selected = mask?.shape == MaskShape.ELLIPSE,
            onClick = { change((mask ?: ClipMask()).copy(shape = MaskShape.ELLIPSE)) },
            label = { Text("Ellipse") },
        )
    }
    if (mask == null) return
    val finish = EditorIntent.EndFxEdit(commit = true)
    InspectorSlider("Centre X", mask.centerX.toFloat(), -HALF..HALF, percent(mask.centerX), onIntent, finish) {
        onIntent(EditorIntent.UpdateMask(mask.copy(centerX = it.toDouble())))
    }
    InspectorSlider("Centre Y", mask.centerY.toFloat(), -HALF..HALF, percent(mask.centerY), onIntent, finish) {
        onIntent(EditorIntent.UpdateMask(mask.copy(centerY = it.toDouble())))
    }
    InspectorSlider("Width", mask.width.toFloat().coerceAtMost(MASK_SLIDER_MAX), MASK_SLIDER_MIN..MASK_SLIDER_MAX, percent(mask.width), onIntent, finish) {
        onIntent(EditorIntent.UpdateMask(mask.copy(width = it.toDouble())))
    }
    InspectorSlider("Height", mask.height.toFloat().coerceAtMost(MASK_SLIDER_MAX), MASK_SLIDER_MIN..MASK_SLIDER_MAX, percent(mask.height), onIntent, finish) {
        onIntent(EditorIntent.UpdateMask(mask.copy(height = it.toDouble())))
    }
    InspectorSlider("Feather", mask.feather.toFloat(), 0f..ClipMask.MAX_FEATHER.toFloat(), percent(mask.feather), onIntent, finish) {
        onIntent(EditorIntent.UpdateMask(mask.copy(feather = it.toDouble())))
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Text(text = "Invert", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(76.dp))
        Switch(checked = mask.invert, onCheckedChange = { change(mask.copy(invert = it)) })
    }
}

private fun percent(fraction: Double): String = "${(fraction * PERCENT).roundToInt()}%"

/** Wide ranges read as whole numbers or one decimal, narrow ones as hundredths. */
private fun formatValue(value: Double, span: Double): String {
    val digits = if (span >= WIDE_SPAN) 1 else 2
    val scale = if (digits == 1) 10.0 else 100.0
    val rounded = (value * scale).roundToInt() / scale
    return rounded.toString()
}

private const val PERCENT = 100.0
private const val HALF = 0.5f
private const val KEY_CHANNELS = 3
private const val KEY_MATCH = 0.02
private const val WIDE_SPAN = 4.0
private const val MASK_SLIDER_MIN = 0.05f
private const val MASK_SLIDER_MAX = 1.5f

private val KEY_COLOURS = listOf(
    "green" to doubleArrayOf(0.0, 1.0, 0.0),
    "blue" to doubleArrayOf(0.0, 0.0, 1.0),
    "red" to doubleArrayOf(1.0, 0.0, 0.0),
    "white" to doubleArrayOf(1.0, 1.0, 1.0),
    "black" to doubleArrayOf(0.0, 0.0, 0.0),
)
