package com.ultimatevideo.uveditor.ui.hub

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ultimatevideo.uveditor.data.ProjectSummary
import java.text.DateFormat
import java.util.Date

@Composable
fun HubScreen(viewModel: HubViewModel, onOpenProject: (String) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var pendingExportId by remember { mutableStateOf<String?>(null) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.onIntent(HubIntent.ImportFrom(uri.toString()))
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val id = pendingExportId
        pendingExportId = null
        if (uri != null && id != null) viewModel.onIntent(HubIntent.ExportTo(id, uri.toString()))
    }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is HubEffect.ShowMessage -> snackbar.showSnackbar(effect.text)
                is HubEffect.LaunchExportPicker -> {
                    pendingExportId = effect.projectId
                    exportLauncher.launch(effect.suggestedFileName)
                }
                is HubEffect.OpenEditor -> onOpenProject(effect.projectId)
            }
        }
    }

    HubContent(
        state = state,
        snackbar = snackbar,
        onIntent = viewModel::onIntent,
        onImport = { importLauncher.launch(arrayOf("*/*")) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HubContent(
    state: HubState,
    snackbar: SnackbarHostState,
    onIntent: (HubIntent) -> Unit,
    onImport: () -> Unit,
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("ultimateVE") },
                actions = { TextButton(onClick = onImport) { Text("Import") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { onIntent(HubIntent.ShowNewProject) }) {
                Text("New project")
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.isLoading -> Unit
                    state.projects.isEmpty() -> WelcomeState(state.unreadableCount)
                    else -> ProjectGrid(state, onIntent)
                }
            }
            Text(
                text = state.engineError ?: state.engineVersion?.let { "Engine v$it" } ?: "Loading engine…",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                textAlign = TextAlign.Center,
            )
        }
    }

    state.newProjectDraft?.let { NewProjectDialog(it, onIntent) }
    state.renameDraft?.let { draft ->
        TextDialog(
            title = "Rename project",
            value = draft.name,
            confirmLabel = "Rename",
            onValueChange = { onIntent(HubIntent.RenameNameChanged(it)) },
            onConfirm = { onIntent(HubIntent.ConfirmRename) },
            onDismiss = { onIntent(HubIntent.DismissDialogs) },
        )
    }
    state.deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { onIntent(HubIntent.DismissDialogs) },
            title = { Text("Delete project?") },
            text = { Text("\"${target.name}\" will be removed from this device. Source media is not touched.") },
            confirmButton = { TextButton(onClick = { onIntent(HubIntent.ConfirmDelete) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { onIntent(HubIntent.DismissDialogs) }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun WelcomeState(unreadableCount: Int) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Welcome to ultimateVE", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Create a project to start editing, or import one from a file.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (unreadableCount > 0) UnreadableNote(unreadableCount)
    }
}

@Composable
private fun UnreadableNote(count: Int) {
    Text(
        "$count project file(s) could not be read.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(top = 12.dp),
    )
}

@Composable
private fun ProjectGrid(state: HubState, onIntent: (HubIntent) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 300.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(state.projects, key = { it.id }) { project -> ProjectCard(project, onIntent) }
        if (state.unreadableCount > 0) {
            item { UnreadableNote(state.unreadableCount) }
        }
    }
}

@Composable
private fun ProjectCard(project: ProjectSummary, onIntent: (HubIntent) -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth().clickable { onIntent(HubIntent.OpenProject(project.id)) }) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(project.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                val s = project.settings
                Text(
                    "${s.width}×${s.height} · ${formatFps(s.fpsNum, s.fpsDen)} fps · ${s.colorSpace}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                        .format(Date(project.lastModifiedMillis)),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) { Text("⋮") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    MenuItem("Rename") { menuOpen = false; onIntent(HubIntent.RequestRename(project)) }
                    MenuItem("Duplicate") { menuOpen = false; onIntent(HubIntent.Clone(project.id)) }
                    MenuItem("Export") { menuOpen = false; onIntent(HubIntent.RequestExport(project)) }
                    MenuItem("Delete") { menuOpen = false; onIntent(HubIntent.RequestDelete(project)) }
                }
            }
        }
    }
}

@Composable
private fun MenuItem(label: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, onClick = onClick)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NewProjectDialog(draft: NewProjectDraft, onIntent: (HubIntent) -> Unit) {
    AlertDialog(
        onDismissRequest = { onIntent(HubIntent.DismissDialogs) },
        title = { Text("New project") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { onIntent(HubIntent.DraftNameChanged(it)) },
                    label = { Text("Name") },
                    singleLine = true,
                )
                Text("Resolution", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProjectPresets.resolutions.forEach { preset ->
                        FilterChip(
                            selected = preset == draft.resolution,
                            onClick = { onIntent(HubIntent.DraftResolutionSelected(preset)) },
                            label = { Text(preset.label) },
                        )
                    }
                }
                Text("Frame rate", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProjectPresets.fps.forEach { preset ->
                        FilterChip(
                            selected = preset == draft.fps,
                            onClick = { onIntent(HubIntent.DraftFpsSelected(preset)) },
                            label = { Text(preset.label) },
                        )
                    }
                }
                Text("Colour space", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProjectPresets.colorSpaces.forEach { preset ->
                        FilterChip(
                            selected = preset == draft.colorSpace,
                            onClick = { onIntent(HubIntent.DraftColorSpaceSelected(preset)) },
                            label = { Text(preset.label) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onIntent(HubIntent.ConfirmCreate) }, enabled = draft.canCreate) {
                Text("Create")
            }
        },
        dismissButton = { TextButton(onClick = { onIntent(HubIntent.DismissDialogs) }) { Text("Cancel") } },
    )
}

@Composable
private fun TextDialog(
    title: String,
    value: String,
    confirmLabel: String,
    onValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = value, onValueChange = onValueChange, singleLine = true) },
        confirmButton = { TextButton(onClick = onConfirm, enabled = value.isNotBlank()) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
