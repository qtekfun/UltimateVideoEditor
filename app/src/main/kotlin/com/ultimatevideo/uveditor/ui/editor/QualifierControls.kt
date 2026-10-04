package com.ultimatevideo.uveditor.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ultimatevideo.uveditor.domain.Effect
import com.ultimatevideo.uveditor.domain.EffectType
import com.ultimatevideo.uveditor.domain.ParamIds
import com.ultimatevideo.uveditor.domain.Qualifier
import kotlin.math.roundToInt

/** The eyedropper's state for the qualifier editor, provided by the editor screen (it lives in the editor state). */
internal val LocalQualifierPick = staticCompositionLocalOf { QualifierPickState() }

/**
 * Editor of an [EffectType.QUALIFIER] effect, replacing its 14 generic sliders with the groups of a colourist's
 * qualifier: an eyedropper and the two switches on top, then Hue, Saturation and Luma ranges (each with a softness),
 * and the correction applied inside the selection. Sliders show live and make one undo step each when released,
 * like every effect slider; ranges keep their minimum below their maximum ([Qualifier.withBound]).
 */
@Composable
internal fun QualifierEditor(effect: Effect, onIntent: (EditorIntent) -> Unit) {
    require(effect.type == EffectType.QUALIFIER) { "not a qualifier: ${effect.type}" }
    val values = effect.values
    val pick = LocalQualifierPick.current
    val finish = EditorIntent.EndFxEdit(commit = true)
    fun set(index: Int, v: Float) = onIntent(EditorIntent.UpdateEffect(effect.id, values.toMutableList().also { it[index] = v.toDouble() }))
    fun setBound(minIndex: Int, maxIndex: Int, index: Int, v: Float) =
        onIntent(EditorIntent.UpdateEffect(effect.id, Qualifier.withBound(values, minIndex, maxIndex, index, v.toDouble())))
    fun toggle(index: Int) {
        onIntent(EditorIntent.UpdateEffect(effect.id, values.toMutableList().also { it[index] = if (it[index] > 0.5) 0.0 else 1.0 }))
        onIntent(finish)
    }

    @Composable
    fun slider(index: Int, label: String, bound: Pair<Int, Int>? = null) {
        val param = EffectType.QUALIFIER.params[index]
        InspectorSlider(
            label = label,
            value = values[index].toFloat(),
            range = param.min.toFloat()..param.max.toFloat(),
            readout = percent(values[index], param.max - param.min),
            onIntent = onIntent,
            finish = finish,
            paramId = ParamIds.fx(effect.id, index),
        ) { v -> if (bound != null) setBound(bound.first, bound.second, index, v) else set(index, v) }
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when {
            pick.effectId == effect.id -> {
                Button(
                    onClick = { onIntent(QualifierIntent.Cancel) },
                    modifier = Modifier.semantics { contentDescription = "Tap the picture to choose a colour; tap to cancel" },
                ) { Text(if (pick.busy) "Reading…" else "Tap the picture…") }
            }
            else -> OutlinedButton(
                onClick = { onIntent(QualifierIntent.Arm(effect.id)) },
                enabled = !pick.armed,
                modifier = Modifier.semantics { contentDescription = "Pick the key colour from the picture" },
            ) { Text("Pick colour") }
        }
        FilterChip(
            selected = values[Qualifier.SHOW_MATTE] > 0.5,
            onClick = { toggle(Qualifier.SHOW_MATTE) },
            label = { Text("Show matte") },
        )
        FilterChip(
            selected = values[Qualifier.INVERT] > 0.5,
            onClick = { toggle(Qualifier.INVERT) },
            label = { Text("Invert") },
        )
    }

    QualifierSection("Hue")
    HueBand(centre = values[Qualifier.HUE], halfWidth = values[Qualifier.HUE_WIDTH], softness = values[Qualifier.HUE_SOFT])
    slider(Qualifier.HUE, "Centre")
    slider(Qualifier.HUE_WIDTH, "Width")
    slider(Qualifier.HUE_SOFT, "Softness")

    QualifierSection("Saturation")
    slider(Qualifier.SAT_MIN, "From", Qualifier.SAT_MIN to Qualifier.SAT_MAX)
    slider(Qualifier.SAT_MAX, "To", Qualifier.SAT_MIN to Qualifier.SAT_MAX)
    slider(Qualifier.SAT_SOFT, "Softness")

    QualifierSection("Luma")
    slider(Qualifier.LUMA_MIN, "From", Qualifier.LUMA_MIN to Qualifier.LUMA_MAX)
    slider(Qualifier.LUMA_MAX, "To", Qualifier.LUMA_MIN to Qualifier.LUMA_MAX)
    slider(Qualifier.LUMA_SOFT, "Softness")

    QualifierSection("Correct inside the selection")
    slider(Qualifier.HUE_SHIFT, "Hue shift")
    slider(Qualifier.SAT_GAIN, "Saturation")
    slider(Qualifier.LIGHTNESS, "Lightness")
    TextButton(
        onClick = {
            onIntent(
                EditorIntent.UpdateEffect(
                    effect.id,
                    values.toMutableList().also {
                        it[Qualifier.HUE_SHIFT] = 0.0
                        it[Qualifier.SAT_GAIN] = 1.0
                        it[Qualifier.LIGHTNESS] = 0.0
                    },
                ),
            )
            onIntent(finish)
        },
    ) { Text("Reset correction") }
}

