package com.qtekfun.ultimatevideoeditor.ui.export

import androidx.compose.ui.platform.LocalConfiguration
import com.qtekfun.ultimatevideoeditor.ui.text.isNotEmpty
import com.qtekfun.ultimatevideoeditor.ui.text.resolve
import com.qtekfun.ultimatevideoeditor.ui.text.asString
import androidx.compose.ui.res.stringResource
import com.qtekfun.ultimatevideoeditor.R
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qtekfun.ultimatevideoeditor.domain.FrameRate
import com.qtekfun.ultimatevideoeditor.engine.export.ExportCodec
import kotlinx.coroutines.delay

/** Hosts the export flow: the document picker, the share sheet and the settings/progress dialog. */
@Composable
fun ExportHost(viewModel: ExportViewModel, onMessage: (String) -> Unit = {}) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
        viewModel.onIntent(ExportIntent.LocationChosen(uri?.toString()))
    }
    // Asked once the user has pressed Export, never at start-up. A refusal changes nothing but the notification: the
    // export still runs in its foreground service.
    var pendingName by remember { mutableStateOf<String?>(null) }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pendingName?.let(createDocument::launch)
        pendingName = null
    }
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is ExportEffect.LaunchCreateDocument -> {
                    val needsAsk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    if (needsAsk) {
                        pendingName = effect.suggestedName
                        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        createDocument.launch(effect.suggestedName)
                    }
                }
                is ExportEffect.ShareFile -> shareExportedMovie(context, effect.uri)
                is ExportEffect.Message -> onMessage(effect.text.resolve(context))
            }
        }
    }

    if (state.visible) ExportDialog(state, viewModel::onIntent)
}

/** Opens the system share sheet for a finished export (the editor's dialog and the project list's bar use the same one). */
fun shareExportedMovie(context: Context, uri: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "video/mp4"
        putExtra(Intent.EXTRA_STREAM, Uri.parse(uri))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, null))
}

@Composable
internal fun ExportDialog(state: ExportState, onIntent: (ExportIntent) -> Unit) {
    val phase = state.phase
    AlertDialog(
        onDismissRequest = { onIntent(ExportIntent.Dismiss) },
        title = {
            Text(
                stringResource(
                    when {
                        phase is ExportPhase.Running && phase.verifying -> R.string.export_dlg_verifying_title
                        phase is ExportPhase.Running -> R.string.export_dlg_exporting_title
                        else -> R.string.export_dlg_title
                    },
                ),
            )
        },
        text = {
            when (phase) {
                ExportPhase.Configuring -> Settings(state, onIntent)
                is ExportPhase.Running -> Progress(phase)
                is ExportPhase.Done -> DoneMessage(phase, onIntent)
                is ExportPhase.Failed -> Text(phase.message.asString(), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            when (phase) {
                ExportPhase.Configuring -> TextButton(
                    onClick = { onIntent(ExportIntent.ChooseLocation) },
                    enabled = state.resolution != null && state.frameRate != null,
                ) { Text(stringResource(R.string.export_dlg_export_button)) }

                // The export goes on; the notification and the project list keep showing it.
                is ExportPhase.Running -> TextButton(onClick = { onIntent(ExportIntent.Dismiss) }) { Text(stringResource(R.string.common_hide)) }
                is ExportPhase.Done -> TextButton(onClick = { onIntent(ExportIntent.Share) }) { Text(stringResource(R.string.common_share)) }
                is ExportPhase.Failed -> TextButton(onClick = { onIntent(ExportIntent.Dismiss) }) { Text(stringResource(R.string.common_close)) }
            }
        },
        dismissButton = {
            when (phase) {
                is ExportPhase.Running -> TextButton(onClick = { onIntent(ExportIntent.Cancel) }) { Text(stringResource(if (phase.verifying) R.string.export_dlg_skip_check else R.string.common_cancel)) }
                is ExportPhase.Done -> TextButton(onClick = { onIntent(ExportIntent.Dismiss) }) { Text(stringResource(R.string.common_close)) }
                else -> TextButton(onClick = { onIntent(ExportIntent.Dismiss) }) { Text(stringResource(R.string.common_cancel)) }
            }
        },
    )
}

/** The saved file and what the check of it found: verified, a warning with its reasons, or "could not verify". */
@Composable
private fun DoneMessage(phase: ExportPhase.Done, onIntent: (ExportIntent) -> Unit) {
    val result = exportResultText(phase.note, phase.verification, phase.exportMs, phase.verifyMs)
    val colour = if (result.severity == ResultSeverity.OK) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error
    Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.export_dlg_saved, phase.fileName))
        if (result.headline.isNotEmpty()) Text(result.headline.asString(), style = MaterialTheme.typography.titleSmall, color = colour)
        if (result.timing.isNotEmpty()) Text(result.timing.asString(), style = MaterialTheme.typography.bodyMedium)
        if (result.detail.isNotEmpty()) Text(result.detail.asString(), style = MaterialTheme.typography.bodySmall)
        if (result.offersExportAgain) {
            Text(stringResource(R.string.export_dlg_kept), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { onIntent(ExportIntent.ExportAgain) }) { Text(stringResource(R.string.export_dlg_export_again)) }
        }
    }
}

