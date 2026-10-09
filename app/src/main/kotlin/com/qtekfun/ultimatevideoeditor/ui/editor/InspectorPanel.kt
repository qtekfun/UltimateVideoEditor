package com.qtekfun.ultimatevideoeditor.ui.editor

import androidx.compose.ui.res.pluralStringResource
import com.qtekfun.ultimatevideoeditor.R
import androidx.compose.ui.res.stringResource
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
import com.qtekfun.ultimatevideoeditor.domain.Clip
import com.qtekfun.ultimatevideoeditor.domain.ClipGain
import com.qtekfun.ultimatevideoeditor.domain.Interpolation
import com.qtekfun.ultimatevideoeditor.domain.ParamIds
import com.qtekfun.ultimatevideoeditor.domain.ParamKey
import com.qtekfun.ultimatevideoeditor.domain.PoseParams
import androidx.compose.runtime.CompositionLocalProvider
import com.qtekfun.ultimatevideoeditor.domain.SourceColorSpace
import com.qtekfun.ultimatevideoeditor.domain.SpeedLimits
import com.qtekfun.ultimatevideoeditor.domain.SpeedRamps
import com.qtekfun.ultimatevideoeditor.domain.TitleAlignment
import com.qtekfun.ultimatevideoeditor.domain.TitleContent
import com.qtekfun.ultimatevideoeditor.domain.TitleLayerEdit
import com.qtekfun.ultimatevideoeditor.ui.editor.title.TitleLayerEditor
import com.qtekfun.ultimatevideoeditor.ui.editor.title.TitleTools
import com.qtekfun.ultimatevideoeditor.domain.Transition
import com.qtekfun.ultimatevideoeditor.domain.isFreeze
import com.qtekfun.ultimatevideoeditor.domain.speed
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
    titleTools: TitleTools = TitleTools.NONE,
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
                text = if (clip == null) stringResource(R.string.ed_2b_select_a_clip_to_adjust) else stringResource(R.string.ed_2b_clip_appearance),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onIntent(EditorIntent.ResetAppearance) }, enabled = clip != null) { Text(stringResource(R.string.ed_2b_reset)) }
            TextButton(onClick = { onIntent(EditorIntent.ToggleInspector) }) { Text(stringResource(R.string.ed_2a_done)) }
        }
        if (clip == null) return@Column

        val isVisual = state.selectedVisualClip != null
        // An animated clip shows its pose at the playhead; a fixed one its transform.
        val transform = state.selectedPose ?: clip.transform
        val title = clip.title
        if (title != null) {
            if (title.isLayered) {
                val clipSeconds = clip.durationFrames * state.fps.den.toDouble() / state.fps.num
                TitleLayerEditor(title, clip.id, state.selectedTitleLayer, state.assets, clipSeconds, titleTools, onIntent)
            } else {
                TitleControls(title, clip.id, onIntent)
            }
        }
        if (isVisual) {
            KeyframeControls(state, clip.keyframes.size, onIntent)
            InspectorSlider(
                label = stringResource(R.string.ed_2b_position_x),
                value = transform.positionX.toFloat(),
                range = -state.canvasWidth.toFloat()..state.canvasWidth.toFloat(),
                readout = "${transform.positionX.roundToInt()} px",
                onIntent = onIntent,
            ) { onIntent(EditorIntent.UpdateTransform(transform.copy(positionX = it.toDouble()))) }
            InspectorSlider(
                label = stringResource(R.string.ed_2b_position_y),
                value = transform.positionY.toFloat(),
                range = -state.canvasHeight.toFloat()..state.canvasHeight.toFloat(),
                readout = "${transform.positionY.roundToInt()} px",
                onIntent = onIntent,
            ) { onIntent(EditorIntent.UpdateTransform(transform.copy(positionY = it.toDouble()))) }
            InspectorSlider(
                label = stringResource(R.string.ed_2b_scale),
                value = transform.scaleX.toFloat().coerceIn(SCALE_MIN, SCALE_MAX),
                range = SCALE_MIN..SCALE_MAX,
                readout = "${(transform.scaleX * PERCENT).roundToInt()}%",
                onIntent = onIntent,
            ) { onIntent(EditorIntent.UpdateTransform(transform.copy(scaleX = it.toDouble(), scaleY = it.toDouble()))) }
            InspectorSlider(
                label = stringResource(R.string.ed_2b_rotation),
                value = transform.rotationDegrees.toFloat().coerceIn(-ROTATION_LIMIT, ROTATION_LIMIT),
                range = -ROTATION_LIMIT..ROTATION_LIMIT,
                readout = "${transform.rotationDegrees.roundToInt()}°",
                onIntent = onIntent,
            ) { onIntent(EditorIntent.UpdateTransform(transform.copy(rotationDegrees = it.toDouble()))) }
            // The usual turns in one tap; each is one undo step like a finished slider drag.
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                for (delta in listOf(-QUARTER_TURN, QUARTER_TURN)) {
                    val sign = if (delta < 0) "-" else "+"

                    TextButton(
                        onClick = {
                            onIntent(EditorIntent.UpdateTransform(transform.copy(rotationDegrees = PreviewGeometry.turnBy(transform.rotationDegrees, delta))))
                            onIntent(EditorIntent.EndAppearanceEdit(commit = true))
                        },
                        modifier = Modifier.described(stringResource(if (delta < 0) R.string.ed_2b_rotate_ccw else R.string.ed_2b_rotate_cw)),
                    ) { Text("$sign${QUARTER_TURN.toInt()}°") }
                }
            }
            InspectorSlider(
                label = stringResource(R.string.ed_2b_opacity),
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
        if (clip.hasMedia) AudioLinkControls(state, clip, onIntent)
        // A video clip with detached sound is silent: its volume and sound tools would change nothing, so they are on the audio clip.
        if (clip.hasMedia && !clip.audioDetached) {
            InspectorSlider(
                label = stringResource(R.string.ed_2b_volume),
                value = clip.gainDb.toFloat().coerceIn(GAIN_MIN, GAIN_MAX),
                range = GAIN_MIN..GAIN_MAX,
                readout = if (clip.gainDb <= GAIN_MIN) stringResource(R.string.ed_2b_readout_mute) else "${formatDb(clip.gainDb)} dB",
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
            text = if (freeze) stringResource(R.string.ed_2b_freeze_frame) else stringResource(R.string.ed_2b_speed),
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
            Text(text = stringResource(R.string.ed_2b_custom), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(76.dp))
            Slider(
                value = logSpeed.coerceIn(LOG_SPEED_MIN, LOG_SPEED_MAX),
                onValueChange = { logSpeed = it },
                onValueChangeFinished = { onIntent(speedIntent(2.0.pow(logSpeed.toDouble()))) },
                valueRange = LOG_SPEED_MIN..LOG_SPEED_MAX,
                modifier = Modifier.weight(1f).described(stringResource(R.string.ed_2b_speed_2, formatSpeed(2.0.pow(logSpeed.toDouble())))),
            )
            Text(text = formatSpeed(2.0.pow(logSpeed.toDouble())), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(60.dp))
        }
        val shape = rampShapeOf(clip)
        Text(text = stringResource(R.string.ed_2b_speed_curve), style = MaterialTheme.typography.labelMedium)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        ) {
            for (option in RAMP_SHAPES) {
                FilterChip(
                    selected = shape == option,
                    onClick = { onIntent(EditorIntent.SetSpeedRamp(option)) },
                    label = { Text(stringResource(option.labelRes())) },
                )
            }
        }
        var curveOpen by remember(clip.id) { mutableStateOf(false) }
        TextButton(onClick = { curveOpen = !curveOpen }) { Text(if (curveOpen) stringResource(R.string.ed_2b_hide_curve_editor) else stringResource(R.string.ed_2b_edit_curve)) }
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
                    Text(text = stringResource(R.string.ed_2b_smooth_slow_motion), style = MaterialTheme.typography.labelMedium)
                    Text(
                        text = stringResource(R.string.ed_2b_makes_in_between_frames_where),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = clip.smoothSlowMo,
                    onCheckedChange = { onIntent(EditorIntent.ToggleSmoothSlowMo) },
                    modifier = Modifier.described(stringResource(R.string.ed_2b_smooth_slow_motion)),
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = stringResource(R.string.ed_2b_reverse), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(76.dp))
            Switch(checked = clip.reverse, onCheckedChange = { onIntent(EditorIntent.ToggleReverse) })
        }
        if (speed > MAX_AUDIBLE_SPEED || speed < MIN_AUDIBLE_SPEED) {
            Text(text = stringResource(R.string.ed_2b_sound_is_muted_outside_x), style = MaterialTheme.typography.bodySmall)
        }
    }
    if (isVisual) {
        TextButton(
            onClick = { onIntent(EditorIntent.FreezeFrame) },
            enabled = state.selectedClipVisible,
        ) { Text(stringResource(R.string.ed_2b_freeze_frame_at_the_playhead)) }
    }
}

