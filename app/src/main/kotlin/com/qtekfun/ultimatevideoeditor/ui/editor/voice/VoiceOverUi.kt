package com.qtekfun.ultimatevideoeditor.ui.editor.voice

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatevideoeditor.R
import com.qtekfun.ultimatevideoeditor.data.voice.VoiceProblem
import com.qtekfun.ultimatevideoeditor.ui.editor.tray.clockText

/** What the failed attempt tells the person. */
internal fun VoiceProblem.messageRes(): Int = when (this) {
    VoiceProblem.NO_PERMISSION -> R.string.ed_vo_no_permission
    VoiceProblem.MIC_BUSY -> R.string.ed_vo_mic_busy
    VoiceProblem.DISK -> R.string.ed_vo_disk
    VoiceProblem.FAILED -> R.string.ed_vo_failed
}

private enum class Emphasis { PRIMARY, SECONDARY, QUIET, DANGER }

private class PanelButton(val label: String, val emphasis: Emphasis, val onClick: () -> Unit)

/**
 * The recorder panel, shown right above the transport and the tool row while a voice-over is going on: the count-in, the time and level
 * with Pause, Stop and keep and Discard, what a paused take offers, the failure with what to do next, or the confirmation after the
 * take landed (Undo, Record again). One state at a time, nothing when idle. Only the status words are a live region, so a screen
 * reader hears each change once and not every tick of the clock. The screen stays on while recording.
 */
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
    val container = if (state is VoiceState.Added) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer
    Surface(color = container, modifier = modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            val columns = { count: Int -> RecorderLayout.columns(maxWidth.value, fontScale, count) }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when (state) {
                    VoiceState.Idle -> Unit
                    VoiceState.AskingPermission -> {
                        Text(
                            stringResource(R.string.ed_vo_asking),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                        Text(stringResource(R.string.ed_vo_mic_why), style = MaterialTheme.typography.bodySmall)
                    }
                    is VoiceState.Counting -> {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            val spoken = pluralStringResource(R.plurals.ed_vo_counting, state.secondsLeft, state.secondsLeft)
                            Text(
                                state.secondsLeft.toString(),
                                style = MaterialTheme.typography.displayMedium,
                                modifier = Modifier.semantics { contentDescription = spoken; liveRegion = LiveRegionMode.Polite },
                            )
                            Text(
                                stringResource(R.string.ed_vo_headphones),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Text(stringResource(R.string.ed_vo_mic_why), style = MaterialTheme.typography.bodySmall)
                        Buttons(columns(1), listOf(PanelButton(stringResource(R.string.common_cancel), Emphasis.SECONDARY, controller::cancel)))
                    }
                    is VoiceState.Recording -> {
                        Header(recording = true, elapsedMs = state.elapsedMs)
                        LinearProgressIndicator(
                            progress = { state.level },
                            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "" },
                        )
                        val buttons = listOf(
                            PanelButton(stringResource(R.string.ed_vo_pause), Emphasis.SECONDARY, controller::pause),
                            PanelButton(stringResource(R.string.ed_vo_stop), Emphasis.PRIMARY, controller::stop),
                            PanelButton(stringResource(R.string.ed_vo_discard), Emphasis.DANGER, discard),
                        )
                        Buttons(columns(buttons.size), buttons)
                        Text(stringResource(R.string.ed_vo_leave_hint), style = MaterialTheme.typography.bodySmall)
                    }
                    is VoiceState.Paused -> {
                        Header(recording = false, elapsedMs = state.elapsedMs)
                        val buttons = listOf(
                            PanelButton(stringResource(R.string.ed_vo_resume), Emphasis.PRIMARY, controller::resume),
                            PanelButton(stringResource(R.string.ed_vo_stop), Emphasis.SECONDARY, controller::stop),
                            PanelButton(stringResource(R.string.ed_vo_discard), Emphasis.DANGER, discard),
                        )
                        Buttons(columns(buttons.size), buttons)
                        Text(stringResource(R.string.ed_vo_leave_hint), style = MaterialTheme.typography.bodySmall)
                    }
                    VoiceState.Finishing -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.heightIn(min = RecorderLayout.MIN_TOUCH_DP.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(
                            stringResource(R.string.ed_vo_finishing),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                    VoiceState.Added -> {
                        Text(
                            stringResource(R.string.ed_vo_added),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                        val buttons = listOf(
                            PanelButton(stringResource(R.string.ed_vo_undo), Emphasis.SECONDARY) { controller.dismissAdded(); onUndo() },
                            PanelButton(stringResource(R.string.ed_vo_again), Emphasis.PRIMARY, controller::start),
                            PanelButton(stringResource(R.string.ed_vo_done), Emphasis.QUIET, controller::dismissAdded),
                        )
                        Buttons(columns(buttons.size), buttons)
                    }
                    is VoiceState.Failed -> {
                        Text(
                            stringResource(state.problem.messageRes()),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                        val buttons = buildList {
                            if (state.problem == VoiceProblem.NO_PERMISSION) {
                                add(PanelButton(stringResource(R.string.ed_vo_other_app), Emphasis.SECONDARY) { controller.dismissFailure(); onUseOtherApp() })
                            }
                            add(PanelButton(stringResource(R.string.common_ok), Emphasis.QUIET, controller::dismissFailure))
                        }
                        Buttons(columns(buttons.size), buttons)
                    }
                }
            }
        }
    }
}

/** The red dot (or a pause mark's absence) with the state in words, and the recorded time at the end; wraps when the font is large. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Header(recording: Boolean, elapsedMs: Long) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(if (recording) Color.Red else MaterialTheme.colorScheme.outline))
            Text(
                stringResource(if (recording) R.string.ed_vo_recording else R.string.ed_vo_paused),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        Text(clockText(elapsedMs), style = MaterialTheme.typography.titleSmall)
    }
}

/** The buttons in rows of [columns], each as wide as its share and at least 48 dp tall, so labels wrap instead of clipping. */
@Composable
private fun Buttons(columns: Int, buttons: List<PanelButton>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (row in RecorderLayout.rows(buttons, columns)) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (b in row) {
                    val modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = RecorderLayout.MIN_TOUCH_DP.dp)
                    val label: @Composable () -> Unit = { Text(b.label, textAlign = TextAlign.Center) }
                    when (b.emphasis) {
                        Emphasis.PRIMARY -> Button(onClick = b.onClick, modifier = modifier) { label() }
                        Emphasis.SECONDARY -> OutlinedButton(onClick = b.onClick, modifier = modifier) { label() }
                        Emphasis.QUIET -> TextButton(onClick = b.onClick, modifier = modifier) { label() }
                        Emphasis.DANGER -> TextButton(
                            onClick = b.onClick,
                            modifier = modifier,
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) { label() }
                    }
                }
            }
        }
    }
}
