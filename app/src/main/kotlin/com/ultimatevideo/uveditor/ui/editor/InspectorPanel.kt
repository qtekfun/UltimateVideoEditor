package com.ultimatevideo.uveditor.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ultimatevideo.uveditor.domain.ClipGain
import com.ultimatevideo.uveditor.domain.Interpolation
import com.ultimatevideo.uveditor.domain.TitleAlignment
import com.ultimatevideo.uveditor.domain.TitleContent
import com.ultimatevideo.uveditor.domain.Transition
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Controls for the selected clip: text and style (titles), placement on the canvas (video clips
 * and titles), audio gain (media clips) and the transition into the next clip. [transitionLimit] is
 * the longest transition that fits across a cut right now.
 */
@Composable
fun InspectorPanel(
    state: EditorState,
    onIntent: (EditorIntent) -> Unit,
    modifier: Modifier = Modifier,
    transitionLimit: (Transition) -> Long = { 0L },
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

        val isVisual = state.selectedVisualClip != null
        // An animated clip shows its pose at the playhead; a fixed one its transform.
        val transform = state.selectedPose ?: clip.transform
        val title = clip.title
        if (title != null) TitleControls(title, clip.id, onIntent)
        if (isVisual) {
            KeyframeControls(state, clip.keyframes.size, onIntent)
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
        if (title == null) {
            InspectorSlider(
                label = "Volume",
                value = clip.gainDb.toFloat().coerceIn(GAIN_MIN, GAIN_MAX),
                range = GAIN_MIN..GAIN_MAX,
                readout = if (clip.gainDb <= GAIN_MIN) "mute" else "${formatDb(clip.gainDb)} dB",
                onIntent = onIntent,
            ) { onIntent(EditorIntent.UpdateGain(if (it <= GAIN_MIN) ClipGain.MIN_DB else it.toDouble())) }
        }
        TransitionControls(state, onIntent, transitionLimit)
    }
}

/**
 * Animation of the selected clip: a diamond adds or removes a keyframe at the playhead, arrows jump
 * between keyframes, and the chips choose how the pose moves on from the keyframe under the
 * playhead. Once a clip has keyframes, changing a slider at the playhead writes a keyframe there.
 */
@Composable
private fun KeyframeControls(state: EditorState, keyframeCount: Int, onIntent: (EditorIntent) -> Unit) {
    val atPlayhead = state.keyframeAtPlayhead
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = if (keyframeCount == 0) "Animation" else "Animation · $keyframeCount keyframe${if (keyframeCount == 1) "" else "s"}",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { onIntent(EditorIntent.JumpToKeyframe(forward = false)) }, enabled = keyframeCount > 0) {
            Icon(EditorIcons.SkipPrevious, contentDescription = "Previous keyframe", modifier = Modifier.size(20.dp))
        }
        IconButton(onClick = { onIntent(EditorIntent.ToggleKeyframe) }) {
            Icon(
                imageVector = if (atPlayhead != null) EditorIcons.KeyframeOn else EditorIcons.KeyframeOff,
                contentDescription = if (atPlayhead != null) "Remove the keyframe at the playhead" else "Add a keyframe at the playhead",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
        }
        IconButton(onClick = { onIntent(EditorIntent.JumpToKeyframe(forward = true)) }, enabled = keyframeCount > 0) {
            Icon(EditorIcons.SkipNext, contentDescription = "Next keyframe", modifier = Modifier.size(20.dp))
        }
        TextButton(onClick = { onIntent(EditorIntent.ClearKeyframes) }, enabled = keyframeCount > 0) { Text("Clear") }
    }
    if (atPlayhead != null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "Then", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(76.dp))
            for ((mode, label) in listOf(Interpolation.LINEAR to "Linear", Interpolation.EASE to "Ease", Interpolation.HOLD to "Hold")) {
                FilterChip(
                    selected = atPlayhead.interpolation == mode,
                    onClick = { onIntent(EditorIntent.SetKeyframeInterpolation(mode)) },
                    label = { Text(label) },
                )
            }
        }
    }
}