/** The preset that [clip]'s ramp is, null for a ramp that is none of them. */
private fun rampShapeOf(clip: Clip): SpeedRampShape? = when (clip.speedRamp) {
    emptyList<com.qtekfun.ultimatevideoeditor.domain.SpeedKey>() -> SpeedRampShape.NONE
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
    SpeedRampShape.NONE,
    SpeedRampShape.EASE_IN,
    SpeedRampShape.EASE_OUT,
    SpeedRampShape.EASE_IN_SMOOTH,
    SpeedRampShape.EASE_OUT_SMOOTH,
    SpeedRampShape.BELL,
    SpeedRampShape.MONTAGE,
    SpeedRampShape.HERO,
    SpeedRampShape.BULLET,
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
            text = if (keyframeCount == 0) stringResource(R.string.ed_2b_animation) else pluralStringResource(R.plurals.ed_2b_animation_keys, keyframeCount, keyframeCount),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { onIntent(EditorIntent.JumpToKeyframe(forward = false)) }, enabled = keyframeCount > 0) {
            Icon(EditorIcons.SkipPrevious, contentDescription = stringResource(R.string.ed_2b_previous_keyframe), modifier = Modifier.size(20.dp))
        }
        IconButton(onClick = { onIntent(EditorIntent.ToggleKeyframe) }) {
            Icon(
                imageVector = if (atPlayhead != null) EditorIcons.KeyframeOn else EditorIcons.KeyframeOff,
                contentDescription = if (atPlayhead != null) stringResource(R.string.ed_2b_remove_the_keyframe_at_the) else stringResource(R.string.ed_2b_add_a_keyframe_at_the),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
        }
        IconButton(onClick = { onIntent(EditorIntent.JumpToKeyframe(forward = true)) }, enabled = keyframeCount > 0) {
            Icon(EditorIcons.SkipNext, contentDescription = stringResource(R.string.ed_2b_next_keyframe), modifier = Modifier.size(20.dp))
        }
        TextButton(onClick = { onIntent(EditorIntent.ClearKeyframes) }, enabled = keyframeCount > 0) { Text(stringResource(R.string.common_clear)) }
    }
    if (atPlayhead != null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = stringResource(R.string.ed_2b_then), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(76.dp))
            for (mode in Interpolation.entries) {
                FilterChip(
                    selected = atPlayhead.interpolation == mode,
                    onClick = { onIntent(EditorIntent.SetKeyframeInterpolation(mode)) },
                    label = { Text(stringResource(mode.labelRes())) },
                )
            }
        }
        if (atPlayhead.interpolation == Interpolation.BEZIER) {
            // The handles of a pose keyframe live on the joint keyframe; any pose parameter id addresses it.
            CurveControls(
                title = stringResource(R.string.ed_2b_pose),
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
        label = { Text(stringResource(R.string.ed_2b_title_text)) },
        modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused) onIntent(EditorIntent.EndTitleEdit(commit = true)) },
    )
    InspectorSlider(
        label = stringResource(R.string.hub_sort_size),
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
        Text(text = stringResource(R.string.ed_2b_colour), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(76.dp))
        for (swatch in TITLE_COLORS) {
            val selected = title.colorArgb == swatch.toArgb()
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(swatch)
                    .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape)
                    .clickable { change(title.copy(colorArgb = swatch.toArgb())) }
                    .described(stringResource(if (selected) R.string.ed_2b_title_colour_selected else R.string.ed_2b_title_colour)),
            )
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = stringResource(R.string.ed_2b_align), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(76.dp))
        for (alignment in TitleAlignment.entries) {
            TextButton(onClick = { change(title.copy(alignment = alignment)) }) {
                Text(
                    text = alignment.name.lowercase().replaceFirstChar { it.uppercase() },
                    style = if (alignment == title.alignment) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Text(text = stringResource(R.string.ed_2b_bold), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 8.dp, end = 8.dp))
        Switch(checked = title.bold, onCheckedChange = { change(title.copy(bold = it)) })
    }
    // A plain title can grow into layers (shapes, pictures, fonts, borders); captions with word timing cannot.
    if (TitleLayerEdit.canConvert(title)) {
        TextButton(onClick = {
            change(TitleLayerEdit.toLayered(title))
            onIntent(EditorIntent.SelectTitleLayer(0))
        }) { Text(stringResource(R.string.ed_2b_edit_as_layers_shapes_pictures)) }
    }
}

