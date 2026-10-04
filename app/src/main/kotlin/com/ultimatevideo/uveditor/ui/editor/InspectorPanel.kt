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
import com.ultimatevideo.uveditor.domain.Clip
import com.ultimatevideo.uveditor.domain.ClipGain
import com.ultimatevideo.uveditor.domain.Interpolation
import com.ultimatevideo.uveditor.domain.ParamIds
import com.ultimatevideo.uveditor.domain.ParamKey
import com.ultimatevideo.uveditor.domain.PoseParams
import androidx.compose.runtime.CompositionLocalProvider
import com.ultimatevideo.uveditor.domain.SourceColorSpace
import com.ultimatevideo.uveditor.domain.SpeedLimits
import com.ultimatevideo.uveditor.domain.SpeedRamps
import com.ultimatevideo.uveditor.domain.TitleAlignment
import com.ultimatevideo.uveditor.domain.TitleContent
import com.ultimatevideo.uveditor.domain.Transition
import com.ultimatevideo.uveditor.domain.isFreeze
import com.ultimatevideo.uveditor.domain.speed
import kotlin.math.log2
import kotlin.math.pow
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
    // The controls show keyframed values as they are at the playhead (volume, pan, EQ and effect values).
    val clip = state.displayedClip
    CompositionLocalProvider(LocalParamKeys provides { id: String -> state.keyUi(id) }) {
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
            if (clip.hasMedia) {
                val detected = SourceColorSpace.fromId(state.assets.firstOrNull { it.id == clip.assetId }?.colorSpace)
                ClipColorControls(detected, clip.colorOverride, state.colorSpace, onIntent)
                if (clip.still == null) StabiliseControls(clip, if (state.stab.clipId == clip.id) state.stab else StabUiState(), onIntent)
            }
            FxControls(clip.fx, onIntent)
            TrackControls(clip.id, state.track, onIntent)
        }
        if (clip.hasMedia) SpeedControls(state, clip, isVisual, onIntent)
        if (clip.hasMedia) {
            InspectorSlider(
                label = "Volume",
                value = clip.gainDb.toFloat().coerceIn(GAIN_MIN, GAIN_MAX),
                range = GAIN_MIN..GAIN_MAX,
                readout = if (clip.gainDb <= GAIN_MIN) "mute" else "${formatDb(clip.gainDb)} dB",
                onIntent = onIntent,
                paramId = ParamIds.GAIN_DB,
            ) { onIntent(EditorIntent.UpdateGain(if (it <= GAIN_MIN) ClipGain.MIN_DB else it.toDouble())) }
            AudioControls(state, clip, onIntent)
        }
        KeyframeLane(state, onIntent)
        TransitionControls(state, onIntent, transitionLimit)
    }
    }
}

/**
 * Speed of the selected media clip: presets and a slider for a constant speed (0.1x to 100x), a speed curve (presets and a graphical editor)
 * that speeds it up or slows it down over the clip, reverse, and (for video) a freeze frame at the
 * playhead. Changing the speed changes the clip's length and moves the clips after it.
 */