@Composable
private fun Progress(phase: ExportPhase.Running) {
    // A once-a-second tick keeps the elapsed time moving between the engine's progress reports.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(phase.startedAtMs) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val estimate = phase.estimate
    if (phase.verifying) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LinearProgressIndicator(progress = { phase.progressPermille / 1000f }, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.export_dlg_verifying_percent, phase.progressPermille / 10), style = MaterialTheme.typography.labelLarge)
            Text(
                stringResource(R.string.export_dlg_verifying_body),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LinearProgressIndicator(progress = { phase.progressPermille / 1000f }, modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.percent_value, phase.progressPermille / 10), style = MaterialTheme.typography.labelLarge)
        val elapsed = if (phase.startedAtMs > 0) formatDuration((now - phase.startedAtMs).coerceAtLeast(0)) else null
        val left = when {
            estimate.stalled -> stringResource(R.string.export_dlg_waiting_encoder)
            estimate.remainingMs != null -> stringResource(R.string.export_dlg_about_left, formatDuration(estimate.remainingMs))
            else -> stringResource(R.string.export_dlg_estimating)
        }
        Text(listOfNotNull(elapsed?.let { stringResource(R.string.export_dlg_elapsed, it) }, left).joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
        if (estimate.framesPerSecond != null && estimate.speedFactor != null) {
            Text(
                stringResource(R.string.export_dlg_speed, estimate.framesPerSecond, estimate.speedFactor),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Settings(state: ExportState, onIntent: (ExportIntent) -> Unit) {
    // The size estimate stays in view under the scrolling settings, so every change shows its effect.
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Column(
        modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Section(stringResource(R.string.export_dlg_upload_to)) {
            for (preset in ExportPresets.all) {
                FilterChip(
                    selected = preset == state.preset,
                    onClick = { onIntent(ExportIntent.SelectPreset(preset)) },
                    label = { Text(stringResource(preset.labelRes)) },
                )
            }
        }
        state.preset?.let { preset ->
            if (state.projectAspect.isNotEmpty() && preset.aspect != state.projectAspect) {
                Text(
                    stringResource(R.string.export_dlg_preset_mismatch, stringResource(preset.labelRes), preset.aspect, state.projectAspect),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        Section(stringResource(R.string.export_dlg_resolution)) {
            for (option in state.resolutions) {
                FilterChip(
                    selected = option == state.resolution,
                    onClick = { onIntent(ExportIntent.SelectResolution(option)) },
                    label = { Text(option.label) },
                )
            }
        }
        Section(stringResource(R.string.export_dlg_frame_rate)) {
            for (rate in state.frameRates) {
                FilterChip(
                    selected = rate == state.frameRate,
                    onClick = { onIntent(ExportIntent.SelectFrameRate(rate)) },
                    label = { Text(rateLabel(rate)) },
                )
            }
        }
        if (state.hdrAvailable) {
            Section(stringResource(R.string.export_dlg_dynamic_range)) {
                FilterChip(
                    selected = state.hdr,
                    onClick = { onIntent(ExportIntent.SelectHdr(true)) },
                    label = { Text(stringResource(R.string.export_dlg_hdr)) },
                )
                FilterChip(
                    selected = !state.hdr,
                    onClick = { onIntent(ExportIntent.SelectHdr(false)) },
                    label = { Text(stringResource(R.string.export_dlg_sdr)) },
                )
            }
        } else if (state.hdrUnsupportedNotice) {
            Text(
                stringResource(R.string.export_dlg_hdr_unsupported),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Section(stringResource(R.string.export_dlg_codec)) {
            for (codec in ExportCodec.entries) {
                FilterChip(
                    selected = codec == state.codec,
                    onClick = { onIntent(ExportIntent.SelectCodec(codec)) },
                    label = { Recommendable(codec.label, state.recommendation?.takeIf { it.fromSources }?.codec == codec) },
                )
            }
        }
        if (state.codec == ExportCodec.HEVC) {
            Section(stringResource(R.string.export_dlg_smart)) {
                FilterChip(
                    selected = state.smart,
                    onClick = { onIntent(ExportIntent.SelectSmart(!state.smart)) },
                    label = { Text(stringResource(R.string.export_dlg_smart_chip)) },
                )
            }
            if (state.smart) {
                Text(
                    stringResource(R.string.export_dlg_smart_note),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Section(stringResource(R.string.export_dlg_bitrate)) {
            val recommendation = state.recommendation?.takeIf { it.fromSources }
            for (mbps in bitrateChoicesMbps()) {
                FilterChip(
                    selected = mbps == state.bitrateMbps,
                    onClick = { onIntent(ExportIntent.SelectBitrate(mbps)) },
                    label = { Recommendable(stringResource(R.string.export_dlg_mbps, mbps), recommendation?.bitrateMbps == mbps) },
                )
            }
        }
        state.recommendation?.let { recommendation ->
            bitrateAdvice(recommendation, state.sources, state.bitrateMbps)?.let { advice ->
                Text(advice.asString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    SizeLine(state)
    }
}

/** A chip label that says "recommended" in small type when the clips suggested this choice. */
@Composable
private fun Recommendable(label: String, recommended: Boolean) {
    if (recommended) {
        Column {
            Text(label)
            Text(stringResource(R.string.export_dlg_recommended), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    } else {
        Text(label)
    }
}

/** The live size estimate, with the range the encoder's rate control allows and a warning when it might not fit. */
@Composable
private fun SizeLine(state: ExportState) {
    val estimate = state.sizeEstimate ?: return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            stringResource(R.string.export_dlg_estimated_size, formatBytes(estimate.bytes), formatBytes(estimate.lowBytes), formatBytes(estimate.highBytes)),
            style = MaterialTheme.typography.bodyMedium,
        )
        state.freeBytes?.let { free ->
            if (exceedsFreeSpace(estimate, free)) {
                Text(
                    stringResource(R.string.export_dlg_may_not_fit, formatBytes(free)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 2.dp)) {
            content()
        }
    }
}

@Composable
private fun rateLabel(rate: FrameRate): String {
    val value = rate.num.toDouble() / rate.den
    val locale = LocalConfiguration.current.locales[0]
    return stringResource(R.string.fps_value, if (rate.den == 1) rate.num.toString() else "%.2f".format(locale, value))
}
