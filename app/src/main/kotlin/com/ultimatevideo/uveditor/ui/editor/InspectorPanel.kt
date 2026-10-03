package com.ultimatevideo.uveditor.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ultimatevideo.uveditor.domain.ClipGain
import kotlin.math.roundToInt

/** Sliders for the selected clip: placement on the canvas (video clips) and audio gain (any clip). */
@Composable
fun InspectorPanel(
    state: EditorState,
    onIntent: (EditorIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val clip = state.selectedClip
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (clip == null) "Select a clip to adjust it" else "Clip appearance",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onIntent(EditorIntent.ResetAppearance) }, enabled = clip != null) { Text("Reset") }
            TextButton(onClick = { onIntent(EditorIntent.ToggleInspector) }) { Text("Done") }
        }
        if (clip == null) return@Column

        val isVideo = state.selectedVideoClip != null
        val transform = clip.transform
        if (isVideo) {
            InspectorSlider(
                label = "Position X",
                value = transform.positionX.toFloat(),
                range = -state.canvasWidth.toFloat()..state.canvasWidth.toFloat(),
                readout = "${transform.positionX.roundToInt()} px",
                onIntent = onIntent,
            ) { onIntent(EditorIntent.UpdateTransform(transform.copy(positionX = it.toDouble()))) }
            InspectorSlider(
                label = "Position Y",
                value = transform.positionY.toFloat(),
                range = -state.canvasHeight.toFloat()..state.canvasHeight.toFloat(),
                readout = "${transform.positionY.roundToInt()} px",
                onIntent = onIntent,
            ) { onIntent(EditorIntent.UpdateTransform(transform.copy(positionY = it.toDouble()))) }
            InspectorSlider(
                label = "Scale",
                value = transform.scaleX.toFloat().coerceIn(SCALE_MIN, SCALE_MAX),
                range = SCALE_MIN..SCALE_MAX,
                readout = "${(transform.scaleX * PERCENT).roundToInt()}%",
                onIntent = onIntent,
            ) { onIntent(EditorIntent.UpdateTransform(transform.copy(scaleX = it.toDouble(), scaleY = it.toDouble()))) }
            InspectorSlider(
                label = "Rotation",
                value = transform.rotationDegrees.toFloat().coerceIn(-ROTATION_LIMIT, ROTATION_LIMIT),
                range = -ROTATION_LIMIT..ROTATION_LIMIT,
                readout = "${transform.rotationDegrees.roundToInt()}°",
                onIntent = onIntent,
            ) { onIntent(EditorIntent.UpdateTransform(transform.copy(rotationDegrees = it.toDouble()))) }
            InspectorSlider(
                label = "Opacity",
                value = transform.opacity.toFloat(),
                range = 0f..1f,
                readout = "${(transform.opacity * PERCENT).roundToInt()}%",
                onIntent = onIntent,
            ) { onIntent(EditorIntent.UpdateTransform(transform.copy(opacity = it.toDouble()))) }
        }
        InspectorSlider(
            label = "Volume",
            value = clip.gainDb.toFloat().coerceIn(GAIN_MIN, GAIN_MAX),
            range = GAIN_MIN..GAIN_MAX,
            readout = if (clip.gainDb <= GAIN_MIN) "mute" else "${formatDb(clip.gainDb)} dB",
            onIntent = onIntent,
        ) { onIntent(EditorIntent.UpdateGain(if (it <= GAIN_MIN) ClipGain.MIN_DB else it.toDouble())) }
    }
}

/**
 * One slider row. Dragging shows the value live; releasing commits it as one undo step.
 * [onChange] receives the new slider value.
 */
@Composable
private fun InspectorSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    readout: String,
    onIntent: (EditorIntent) -> Unit,
    onChange: (Float) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(76.dp))
        Slider(
            value = value,
            onValueChange = onChange,
            onValueChangeFinished = { onIntent(EditorIntent.EndAppearanceEdit(commit = true)) },
            valueRange = range,
            modifier = Modifier.weight(1f).semantics { contentDescription = "$label $readout" },
        )
        Text(text = readout, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(60.dp))
    }
}

private fun formatDb(db: Double): String {
    val tenths = (db * 10).roundToInt()
    return if (tenths % 10 == 0) "${tenths / 10}" else "${tenths / 10}.${kotlin.math.abs(tenths % 10)}"
}

// Slider ranges are narrower than what the domain allows, so a gesture can still go further.
private const val SCALE_MIN = 0.1f
private const val SCALE_MAX = 4f
private const val ROTATION_LIMIT = 180f
private const val GAIN_MIN = -60f
private const val GAIN_MAX = 12f
private const val PERCENT = 100.0
