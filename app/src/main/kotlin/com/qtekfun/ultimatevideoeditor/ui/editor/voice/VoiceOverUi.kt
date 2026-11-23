package com.qtekfun.ultimatevideoeditor.ui.editor.voice

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatevideoeditor.R
import com.qtekfun.ultimatevideoeditor.data.voice.VoiceProblem
import com.qtekfun.ultimatevideoeditor.ui.editor.EditorIcons
import com.qtekfun.ultimatevideoeditor.ui.editor.tray.clockText

/** What the failed attempt tells the person. */
internal fun VoiceProblem.messageRes(): Int = when (this) {
    VoiceProblem.NO_PERMISSION -> R.string.ed_vo_no_permission
    VoiceProblem.MIC_BUSY -> R.string.ed_vo_mic_busy
    VoiceProblem.DISK -> R.string.ed_vo_disk
    VoiceProblem.FAILED -> R.string.ed_vo_failed
}

/** The colour of the record dot and of the stop button: the one place the bar uses red, so it reads as "the microphone is on". */
private val RecordRed = Color(0xFFE5483F)

/**
 * The recorder bar, shown right above the transport and the tool row while a voice-over is going on. It is neutral, not an alarm: the
 * time and a scrolling level meter on the left, and round Discard, Pause or Resume, and Stop buttons, each with its name under it,
 * on the right (under the time in a narrow window or with a large font). Pause and Resume share a place, so a finger that went for
 * one never lands on something else; Undo, after the take landed, sits at the other edge. One state at a time, nothing when idle.
 * Only the status words are a live region, so a screen reader hears each change once and not every tick of the clock. The screen
 * stays on while recording.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VoiceOverPanel(controller: VoiceOverController, onUseOtherApp: () -> Unit, onUndo: () -> Unit, modifier: Modifier = Modifier) {
    val state = controller.state
    val recording = state is VoiceState.Recording || state is VoiceState.Paused
    val keepOn = recording || state is VoiceState.Counting

    val view = LocalView.current
    DisposableEffect(keepOn) {
        if (keepOn) view.keepScreenOn = true
        onDispose { if (keepOn) view.keepScreenOn = false }
    }
    // A tick under the finger's other hand when the microphone goes on and when it ends.
    val haptic = LocalHapticFeedback.current
    var wasRecording by remember { mutableStateOf(false) }
    LaunchedEffect(recording) {
        if (recording != wasRecording) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            wasRecording = recording
        }
    }
    var confirmDiscard by remember { mutableStateOf(false) }
    LaunchedEffect(recording) { if (!recording) confirmDiscard = false }

    // The recent levels the meter draws: kept while paused, started afresh with each take.
    var history by remember { mutableStateOf(emptyList<Float>()) }
    LaunchedEffect(state) {
        when (state) {
            is VoiceState.Recording -> history = LevelHistory.push(history, state.level)
            is VoiceState.Paused -> Unit
            else -> if (history.isNotEmpty()) history = emptyList()
        }
    }

    if (state is VoiceState.Idle) return
    if (confirmDiscard && recording) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.ed_vo_discard_title)) },
            text = { Text(stringResource(R.string.ed_vo_discard_body)) },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; controller.cancel() }) {
                    Text(stringResource(R.string.ed_vo_discard), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.ed_vo_keep_recording)) } },
        )
    }
    val discard = { if (controller.discardNeedsConfirmation) confirmDiscard = true else controller.cancel() }
    val fontScale = LocalDensity.current.fontScale
    val accent = when (state) {
        is VoiceState.Recording -> RecordRed
        is VoiceState.Paused -> MaterialTheme.colorScheme.outline
        is VoiceState.Counting -> RecordRed
        else -> Color.Transparent
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier.fillMaxWidth()) {
        Column {
            Box(Modifier.fillMaxWidth().height(3.dp).background(accent))
            BoxWithConstraints(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                val wide = RecorderBarLayout.isWide(maxWidth.value, fontScale)
                when (state) {
                    VoiceState.Idle -> Unit
                    VoiceState.AskingPermission -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(EditorIcons.Mic, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.ed_vo_asking),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            )
                            Text(stringResource(R.string.ed_vo_mic_why), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    is VoiceState.Counting -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val spoken = pluralStringResource(R.plurals.ed_vo_counting, state.secondsLeft, state.secondsLeft)
                        Box(Modifier.size(48.dp).border(2.dp, RecordRed, CircleShape), contentAlignment = Alignment.Center) {
                            Text(state.secondsLeft.toString(), style = MaterialTheme.typography.titleLarge)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(spoken, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                            Text(stringResource(R.string.ed_vo_headphones), style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = controller::cancel, modifier = Modifier.heightIn(min = RecorderBarLayout.ACTION_DP.dp)) {
                            Text(stringResource(R.string.common_cancel))
                        }
                    }
                    is VoiceState.Recording -> TakeBar(true, state.elapsedMs, history, wide, controller, discard)
                    is VoiceState.Paused -> TakeBar(false, state.elapsedMs, history, wide, controller, discard)
                    VoiceState.Finishing -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.heightIn(min = RecorderBarLayout.ACTION_DP.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(
                            stringResource(R.string.ed_vo_finishing),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                    VoiceState.Added -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            stringResource(R.string.ed_vo_added),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                        // Undo at one edge and Done at the other: neither sits where Pause was a moment ago.
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            itemVerticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(
                                onClick = { controller.dismissAdded(); onUndo() },
                                modifier = Modifier.heightIn(min = RecorderBarLayout.ACTION_DP.dp),
                            ) { Text(stringResource(R.string.ed_vo_undo)) }
                            OutlinedButton(onClick = controller::start, modifier = Modifier.heightIn(min = RecorderBarLayout.ACTION_DP.dp)) {
                                Icon(EditorIcons.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                                Text(stringResource(R.string.ed_vo_again), modifier = Modifier.padding(start = 6.dp))
                            }
                            TextButton(onClick = controller::dismissAdded, modifier = Modifier.heightIn(min = RecorderBarLayout.ACTION_DP.dp)) {
                                Text(stringResource(R.string.ed_vo_done))
                            }
                        }
                    }
                    is VoiceState.Failed -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            stringResource(state.problem.messageRes()),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                            if (state.problem == VoiceProblem.NO_PERMISSION) {
                                OutlinedButton(
                                    onClick = { controller.dismissFailure(); onUseOtherApp() },
                                    modifier = Modifier.heightIn(min = RecorderBarLayout.ACTION_DP.dp),
                                ) { Text(stringResource(R.string.ed_vo_other_app)) }
                            }
                            TextButton(onClick = controller::dismissFailure, modifier = Modifier.heightIn(min = RecorderBarLayout.ACTION_DP.dp)) {
                                Text(stringResource(R.string.common_ok))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Recording or paused: the time, the meter and the three round actions, in one row when they fit and in two when they do not. */
