package com.qtekfun.ultimatevideoeditor.ui.templates

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatevideoeditor.data.ProjectSummary
import com.qtekfun.ultimatevideoeditor.domain.templates.BuiltInTemplates
import com.qtekfun.ultimatevideoeditor.domain.templates.Placeholder
import com.qtekfun.ultimatevideoeditor.domain.templates.PlaceholderKind
import com.qtekfun.ultimatevideoeditor.domain.templates.ProjectTemplate

/**
 * "New from template": the list of templates, then one row per placeholder to fill with a file, then Create.
 * [projects] feeds the "make a template from a project" list; [onCreated] is called with the new project's id.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplateWizardSheet(
    viewModel: TemplateWizardViewModel,
    projects: List<ProjectSummary>,
    onCreated: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(state.createdProjectId) {
        state.createdProjectId?.let {
            viewModel.consumeCreated()
            onCreated(it)
        }
    }
    var pickFor by remember { mutableStateOf<String?>(null) }
    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = pickFor
        pickFor = null
        if (uri != null && id != null) viewModel.pick(id, uri.toString())
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importTemplate(uri.toString())
    }
    var exportId by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val id = exportId
        exportId = null
        if (uri != null && id != null) viewModel.exportTemplate(id, uri.toString())
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val selected = state.selected
            if (selected == null) {
                Text("New from a template", style = MaterialTheme.typography.titleMedium)
                Text(
                    "A template is a project without media: titles, effects, transitions and slots for your own clips. Nothing is downloaded and your files stay where they are.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(state.templates, key = { it.id }) { template ->
                        TemplateRow(
                            template = template,
                            user = BuiltInTemplates.find(template.id) == null,
                            onOpen = { viewModel.select(template.id) },
                            onExport = { exportId = template.id; exportLauncher.launch("${template.name}.uvtemplate") },
                            onDelete = { viewModel.deleteTemplate(template.id) },
                        )
                    }
                    if (projects.isNotEmpty()) {
                        item(key = "from-project") {
                            Text("Make a template from one of your projects", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                        }
                        items(projects, key = { "project-${it.id}" }) { project ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Text(project.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                TextButton(onClick = { viewModel.saveProjectAsTemplate(project.id) }) { Text("Save as template") }
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) { Text("Import template file…") }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            } else {
                Text(selected.name, style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = state.name,
                    onValueChange = viewModel::setName,
                    label = { Text("Project name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Choose a file for each slot. Clips longer than a slot are trimmed from their start, shorter ones shorten it, and pictures of another shape are centre-cropped.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.busy || state.busyPlaceholder != null) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                LazyColumn(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(selected.placeholders, key = { it.id }) { placeholder ->
                        PlaceholderRow(
                            placeholder = placeholder,
                            fps = selected,
                            label = state.picked[placeholder.id]?.label,
                            busy = state.busyPlaceholder == placeholder.id,
                            onPick = { pickFor = placeholder.id; pickLauncher.launch(mimeTypesFor(placeholder.kind)) },
                            onClear = { viewModel.clear(placeholder.id) },
                        )
                    }
                }
                state.problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                for (warning in state.warnings) Text(warning, style = MaterialTheme.typography.bodySmall)
                if (state.missingRequired.isNotEmpty()) {
                    Text("Still to choose: ${state.missingRequired.joinToString { it.name }}", style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = viewModel::create, enabled = state.canCreate) { Text("Create project") }
                    TextButton(onClick = viewModel::back) { Text("Back") }
                }
            }
            state.message?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = viewModel::dismissMessage) { Text("OK") }
                }
            }
        }
    }
}

private fun mimeTypesFor(kind: PlaceholderKind): Array<String> = when (kind) {
    PlaceholderKind.VIDEO -> arrayOf("video/*")
    PlaceholderKind.VIDEO_OR_PHOTO -> arrayOf("video/*", "image/*")
    PlaceholderKind.PHOTO -> arrayOf("image/*")
    PlaceholderKind.AUDIO -> arrayOf("audio/*", "video/*")
}

@Composable
private fun TemplateRow(template: ProjectTemplate, user: Boolean, onOpen: () -> Unit, onExport: () -> Unit, onDelete: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = "Use ${template.name}", onClick = onOpen)
            .padding(vertical = 8.dp),
    ) {
        Text(template.name, style = MaterialTheme.typography.bodyLarge)
        Text(template.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val fps = template.fpsNum.toDouble() / template.fpsDen
        Text(
            "${slotCountLabel(template.placeholders.size)} ·${template.width} x ${template.height} · ${"%.4g".format(fps)} fps",
            style = MaterialTheme.typography.labelSmall,
        )
        if (user) {
            Row {
                TextButton(onClick = onExport) { Text("Share file…") }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun PlaceholderRow(
    placeholder: Placeholder,
    fps: ProjectTemplate,
    label: String?,
    busy: Boolean,
    onPick: () -> Unit,
    onClear: () -> Unit,
) {
    val seconds = placeholder.frames * fps.fpsDen.toDouble() / fps.fpsNum
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Slot ${placeholder.name}" }) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "${placeholder.name}${if (placeholder.optional) " (optional)" else ""}",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                label?.let { "Chosen: $it" } ?: "${placeholder.kind.label}, about ${"%.1f".format(seconds)} s",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (label != null) TextButton(onClick = onClear) { Text("Clear") }
        TextButton(onClick = onPick, enabled = !busy) { Text(if (label == null) "Choose…" else "Change…") }
    }
}
