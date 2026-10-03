package com.ultimatevideo.uveditor.ui.editor.captions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ultimatevideo.uveditor.domain.Clip
import com.ultimatevideo.uveditor.domain.captions.CaptionStyle
import com.ultimatevideo.uveditor.engine.captions.CaptionLanguage

/** Shows the captions sheet while [CaptionsViewModel] has a target, and passes its results on. */
@Composable
fun CaptionsHost(
    viewModel: CaptionsViewModel,
    onClips: (List<Clip>) -> Unit,
    onMessage: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is CaptionsEffect.ClipsReady -> onClips(effect.clips)
                is CaptionsEffect.ShowMessage -> onMessage(effect.text)
            }
        }
    }
    if (state.isOpen) CaptionsSheet(state, viewModel::onIntent)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CaptionsSheet(state: CaptionsState, onIntent: (CaptionsIntent) -> Unit) {
    ModalBottomSheet(
        onDismissRequest = { onIntent(CaptionsIntent.Close) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Auto captions", style = MaterialTheme.typography.titleLarge)
            Text(
                "Listens to the selected clip on this device and adds the words as editable title clips on a new track.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Section("Spoken language") { LanguagePicker(state, onIntent) }
            Section("Speech model") { ModelPicker(state, onIntent) }
            Section("Style") { StylePicker(state, onIntent) }

            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            if (state.busy) {
                val label = if (state.phase == CaptionPhase.DOWNLOADING) "Downloading the model" else "Listening to the clip"
                Text("$label ${state.progress}%", style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth())
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                if (state.busy) {
                    OutlinedButton(onClick = { onIntent(CaptionsIntent.Cancel) }) { Text("Cancel") }
                } else {
                    Button(onClick = { onIntent(CaptionsIntent.Generate) }) {
                        Text(if (state.selectedModel?.installed == true) "Generate captions" else "Download and generate")
                    }
                    TextButton(onClick = { onIntent(CaptionsIntent.Close) }) { Text("Close") }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        content()
    }
}

@Composable
private fun LanguagePicker(state: CaptionsState, onIntent: (CaptionsIntent) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = CaptionLanguage.ALL.firstOrNull { it.code == state.languageCode } ?: CaptionLanguage.ALL.first()
    OutlinedButton(onClick = { open = true }, enabled = !state.busy) { Text(current.label) }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        for (language in CaptionLanguage.ALL) {
            DropdownMenuItem(
                text = { Text(language.label) },
                onClick = {
                    open = false
                    onIntent(CaptionsIntent.SelectLanguage(language.code))
                },
            )
        }
    }
}

@Composable
private fun ModelPicker(state: CaptionsState, onIntent: (CaptionsIntent) -> Unit) {
    for (option in state.models) {
        val model = option.model
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .selectable(selected = state.modelId == model.id, enabled = !state.busy, role = Role.RadioButton) {
                    onIntent(CaptionsIntent.SelectModel(model.id))
                },
        ) {
            RadioButton(selected = state.modelId == model.id, onClick = null, enabled = !state.busy)
            Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                Text("${model.label} · ${megabytes(model.sizeBytes)} MB", style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (option.installed) "Downloaded, works offline" else "${model.description} (downloaded once)",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (option.installed) {
                TextButton(onClick = { onIntent(CaptionsIntent.DeleteModel(model.id)) }, enabled = !state.busy) { Text("Remove") }
            }
        }
    }
}

@Composable
private fun StylePicker(state: CaptionsState, onIntent: (CaptionsIntent) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        for (style in CaptionStyle.ALL) {
            FilterChip(
                selected = state.styleId == style.id,
                onClick = { onIntent(CaptionsIntent.SelectStyle(style.id)) },
                enabled = !state.busy,
                label = { Text(style.label) },
            )
        }
    }
}

private fun megabytes(bytes: Long) = (bytes + 1024 * 1024 - 1) / (1024 * 1024)