@Composable
private fun TakeBar(
    recording: Boolean,
    elapsedMs: Long,
    history: List<Float>,
    wide: Boolean,
    controller: VoiceOverController,
    discard: () -> Unit,
) {
    val actions: @Composable (Modifier, Arrangement.Horizontal) -> Unit = { modifier, arrangement ->
        Row(modifier, horizontalArrangement = arrangement) {
            RoundAction(
                label = stringResource(R.string.ed_vo_discard),
                description = stringResource(R.string.ed_vo_discard),
                container = MaterialTheme.colorScheme.surfaceContainerHighest,
                onClick = discard,
            ) { Icon(EditorIcons.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) }
            if (recording) {
                RoundAction(
                    label = stringResource(R.string.ed_vo_pause),
                    description = stringResource(R.string.ed_vo_pause),
                    container = MaterialTheme.colorScheme.surfaceContainerHighest,
                    onClick = controller::pause,
                ) { Icon(EditorIcons.Pause, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface) }
            } else {
                RoundAction(
                    label = stringResource(R.string.ed_vo_resume),
                    description = stringResource(R.string.ed_vo_resume),
                    container = MaterialTheme.colorScheme.primary,
                    onClick = controller::resume,
                ) { Icon(EditorIcons.Mic, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary) }
            }
            RoundAction(
                label = stringResource(R.string.ed_vo_stop_short),
                description = stringResource(R.string.ed_vo_stop),
                container = RecordRed,
                onClick = controller::stop,
            ) { Box(Modifier.size(18.dp).clip(RoundedCornerShape(3.dp)).background(Color.White)) }
        }
    }
    if (wide) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Timer(recording, elapsedMs)
            LevelBars(history, recording, Modifier.weight(1f))
            actions(Modifier, Arrangement.spacedBy(8.dp))
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Timer(recording, elapsedMs)
                LevelBars(history, recording, Modifier.weight(1f))
            }
            actions(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly)
        }
    }
}

/** The recorded time, with the state in words under it and a dot that pulses while the microphone is on. */
@Composable
private fun Timer(recording: Boolean, elapsedMs: Long) {
    val pulse = rememberInfiniteTransition()
    val alpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(if (recording) RecordRed.copy(alpha = alpha) else MaterialTheme.colorScheme.outline))
        Column {
            Text(clockText(elapsedMs), style = MaterialTheme.typography.titleLarge, maxLines = 1)
            Text(
                stringResource(if (recording) R.string.ed_vo_recording else R.string.ed_vo_paused),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

/** The last moments of sound as bars, the newest at the right: it moves while the person talks and stands still when paused. */
@Composable
private fun LevelBars(history: List<Float>, active: Boolean, modifier: Modifier) {
    val on = if (active) RecordRed else MaterialTheme.colorScheme.outline
    val off = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(modifier.height(36.dp).semantics { contentDescription = "" }) {
        val slots = LevelHistory.SLOTS
        val slot = size.width / slots
        val bar = slot * 0.55f
        val minHeight = size.height * 0.1f
        for (i in 0 until slots) {
            val index = history.size - slots + i
            val level = history.getOrNull(index)
            val h = if (level == null) minHeight else maxOf(minHeight, LevelHistory.fill(level) * size.height)
            drawRoundRect(
                color = if (level == null) off else on,
                topLeft = Offset(i * slot + (slot - bar) / 2f, (size.height - h) / 2f),
                size = Size(bar, h),
                cornerRadius = CornerRadius(bar / 2f),
            )
        }
    }
}

/** A round button with its name under it: the picture says what it does and the word says it again. */
@Composable
private fun RoundAction(label: String, description: String, container: Color, onClick: () -> Unit, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .widthIn(min = 56.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClickLabel = description, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(RecorderBarLayout.ACTION_DP.dp).clip(CircleShape).background(container), contentAlignment = Alignment.Center) { content() }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}
