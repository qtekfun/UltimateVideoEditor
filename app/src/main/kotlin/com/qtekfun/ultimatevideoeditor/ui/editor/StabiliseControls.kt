package com.qtekfun.ultimatevideoeditor.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatevideoeditor.domain.Clip
import com.qtekfun.ultimatevideoeditor.domain.StabCrop
import com.qtekfun.ultimatevideoeditor.domain.Stabilise
import com.qtekfun.ultimatevideoeditor.engine.stabilise.StabStatus
import kotlin.math.roundToInt

/**
 * Camera-shake correction of the selected video clip (SPECS.md 9.6): a switch, how steady the result is, how much of
 * the picture to give up to hide the moving edges, and the one-off analysis of the file's camera motion (progress,
 * cancel, and a note when the clip was extended beyond the analysed part). Everything runs on the device.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun StabiliseControls(clip: Clip, stab: StabUiState, onIntent: (EditorIntent) -> Unit) {
    val settings = clip.stabilise
    // Make the table of this clip available (and read its analysis status) whenever the clip or its settings change.
    LaunchedEffect(clip.id, settings) { onIntent(EditorIntent.RefreshStabilise) }

    Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Stabilise", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Switch(
                checked = settings != null,
                onCheckedChange = { on -> onIntent(EditorIntent.SetStabilise(if (on) Stabilise() else null)) },
                modifier = Modifier.semantics { contentDescription = "Stabilise this clip" },
            )
        }
        if (settings == null) return@Column

        // The slider moves freely while dragged and commits one undo step when released.
        var strength by remember(clip.id, settings.strength) { mutableFloatStateOf(settings.strength.toFloat()) }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Strength", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(76.dp))
            Slider(
                value = strength,
                onValueChange = { strength = it },
                onValueChangeFinished = { onIntent(EditorIntent.SetStabilise(settings.copy(strength = strength.toDouble()))) },
                valueRange = 0f..1f,
                modifier = Modifier.weight(1f).semantics { contentDescription = "Stabilise strength ${(strength * 100).roundToInt()} percent" },
            )
            Text("${(strength * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium)
        }
        Text("Crop", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (crop in StabCrop.entries) {
                FilterChip(
                    selected = settings.crop == crop,
                    onClick = { onIntent(EditorIntent.SetStabilise(settings.copy(crop = crop))) },
                    label = { Text(crop.label) },
                )
            }
        }
        Text(
            when (settings.crop) {
                StabCrop.TIGHT -> "Zooms in so no edge shows."
                StabCrop.MEDIUM -> "Half the zoom; a little edge fill may show."
                StabCrop.FULL -> "No zoom; edges repeat the border pixels."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val progress = stab.progress
        if (progress != null) {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Analysing camera motion ${(progress * 100).roundToInt()} percent" })
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Analysing camera motion… ${(progress * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { onIntent(EditorIntent.CancelStabilise) }) { Text("Cancel") }
            }
        } else {
            when (stab.status) {
                StabStatus.Ready -> Text("Ready: the correction follows the camera path.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                StabStatus.Stale -> AnalyseRow("The clip reaches outside the analysed part. Analyse again to cover it.", "Analyse again", onIntent)
                StabStatus.NotAnalysed, StabStatus.Off -> AnalyseRow("Not analysed yet: the camera motion has to be measured once for this file.", "Analyse", onIntent)
            }
        }
    }
}

@Composable
private fun AnalyseRow(note: String, button: String, onIntent: (EditorIntent) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Button(onClick = { onIntent(EditorIntent.AnalyseStabilise) }) { Text(button) }
    }
}
