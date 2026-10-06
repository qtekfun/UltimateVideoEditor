package com.ultimatevideo.uveditor.ui.frame

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ultimatevideo.uveditor.domain.stillframe.FrameContent
import com.ultimatevideo.uveditor.domain.stillframe.FrameFit
import com.ultimatevideo.uveditor.domain.stillframe.FrameFormat
import com.ultimatevideo.uveditor.domain.stillframe.FrameSizePreset
import com.ultimatevideo.uveditor.domain.stillframe.FrameSource
import com.ultimatevideo.uveditor.domain.stillframe.MAX_FRAME_SIDE
import com.ultimatevideo.uveditor.domain.stillframe.MAX_JPEG_QUALITY
import com.ultimatevideo.uveditor.domain.stillframe.MIN_JPEG_QUALITY
import com.ultimatevideo.uveditor.domain.stillframe.SelectedClipStatus
import com.ultimatevideo.uveditor.ui.hub.aspectLabelOf

/** Hosts the "Save frame as image" flow: the settings dialog, the document picker and the share sheet. */
@Composable
fun StillFrameHost(viewModel: StillFrameViewModel, onMessage: (String) -> Unit = {}) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // The contract fixes the document's MIME type, so each format has its own launcher: the new file gets the right type and extension.
    val createPng = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(FrameFormat.PNG.mime)) { uri ->
        viewModel.onIntent(StillFrameIntent.LocationChosen(uri?.toString()))
    }
    val createJpeg = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(FrameFormat.JPEG.mime)) { uri ->
        viewModel.onIntent(StillFrameIntent.LocationChosen(uri?.toString()))
    }
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is StillFrameEffect.LaunchCreateDocument -> {
                    (if (effect.mime == FrameFormat.JPEG.mime) createJpeg else createPng).launch(effect.suggestedName)
                }
                is StillFrameEffect.ShareFile -> shareSavedFrame(context, effect.uri, effect.mime)
                is StillFrameEffect.Message -> onMessage(effect.text)
            }
        }
    }
    if (state.visible) StillFrameDialog(state, viewModel::onIntent)
}

/** Opens the system share sheet for a saved picture. */
fun shareSavedFrame(context: Context, uri: String, mime: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, Uri.parse(uri))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, null))
}