@Composable
private fun QualifierSection(title: String) {
    Text(text = title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
}

/** The colour wheel as a strip with the selected hue range marked on it (it wraps around the right edge). */
@Composable
private fun HueBand(centre: Double, halfWidth: Double, softness: Double) {
    val outline = MaterialTheme.colorScheme.onSurface
    val description = "Selected hues: centre ${percent(centre, 1.0)}, width ${percent(halfWidth, 0.5)}"
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(18.dp)
            .semantics { contentDescription = description },
    ) {
        val colours = List(HUE_STOPS + 1) { Color.hsv(it * 360f / HUE_STOPS, 1f, 1f) }
        drawRect(Brush.horizontalGradient(colours), size = size)
        // Outside the key is dimmed; the soft edges are not drawn, only the solid core.
        val dim = Color.Black.copy(alpha = 0.55f)
        val lo = (centre - halfWidth).toFloat()
        val hi = (centre + halfWidth).toFloat()
        if (halfWidth >= 0.5) return@Canvas
        fun dimSpan(from: Float, to: Float) {
            if (to <= from) return
            drawRect(dim, Offset(from * size.width, 0f), Size((to - from) * size.width, size.height))
        }
        // The stretches outside the key are dimmed. The key wraps around the wheel: past the left edge it keeps
        // [0, hi] and [lo + 1, 1]; past the right edge [lo, 1] and [0, hi - 1].
        val dimmed = when {
            lo < 0f -> listOf(hi to (lo + 1f))
            hi > 1f -> listOf((hi - 1f) to lo)
            else -> listOf(0f to lo, hi to 1f)
        }
        for ((a, b) in dimmed) dimSpan(a.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
        val markX = (centre.toFloat().coerceIn(0f, 1f)) * size.width
        drawLine(outline, Offset(markX, 0f), Offset(markX, size.height), strokeWidth = 3f)
        if (softness > 0.0) {
            // A thin bracket under the strip marks how far the soft edges reach.
            val soft = softness.toFloat() * size.width
            drawLine(outline.copy(alpha = 0.6f), Offset((lo * size.width - soft).coerceAtLeast(0f), size.height - 1f), Offset((hi * size.width + soft).coerceAtMost(size.width), size.height - 1f), strokeWidth = 2f)
        }
    }
}

private const val HUE_STOPS = 12

private fun percent(value: Double, span: Double): String = "${(value / span * 100).roundToInt()}%"