/** Text, size, colour, alignment and weight of a title. Typing and sliders are one undo step each. */
@Composable
private fun TitleControls(title: TitleContent, clipId: String, onIntent: (EditorIntent) -> Unit) {
    var text by remember(clipId) { mutableStateOf(title.text) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onIntent(EditorIntent.UpdateTitle(title.copy(text = it)))
        },
        label = { Text("Title text") },
        modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused) onIntent(EditorIntent.EndTitleEdit(commit = true)) },
    )
    InspectorSlider(
        label = "Size",
        value = title.sizeFraction.toFloat().coerceIn(TITLE_SIZE_MIN, TITLE_SIZE_MAX),
        range = TITLE_SIZE_MIN..TITLE_SIZE_MAX,
        readout = "${(title.sizeFraction * PERCENT).roundToInt()}%",
        onIntent = onIntent,
        finish = EditorIntent.EndTitleEdit(commit = true),
    ) { onIntent(EditorIntent.UpdateTitle(title.copy(sizeFraction = it.toDouble()))) }

    fun change(content: TitleContent) {
        onIntent(EditorIntent.UpdateTitle(content))
        onIntent(EditorIntent.EndTitleEdit(commit = true))
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = "Colour", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(76.dp))
        for (swatch in TITLE_COLORS) {
            val selected = title.colorArgb == swatch.toArgb()
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(swatch)
                    .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape)
                    .clickable { change(title.copy(colorArgb = swatch.toArgb())) }
                    .semantics { contentDescription = "Title colour ${if (selected) "selected" else ""}" },
            )
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = "Align", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(76.dp))
        for (alignment in TitleAlignment.entries) {
            TextButton(onClick = { change(title.copy(alignment = alignment)) }) {
                Text(
                    text = alignment.name.lowercase().replaceFirstChar { it.uppercase() },
                    style = if (alignment == title.alignment) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Text(text = "Bold", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 8.dp, end = 8.dp))
        Switch(checked = title.bold, onCheckedChange = { change(title.copy(bold = it)) })
    }
}

/** Add, resize or remove the crossfade between the selected clip and the one right after it. */
@Composable
private fun TransitionControls(state: EditorState, onIntent: (EditorIntent) -> Unit, transitionLimit: (Transition) -> Long) {
    val transition = state.selectedTransition
    if (transition == null && state.clipAfterSelected == null) return
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Crossfade to next clip",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        if (transition == null) {
            TextButton(onClick = { onIntent(EditorIntent.AddTransition) }) { Text("Add") }
        } else {
            TextButton(onClick = { onIntent(EditorIntent.RemoveTransition) }) { Text("Remove") }
        }
    }
    if (transition == null) return
    val limit = maxOf(transitionLimit(transition), transition.durationFrames)
    var frames by remember(transition.id, transition.durationFrames) { mutableFloatStateOf(transition.durationFrames.toFloat()) }
    val seconds = frames.toDouble() * state.fps.den / state.fps.num
    val readout = "${(seconds * 100).roundToInt() / 100.0} s"
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text = "Length", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(76.dp))
        if (limit > Transition.MIN_DURATION_FRAMES) {
            Slider(
                value = frames,
                onValueChange = { frames = it },
                onValueChangeFinished = { onIntent(EditorIntent.SetTransitionDuration(frames.roundToLong())) },
                valueRange = Transition.MIN_DURATION_FRAMES.toFloat()..limit.toFloat(),
                modifier = Modifier.weight(1f).semantics { contentDescription = "Crossfade length $readout" },
            )
        } else {
            Box(modifier = Modifier.weight(1f))
        }
        Text(text = readout, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(60.dp))
    }
    if (limit <= Transition.MIN_DURATION_FRAMES) {
        Text(
            text = "No extra footage around the cut allows a longer crossfade",
            style = MaterialTheme.typography.bodySmall,
        )
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
    finish: EditorIntent = EditorIntent.EndAppearanceEdit(commit = true),
    onChange: (Float) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(76.dp))
        Slider(
            value = value,
            onValueChange = onChange,
            onValueChangeFinished = { onIntent(finish) },
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
private const val TITLE_SIZE_MIN = 0.02f
private const val TITLE_SIZE_MAX = 0.3f

private val TITLE_COLORS = listOf(
    Color(0xFFFFFFFF),
    Color(0xFFFFD60A),
    Color(0xFFFF453A),
    Color(0xFF32D74B),
    Color(0xFF0A84FF),
    Color(0xFF000000),
)
