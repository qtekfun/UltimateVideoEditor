package com.qtekfun.ultimatevideoeditor.ui.editor.voice

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
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

/** The button that starts a voice-over; disabled while one is going on. */
@Composable
internal fun VoiceOverButton(controller: VoiceOverController, modifier: Modifier = Modifier) {
    OutlinedButton(onClick = controller::start, enabled = !controller.active, modifier = modifier) {
        Text(stringResource(R.string.ed_vo_record))
    }
}

/**
 * The strip above the preview while a voice-over is going on: the count-in, the time and a level meter with Stop, a failure with what to
 * do next, or nothing. It is read out by screen readers as it changes (a live region).
 */
@Composable
internal fun VoiceOverBar(controller: VoiceOverController, onUseOtherApp: () -> Unit, modifier: Modifier = Modifier) {
    val state = controller.state
    if (state is VoiceState.Idle || state is VoiceState.AskingPermission) return
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (state) {
                is VoiceState.Counting -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            pluralStringResource(R.plurals.ed_vo_counting, state.secondsLeft, state.secondsLeft),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = controller::cancel) { Text(stringResource(R.string.common_cancel)) }
                    }
                    Text(stringResource(R.string.ed_vo_headphones), style = MaterialTheme.typography.bodySmall)
                }
                is VoiceState.Recording -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(Color.Red))
                    Text(clockText(state.elapsedMs), style = MaterialTheme.typography.titleSmall)
                    LinearProgressIndicator(
                        progress = { state.level },
                        modifier = Modifier.weight(1f).semantics { contentDescription = "" },
                    )
                    Button(onClick = controller::stop) { Text(stringResource(R.string.ed_vo_stop)) }
                    TextButton(onClick = controller::cancel) { Text(stringResource(R.string.ed_vo_discard)) }
                }
                VoiceState.Finishing -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.ed_vo_finishing), style = MaterialTheme.typography.bodyMedium)
                }
                is VoiceState.Failed -> {
                    Text(stringResource(state.problem.messageRes()), style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.problem == VoiceProblem.NO_PERMISSION) {
                            OutlinedButton(onClick = { controller.dismissFailure(); onUseOtherApp() }) { Text(stringResource(R.string.ed_vo_other_app)) }
                        }
                        TextButton(onClick = controller::dismissFailure) { Text(stringResource(R.string.common_ok)) }
                    }
                }
            }
        }
    }
}
