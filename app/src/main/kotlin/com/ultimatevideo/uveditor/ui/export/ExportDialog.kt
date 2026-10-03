package com.ultimatevideo.uveditor.ui.export

import android.content.Intent
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ultimatevideo.uveditor.domain.FrameRate
import com.ultimatevideo.uveditor.engine.export.ExportCodec

/** Hosts the export flow: the document picker, the share sheet and the settings/progress dialog. */
@Composable
fun ExportHost(viewModel: ExportViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
        viewModel.onIntent(ExportIntent.LocationChosen(uri?.toString()))
    }
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is ExportEffect.LaunchCreateDocument -> createDocument.launch(effect.suggestedName)
                is ExportEffect.ShareFile -> {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "video/mp4"
                        putExtra(Intent.EXTRA_STREAM, Uri.parse(effect.uri))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(send, null))
                }
            }
        }
    }

    if (state.visible) ExportDialog(state, viewModel::onIntent)
}

@Composable
internal fun ExportDialog(state: ExportState, onIntent: (ExportIntent) -> Unit) {
    val phase = state.phase
    AlertDialog(
        onDismissRequest = { onIntent(ExportIntent.Dismiss) },
        title = { Text(if (phase is ExportPhase.Running) "Exporting…" else "Export movie") },
        text = {
            when (phase) {
                ExportPhase.Configuring -> Settings(state, onIntent)
                is ExportPhase.Running -> Progress(phase.progressPermille)
                is ExportPhase.Done -> Text("Saved ${phase.fileName}.")
                is ExportPhase.Failed -> Text(phase.message, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            when (phase) {
                ExportPhase.Configuring -> TextButton(
                    onClick = { onIntent(ExportIntent.ChooseLocation) },
                    enabled = state.resolution != null && state.frameRate != null,
                ) { Text("Export…") }

                is ExportPhase.Running -> Unit
                is ExportPhase.Done -> TextButton(onClick = { onIntent(ExportIntent.Share) }) { Text("Share") }
                is ExportPhase.Failed -> TextButton(onClick = { onIntent(ExportIntent.Dismiss) }) { Text("Close") }
            }
        },
        dismissButton = {
            when (phase) {
                is ExportPhase.Running -> TextButton(onClick = { onIntent(ExportIntent.Cancel) }) { Text("Cancel") }
                is ExportPhase.Done -> TextButton(onClick = { onIntent(ExportIntent.Dismiss) }) { Text("Close") }
                else -> TextButton(onClick = { onIntent(ExportIntent.Dismiss) }) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun Progress(permille: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LinearProgressIndicator(progress = { permille / 1000f }, modifier = Modifier.fillMaxWidth())
        Text("${permille / 10}%", style = MaterialTheme.typography.labelLarge)
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