@Composable
private fun SpeedControls(state: EditorState, clip: Clip, isVisual: Boolean, onIntent: (EditorIntent) -> Unit) {
    val freeze = clip.isFreeze
    val speed = clip.speed
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = if (freeze) "Freeze frame" else "Speed",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        if (!freeze) Text(text = formatSpeed(speed), style = MaterialTheme.typography.titleSmall)
    }
    if (!freeze) {
        val rampless = clip.speedRamp.isEmpty()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            for (preset in SPEED_PRESETS) {
                FilterChip(
                    selected = rampless && kotlin.math.abs(speed - preset) < SPEED_MATCH,
                    onClick = { onIntent(speedIntent(preset)) },
                    label = { Text(formatSpeed(preset)) },
                )
            }
        }
        var logSpeed by remember(clip.id, clip.durationFrames) { mutableFloatStateOf(log2(speed).toFloat()) }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(text = "Custom", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(76.dp))
            Slider(
                value = logSpeed.coerceIn(LOG_SPEED_MIN, LOG_SPEED_MAX),
                onValueChange = { logSpeed = it },
                onValueChangeFinished = { onIntent(speedIntent(2.0.pow(logSpeed.toDouble()))) },
                valueRange = LOG_SPEED_MIN..LOG_SPEED_MAX,
                modifier = Modifier.weight(1f).semantics { contentDescription = "Speed ${formatSpeed(2.0.pow(logSpeed.toDouble()))}" },
            )
            Text(text = formatSpeed(2.0.pow(logSpeed.toDouble())), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(60.dp))
        }
        val shape = rampShapeOf(clip)
        Text(text = "Speed curve", style = MaterialTheme.typography.labelMedium)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        ) {
            for ((option, label) in RAMP_SHAPES) {
                FilterChip(
                    selected = shape == option,
                    onClick = { onIntent(EditorIntent.SetSpeedRamp(option)) },
                    label = { Text(label) },
                )
            }
        }
        var curveOpen by remember(clip.id) { mutableStateOf(false) }
        TextButton(onClick = { curveOpen = !curveOpen }) { Text(if (curveOpen) "Hide curve editor" else "Edit curve") }
        if (curveOpen && clip.durationFrames >= 2) {
            SpeedCurveEditor(
                keys = clip.speedRamp,
                durationFrames = clip.durationFrames,
                onCommit = { onIntent(EditorIntent.SetSpeedKeys(it)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (isVisual && (speed < 1.0 || !rampless)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Smooth slow motion", style = MaterialTheme.typography.labelMedium)
                    Text(
                        text = "Makes in-between frames where the clip plays slower than real time",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = clip.smoothSlowMo,
                    onCheckedChange = { onIntent(EditorIntent.ToggleSmoothSlowMo) },
                    modifier = Modifier.semantics { contentDescription = "Smooth slow motion" },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "Reverse", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(76.dp))
            Switch(checked = clip.reverse, onCheckedChange = { onIntent(EditorIntent.ToggleReverse) })
        }
        if (speed > MAX_AUDIBLE_SPEED || speed < MIN_AUDIBLE_SPEED) {
            Text(text = "Sound is muted outside 0.25x to 4x", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (isVisual) {
        TextButton(
            onClick = { onIntent(EditorIntent.FreezeFrame) },
            enabled = state.selectedClipVisible,
        ) { Text("Freeze frame at the playhead") }
    }
}

/** The preset that [clip]'s ramp is, null for a ramp that is none of them. */
private fun rampShapeOf(clip: Clip): SpeedRampShape? = when (clip.speedRamp) {
    emptyList<com.ultimatevideo.uveditor.domain.SpeedKey>() -> SpeedRampShape.NONE
    SpeedRamps.easeIn(clip.durationFrames) -> SpeedRampShape.EASE_IN
    SpeedRamps.easeOut(clip.durationFrames) -> SpeedRampShape.EASE_OUT
    SpeedRamps.bell(clip.durationFrames) -> SpeedRampShape.BELL
    SpeedRamps.easeInSmooth(clip.durationFrames) -> SpeedRampShape.EASE_IN_SMOOTH
    SpeedRamps.easeOutSmooth(clip.durationFrames) -> SpeedRampShape.EASE_OUT_SMOOTH
    SpeedRamps.montage(clip.durationFrames) -> SpeedRampShape.MONTAGE
    SpeedRamps.hero(clip.durationFrames) -> SpeedRampShape.HERO
    SpeedRamps.bullet(clip.durationFrames) -> SpeedRampShape.BULLET
    else -> null
}

/** A speed as the whole-percent ratio the domain stores: 1.25 is 125/100. Clamped to what clips accept. */
private fun speedIntent(speed: Double): EditorIntent {
    val percent = (speed * PERCENT).roundToLong().coerceIn(
        SpeedLimits.MIN_NUM * PERCENT.toLong() / SpeedLimits.MIN_DEN,
        SpeedLimits.MAX * PERCENT.toLong(),
    )
    return EditorIntent.SetSpeed(percent, PERCENT.toLong())
}

internal fun formatSpeed(speed: Double): String {
    val rounded = (speed * PERCENT).roundToInt()
    return if (rounded % PERCENT.toInt() == 0) "${rounded / PERCENT.toInt()}x" else "${(rounded / PERCENT).toString().trimEnd('0').trimEnd('.')}x"
}

private val SPEED_PRESETS = listOf(0.25, 0.5, 1.0, 2.0, 4.0, 10.0)
private val RAMP_SHAPES = listOf(
    SpeedRampShape.NONE to "None",
    SpeedRampShape.EASE_IN to "Ease in",
    SpeedRampShape.EASE_OUT to "Ease out",
    SpeedRampShape.EASE_IN_SMOOTH to "Ease in (round)",
    SpeedRampShape.EASE_OUT_SMOOTH to "Ease out (round)",
    SpeedRampShape.BELL to "Bell",
    SpeedRampShape.MONTAGE to "Montage",
    SpeedRampShape.HERO to "Hero",
    SpeedRampShape.BULLET to "Bullet time",
)
private const val SPEED_MATCH = 0.01
private val LOG_SPEED_MIN = log2(0.1).toFloat()
private val LOG_SPEED_MAX = log2(SpeedLimits.MAX.toDouble()).toFloat()

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
            for ((mode, label) in listOf(
                Interpolation.LINEAR to "Linear",
                Interpolation.EASE to "Ease",
                Interpolation.HOLD to "Hold",
                Interpolation.BEZIER to "Bezier",
            )) {
                FilterChip(
                    selected = atPlayhead.interpolation == mode,
                    onClick = { onIntent(EditorIntent.SetKeyframeInterpolation(mode)) },
                    label = { Text(label) },
                )
            }
        }
        if (atPlayhead.interpolation == Interpolation.BEZIER) {
            // The handles of a pose keyframe live on the joint keyframe; any pose parameter id addresses it.
            CurveControls(
                title = "Pose",
                key = ParamKey(atPlayhead.frame, 0.0, atPlayhead.interpolation, atPlayhead.out, atPlayhead.inn),
                onShape = { mode, out, inn ->
                    onIntent(EditorIntent.SetParamKeyShape(PoseParams.POSITION_X.id, atPlayhead.frame, mode, out, inn))
                },
            )
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
internal fun InspectorSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    readout: String,
    onIntent: (EditorIntent) -> Unit,
    finish: EditorIntent = EditorIntent.EndAppearanceEdit(commit = true),
    /** The parameter this slider drives, when it can be keyframed: a diamond appears at the end of the row. */
    paramId: String? = null,
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
        val keyUi = paramId?.let { LocalParamKeys.current(it) }
        if (keyUi != null) KeyDiamond(keyUi, onIntent)
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