@Composable
internal fun StillFrameDialog(state: StillFrameState, onIntent: (StillFrameIntent) -> Unit) {
    val phase = state.phase
    AlertDialog(
        onDismissRequest = { onIntent(StillFrameIntent.Dismiss) },
        title = { Text(if (phase is StillFramePhase.Working) "Saving the frame…" else "Save frame as image") },
        text = {
            when (phase) {
                StillFramePhase.Configuring -> Settings(state, onIntent)
                StillFramePhase.Working -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("Drawing frame ${state.timecode}…", style = MaterialTheme.typography.bodyMedium)
                }
                is StillFramePhase.Saved -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Saved ${phase.fileName}.")
                    Text(
                        "${phase.width} x ${phase.height} px, ${formatBytes(phase.bytes)}" + (phase.quality?.let { ", JPEG quality $it" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    for (note in phase.notes) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                is StillFramePhase.Failed -> Text(phase.message, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            when (phase) {
                StillFramePhase.Configuring -> TextButton(onClick = { onIntent(StillFrameIntent.ChooseLocation) }) { Text("Save…") }
                StillFramePhase.Working -> Unit
                is StillFramePhase.Saved -> TextButton(onClick = { onIntent(StillFrameIntent.Share) }) { Text("Share") }
                is StillFramePhase.Failed -> TextButton(onClick = { onIntent(StillFrameIntent.Back) }) { Text("Back") }
            }
        },
        dismissButton = {
            when (phase) {
                StillFramePhase.Working -> TextButton(onClick = { onIntent(StillFrameIntent.Cancel) }) { Text("Cancel") }
                is StillFramePhase.Saved -> TextButton(onClick = { onIntent(StillFrameIntent.Dismiss) }) { Text("Done") }
                else -> TextButton(onClick = { onIntent(StillFrameIntent.Dismiss) }) { Text("Cancel") }
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Settings(state: StillFrameState, onIntent: (StillFrameIntent) -> Unit) {
    Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Frame ${state.timecode}", style = MaterialTheme.typography.titleSmall)
        frameNotice(state)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        if (state.hdrProject) {
            Text(
                "HDR project: the picture is converted to SDR (Rec.709) with the same tone mapping as an SDR export and saved as sRGB.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Section("Source") {
            FilterChip(
                selected = state.source == FrameSource.WHOLE_PICTURE,
                onClick = { onIntent(StillFrameIntent.SelectSource(FrameSource.WHOLE_PICTURE)) },
                label = { Text("Whole picture") },
            )
            FilterChip(
                selected = state.source == FrameSource.SELECTED_CLIP,
                enabled = state.clipStatus == SelectedClipStatus.USABLE,
                onClick = { onIntent(StillFrameIntent.SelectSource(FrameSource.SELECTED_CLIP)) },
                label = { Text("Selected clip only") },
            )
        }
        Text(
            when {
                state.source == FrameSource.SELECTED_CLIP -> "Only the selected clip, as it looks on the timeline (effects and position), without other layers, titles or transitions."
                state.clipStatus != SelectedClipStatus.USABLE -> "Everything the export draws at this frame. ${state.clipStatus.reason}"
                else -> "Everything the export draws at this frame: all layers, titles and transitions."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Section("Size") {
            for (preset in FrameSizePreset.entries) {
                FilterChip(
                    selected = preset == state.preset,
                    onClick = { onIntent(StillFrameIntent.SelectPreset(preset)) },
                    label = { Text(preset.label) },
                )
            }
        }
        if (state.preset == FrameSizePreset.CUSTOM) {
            var text by remember { mutableStateOf(state.customWidth.toString()) }
            OutlinedTextField(
                value = text,
                onValueChange = { value ->
                    text = value.filter(Char::isDigit).take(MAX_DIGITS)
                    text.toIntOrNull()?.let { onIntent(StillFrameIntent.SetCustomWidth(it)) }
                },
                label = { Text("Width in pixels (16 to $MAX_FRAME_SIDE), height follows the project's shape") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        val size = state.target.size
        Text(
            "${size.width} x ${size.height} px" + if (state.target.reduced) " (reduced to the $MAX_FRAME_SIDE px limit)" else "",
            style = MaterialTheme.typography.labelLarge,
        )
        if (state.shapeDiffers) {
            Section("Different shape") {
                FilterChip(
                    selected = state.fit == FrameFit.LETTERBOX,
                    onClick = { onIntent(StillFrameIntent.SelectFit(FrameFit.LETTERBOX)) },
                    label = { Text("Fit (black bars)") },
                )
                FilterChip(
                    selected = state.fit == FrameFit.FILL,
                    onClick = { onIntent(StillFrameIntent.SelectFit(FrameFit.FILL)) },
                    label = { Text("Fill (crop)") },
                )
            }
            Text(
                "The project is ${aspectLabelOf(state.projectWidth, state.projectHeight)} and this size has another shape. " +
                    "Fit keeps the whole picture and adds black bars, as an export to this shape does. " +
                    "Fill enlarges the picture until it covers the shape and crops the overflow around the centre.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Section("Format") {
            for (format in FrameFormat.entries) {
                FilterChip(
                    selected = format == state.format,
                    onClick = { onIntent(StillFrameIntent.SelectFormat(format)) },
                    label = { Text(format.label) },
                )
            }
        }
        if (state.format == FrameFormat.JPEG) {
            Text("JPEG quality ${state.jpegQuality}", style = MaterialTheme.typography.labelLarge)
            Slider(
                value = state.jpegQuality.toFloat(),
                onValueChange = { onIntent(StillFrameIntent.SetQuality(it.toInt())) },
                valueRange = MIN_JPEG_QUALITY.toFloat()..MAX_JPEG_QUALITY.toFloat(),
                modifier = Modifier.semantics { contentDescription = "JPEG quality" },
            )
            if (state.sizeLimit != null) {
                Text(
                    "The quality is lowered automatically if the file would be over 2 MB, YouTube's limit for thumbnails.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (state.sizeLimit != null) {
            Text(
                "YouTube refuses thumbnails over 2 MB; a PNG of a photo is often larger. Choose JPEG to stay under it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** What the user should know about the frame before saving it, or null. */
internal fun frameNotice(state: StillFrameState): String? = when (state.content) {
    FrameContent.PICTURE -> null
    FrameContent.GAP -> "There is no picture at this frame (a gap on the timeline): the image will be black."
    FrameContent.PAST_END -> "The playhead is after the end of the project: the image will be black."
    FrameContent.EMPTY -> "The project has no video, picture or title yet: the image will be black."
}

internal fun formatBytes(bytes: Long): String = when {
    bytes >= BYTES_PER_MB -> "%.2f MB".format(bytes / BYTES_PER_MB.toDouble())
    else -> "%.0f KB".format(bytes / BYTES_PER_KB.toDouble())
}

private const val BYTES_PER_KB = 1000L
private const val BYTES_PER_MB = 1_000_000L
private const val MAX_DIGITS = 5

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 2.dp)) {
            content()
        }
    }
}
