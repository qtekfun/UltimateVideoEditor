package com.ultimatevideo.uveditor.ui.export

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
import com.ultimatevideo.uveditor.domain.FrameRate
import com.ultimatevideo.uveditor.engine.export.ExportCodec
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
                is ExportEffect.Message -> onMessage(effect.text)
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
                when {
                    phase is ExportPhase.Running && phase.verifying -> "Verifying…"
                    phase is ExportPhase.Running -> "Exporting…"
                    else -> "Export movie"
                },
            )
        },
        text = {
            when (phase) {
                ExportPhase.Configuring -> Settings(state, onIntent)
                is ExportPhase.Running -> Progress(phase)
                is ExportPhase.Done -> DoneMessage(phase, onIntent)
                is ExportPhase.Failed -> Text(phase.message, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            when (phase) {
                ExportPhase.Configuring -> TextButton(
                    onClick = { onIntent(ExportIntent.ChooseLocation) },
                    enabled = state.resolution != null && state.frameRate != null,
                ) { Text("Export…") }

                // The export goes on; the notification and the project list keep showing it.
                is ExportPhase.Running -> TextButton(onClick = { onIntent(ExportIntent.Dismiss) }) { Text("Hide") }
                is ExportPhase.Done -> TextButton(onClick = { onIntent(ExportIntent.Share) }) { Text("Share") }
                is ExportPhase.Failed -> TextButton(onClick = { onIntent(ExportIntent.Dismiss) }) { Text("Close") }
            }
        },
        dismissButton = {
            when (phase) {
                is ExportPhase.Running -> TextButton(onClick = { onIntent(ExportIntent.Cancel) }) { Text(if (phase.verifying) "Skip check" else "Cancel") }
                is ExportPhase.Done -> TextButton(onClick = { onIntent(ExportIntent.Dismiss) }) { Text("Close") }
                else -> TextButton(onClick = { onIntent(ExportIntent.Dismiss) }) { Text("Cancel") }
            }
        },
    )
}

/** The saved file and what the check of it found: verified, a warning with its reasons, or "could not verify". */
@Composable
private fun DoneMessage(phase: ExportPhase.Done, onIntent: (ExportIntent) -> Unit) {
    val result = exportResultText(phase.note, phase.verification)
    val colour = if (result.severity == ResultSeverity.OK) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error
    Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Saved ${phase.fileName}.")
        if (result.headline.isNotEmpty()) Text(result.headline, style = MaterialTheme.typography.titleSmall, color = colour)
        if (result.detail.isNotEmpty()) Text(result.detail, style = MaterialTheme.typography.bodySmall)
        if (result.offersExportAgain) {
            Text("The file was kept. You can use it, or export again.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { onIntent(ExportIntent.ExportAgain) }) { Text("Export again") }
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
            Text("Verifying… ${phase.progressPermille / 10}%", style = MaterialTheme.typography.labelLarge)
            Text(
                "The movie is saved. Checking that its first and last frames are the right ones and not damaged.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LinearProgressIndicator(progress = { phase.progressPermille / 1000f }, modifier = Modifier.fillMaxWidth())
        Text("${phase.progressPermille / 10}%", style = MaterialTheme.typography.labelLarge)
        val elapsed = if (phase.startedAtMs > 0) formatDuration((now - phase.startedAtMs).coerceAtLeast(0)) else null
        val left = when {
            estimate.stalled -> "Waiting for the encoder…"
            estimate.remainingMs != null -> "About ${formatDuration(estimate.remainingMs)} left"
            else -> "Estimating time left…"
        }
        Text(listOfNotNull(elapsed?.let { "$it elapsed" }, left).joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
        if (estimate.framesPerSecond != null && estimate.speedFactor != null) {
            Text(
                "%.0f frames/s · %.1fx real time".format(estimate.framesPerSecond, estimate.speedFactor),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Settings(state: ExportState, onIntent: (ExportIntent) -> Unit) {
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Section("Upload to") {
            for (preset in ExportPresets.all) {
                FilterChip(
                    selected = preset == state.preset,
                    onClick = { onIntent(ExportIntent.SelectPreset(preset)) },
                    label = { Text(preset.label) },
                )
            }
        }
        state.preset?.let { preset ->
            if (state.projectAspect.isNotEmpty() && preset.aspect != state.projectAspect) {
                Text(
                    "${preset.label} is made for ${preset.aspect}; this project is ${state.projectAspect}. " +
                        "Change the canvas in the editor to match.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        Section("Resolution") {
            for (option in state.resolutions) {
                FilterChip(
                    selected = option == state.resolution,
                    onClick = { onIntent(ExportIntent.SelectResolution(option)) },
                    label = { Text(option.label) },
                )
            }
        }
        Section("Frame rate") {
            for (rate in state.frameRates) {
                FilterChip(
                    selected = rate == state.frameRate,
                    onClick = { onIntent(ExportIntent.SelectFrameRate(rate)) },
                    label = { Text(rateLabel(rate)) },
                )
            }
        }
        if (state.hdrAvailable) {
            Section("Dynamic range") {
                FilterChip(
                    selected = state.hdr,
                    onClick = { onIntent(ExportIntent.SelectHdr(true)) },
                    label = { Text("HDR (HLG, 10-bit HEVC)") },
                )
                FilterChip(
                    selected = !state.hdr,
                    onClick = { onIntent(ExportIntent.SelectHdr(false)) },
                    label = { Text("SDR") },
                )
            }
        } else if (state.hdrUnsupportedNotice) {
            Text(
                "This device cannot encode HDR at this size, so the movie is exported as SDR; HLG clips are tone-mapped.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Section("Codec") {
            for (codec in ExportCodec.entries) {
                FilterChip(
                    selected = codec == state.codec,
                    onClick = { onIntent(ExportIntent.SelectCodec(codec)) },
                    label = { Text(codec.label) },
                )
            }
        }
        Section("Bitrate") {
            for (mbps in bitrateChoicesMbps()) {
                FilterChip(
                    selected = mbps == state.bitrateMbps,
                    onClick = { onIntent(ExportIntent.SelectBitrate(mbps)) },
                    label = { Text("$mbps Mbps") },
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

private fun rateLabel(rate: FrameRate): String {
    val value = rate.num.toDouble() / rate.den
    return if (rate.den == 1) "${rate.num} fps" else "%.2f fps".format(value)
}