/** Add, resize or remove the crossfade between the selected clip and the one right after it. */
@Composable
private fun TransitionControls(state: EditorState, onIntent: (EditorIntent) -> Unit, transitionLimit: (Transition) -> Long) {
    val transition = state.selectedTransition
    if (transition == null && state.clipAfterSelected == null) return
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = if (transition == null) stringResource(R.string.ed_2b_transition_to_next_clip) else stringResource(R.string.ed_2b_to_next_clip, stringResource(transition.type.labelRes())),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        if (transition == null) {
            TextButton(onClick = { onIntent(EditorIntent.AddTransition) }) { Text(stringResource(R.string.ed_2b_add)) }
        } else {
            TextButton(onClick = { onIntent(EditorIntent.RemoveTransition) }) { Text(stringResource(R.string.ed_2b_remove)) }
        }
    }
    if (transition == null) return
    val limit = maxOf(transitionLimit(transition), transition.durationFrames)
    var frames by remember(transition.id, transition.durationFrames) { mutableFloatStateOf(transition.durationFrames.toFloat()) }
    val seconds = frames.toDouble() * state.fps.den / state.fps.num
    val readout = "${(seconds * 100).roundToInt() / 100.0} s"
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text = stringResource(R.string.hub_sort_length), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(76.dp))
        if (limit > Transition.MIN_DURATION_FRAMES) {
            Slider(
                value = frames,
                onValueChange = { frames = it },
                onValueChangeFinished = { onIntent(EditorIntent.SetTransitionDuration(frames.roundToLong())) },
                valueRange = Transition.MIN_DURATION_FRAMES.toFloat()..limit.toFloat(),
                modifier = Modifier.weight(1f).described(stringResource(R.string.ed_2b_crossfade_length, readout)),
            )
        } else {
            Box(modifier = Modifier.weight(1f))
        }
        Text(text = readout, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(60.dp))
    }
    if (limit <= Transition.MIN_DURATION_FRAMES) {
        Text(
            text = stringResource(R.string.ed_2b_no_extra_footage_around_the),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    TransitionStylePicker(transition, onIntent)
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
private const val QUARTER_TURN = 90.0
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
