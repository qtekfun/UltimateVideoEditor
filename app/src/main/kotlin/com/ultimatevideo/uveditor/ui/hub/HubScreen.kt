package com.ultimatevideo.uveditor.ui.hub

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ultimatevideo.uveditor.data.ProjectOverview
import com.ultimatevideo.uveditor.data.ProjectSummary
import com.ultimatevideo.uveditor.data.ProjectThumbnails
import com.ultimatevideo.uveditor.data.UnreadableProject
import com.ultimatevideo.uveditor.data.interchange.BundleChoice
import com.ultimatevideo.uveditor.ui.library.BundleExportDialog
import com.ultimatevideo.uveditor.ui.library.ImportProgressDialog
import com.ultimatevideo.uveditor.ui.library.ImportReportDialog
import com.ultimatevideo.uveditor.ui.templates.TemplateWizardSheet
import com.ultimatevideo.uveditor.ui.templates.TemplateWizardViewModel
import java.text.DateFormat
import java.util.Date

@Composable
fun HubScreen(
    viewModel: HubViewModel,
    onOpenProject: (String) -> Unit,
    thumbnails: ProjectThumbnails? = null,
    templates: TemplateWizardViewModel? = null,
    onOpenAbout: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var pendingExportId by remember { mutableStateOf<String?>(null) }
    var wizardOpen by remember { mutableStateOf(false) }

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
    var pendingBundle by remember { mutableStateOf<Pair<String, BundleChoice>?>(null) }
    val bundleLauncher = rememberLauncherForActivityResult(
        // A generic type keeps the suggested ".uvbundle" name (a zip type makes Android add ".zip").
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val pending = pendingBundle
        pendingBundle = null
        if (uri != null && pending != null) viewModel.onIntent(HubIntent.ExportBundleTo(pending.first, uri.toString(), pending.second))
    }
    // Only reads the clip's format once; the clip is not added to the project and no permission is kept.
    val matchLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.onIntent(HubIntent.MatchFromClip(uri.toString()))
    }

    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.onIntent(HubIntent.MediaFolderPicked(uri.toString())) else viewModel.onIntent(HubIntent.DismissMediaFolderPrompt)
    }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                HubEffect.LaunchMediaFolderPicker -> folderLauncher.launch(null)
                is HubEffect.ShowMessage -> snackbar.showSnackbar(effect.text)
                is HubEffect.LaunchExportPicker -> {
                    pendingExportId = effect.projectId
                    exportLauncher.launch(effect.suggestedFileName)
                }
                is HubEffect.LaunchBundleExportPicker -> {
                    pendingBundle = effect.projectId to effect.choice
                    bundleLauncher.launch(effect.suggestedFileName)
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
        onPickMatchClip = { matchLauncher.launch(arrayOf("video/*", "image/*")) },
        thumbnails = thumbnails,
        onTemplates = if (templates != null) ({ wizardOpen = true }) else null,
        onOpenAbout = onOpenAbout,
    )
    if (wizardOpen && templates != null) {
        TemplateWizardSheet(
            viewModel = templates,
            projects = state.projects,
            onCreated = {
                wizardOpen = false
                viewModel.onIntent(HubIntent.Refresh)
                onOpenProject(it)
            },
            onDismiss = { wizardOpen = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HubContent(
    state: HubState,
    snackbar: SnackbarHostState,
    onIntent: (HubIntent) -> Unit,
    onImport: () -> Unit,
    onPickMatchClip: () -> Unit = {},
    thumbnails: ProjectThumbnails? = null,
    onTemplates: (() -> Unit)? = null,
    onOpenAbout: () -> Unit = {},
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("ultimateVE") },
                actions = { HubOverflowMenu(onImport, onOpenAbout, onTemplates) },
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
            state.resumeProject?.let { ResumeBanner(it, onIntent) }
            if (state.showSearch) SearchBar(state, onIntent)
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.isLoading -> Unit
                    state.projects.isEmpty() -> WelcomeState(state.unreadable, onIntent)
                    else -> ProjectGrid(state, onIntent, thumbnails)
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

    state.newProjectDraft?.let { NewProjectSheet(it, nameTaken = state.newNameTaken, onIntent = onIntent, onPickClip = onPickMatchClip) }
    state.bundleExport?.let { dialog ->
        BundleExportDialog(
            draft = dialog.draft,
            onChoice = { onIntent(HubIntent.BundleChoiceChanged(it)) },
            onConfirm = { onIntent(HubIntent.ConfirmBundleExport) },
            onDismiss = { onIntent(HubIntent.DismissBundleExport) },
        )
    }
    if (state.mediaFolderPrompt) {
        AlertDialog(
            onDismissRequest = { onIntent(HubIntent.DismissMediaFolderPrompt) },
            title = { Text("Choose a folder for the media") },
            text = {
                Text(
                    "This LumaFusion package contains the footage of the project. It is copied into a folder you choose, " +
                        "on this device or on a USB drive or SD card, so you can see and manage the files. " +
                        "Deleting the project later does not delete them. You can change the folder in About, under Media folder.",
                )
            },
            confirmButton = { TextButton(onClick = { onIntent(HubIntent.ChooseMediaFolder) }) { Text("Choose folder") } },
            dismissButton = { TextButton(onClick = { onIntent(HubIntent.DismissMediaFolderPrompt) }) { Text("Cancel") } },
        )
    }
    state.importProgress?.let { progress -> ImportProgressDialog(progress) { onIntent(HubIntent.CancelImport) } }
    state.importNotes?.let { notes -> ImportReportDialog(notes) { onIntent(HubIntent.DismissImportNotes) } }
    state.renameDraft?.let { draft ->
        TextDialog(
            title = "Rename project",
            value = draft.name,
            nameTaken = state.renameNameTaken,
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

/** The one overflow menu of the top bar: importing a project file lives here, not on its own button. */
@Composable
private fun HubOverflowMenu(onImport: () -> Unit, onOpenAbout: () -> Unit, onTemplates: (() -> Unit)? = null) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.semantics { contentDescription = "More options" }) { Text("⋮") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (onTemplates != null) DropdownMenuItem(text = { Text("New from a template…") }, onClick = { open = false; onTemplates() })
            DropdownMenuItem(text = { Text("Import project file or bundle") }, onClick = { open = false; onImport() })
            DropdownMenuItem(text = { Text("About, privacy and help") }, onClick = { open = false; onOpenAbout() })
        }
    }
}

/** Search and sorting, shown once the list is longer than a screen. */
@Composable
private fun SearchBar(state: HubState, onIntent: (HubIntent) -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = state.query,
            onValueChange = { onIntent(HubIntent.SearchChanged(it)) },
            label = { Text("Search projects") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Sort by", style = MaterialTheme.typography.labelMedium)
            ProjectSort.entries.forEach { sort ->
                FilterChip(
                    selected = state.sort == sort,
                    onClick = { onIntent(HubIntent.SortSelected(sort)) },
                    label = { Text(sort.label) },
                )
            }
        }
    }
}

@Composable
private fun WelcomeState(unreadable: List<UnreadableProject>, onIntent: (HubIntent) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Welcome to ultimateVE", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Create a project to start editing, or import one from the ⋮ menu.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (unreadable.isNotEmpty()) UnreadableProjects(unreadable, onIntent)
    }
}

/** Offers to reopen the project that was open when the app last stopped without leaving the editor. */
@Composable
private fun ResumeBanner(project: ProjectSummary, onIntent: (HubIntent) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(modifier = Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "The app closed while \"${project.name}\" was open. Your edits were saved as you made them.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(vertical = 12.dp),
            )
            TextButton(onClick = { onIntent(HubIntent.ResumeSession) }) { Text("Reopen") }
            TextButton(onClick = { onIntent(HubIntent.DismissResume) }) { Text("Dismiss") }
        }
    }
}

/** Project folders that cannot be read: each can be restored from its last good copy when one exists, or removed. */
@Composable
private fun UnreadableProjects(unreadable: List<UnreadableProject>, onIntent: (HubIntent) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(
            if (unreadable.size == 1) "1 project file could not be read." else "${unreadable.size} project files could not be read.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        for (item in unreadable) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.error.message.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f),
                )
                if (item.recoverable) {
                    TextButton(onClick = { onIntent(HubIntent.RecoverProject(item.id)) }) { Text("Recover") }
                }
                TextButton(onClick = { onIntent(HubIntent.DeleteUnreadable(item.id)) }) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun ProjectGrid(state: HubState, onIntent: (HubIntent) -> Unit, thumbnails: ProjectThumbnails?) {
    val visible = state.visibleProjects
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 300.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 88.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (visible.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("No project matches \"${state.query.trim()}\".", style = MaterialTheme.typography.bodyMedium)
            }
        }
        items(visible, key = { it.id }) { project -> ProjectCard(project, onIntent, thumbnails) }
        if (state.unreadable.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { UnreadableProjects(state.unreadable, onIntent) }
        }
    }
}

/** A project: the first frame of its first clip, its name, a short format line, its length and last change. */
@Composable
private fun ProjectCard(project: ProjectSummary, onIntent: (HubIntent) -> Unit, thumbnails: ProjectThumbnails?) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth().clickable { onIntent(HubIntent.OpenProject(project.id)) }) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProjectThumbnail(project, thumbnails)
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text(project.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                val s = project.settings
                Text(
                    "${resolutionShortName(s.width, s.height)} · ${formatFps(s.fpsNum, s.fpsDen)} fps · ${colorSpaceShortName(s.colorSpace)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                val length = if (project.durationFrames > 0) {
                    ProjectOverview.formatDuration(project.durationFrames, s.fpsNum, s.fpsDen)
                } else {
                    "Empty"
                }
                Text(
                    "$length · " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(project.lastModifiedMillis)),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Box {
                IconButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.semantics { contentDescription = "Actions for ${project.name}" },
                ) { Text("⋮") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    MenuItem("Rename") { menuOpen = false; onIntent(HubIntent.RequestRename(project)) }
                    MenuItem("Duplicate") { menuOpen = false; onIntent(HubIntent.Clone(project.id)) }
                    MenuItem("Export project file") { menuOpen = false; onIntent(HubIntent.RequestExport(project)) }
                    // One entry: the dialog it opens asks what the bundle should hold (media files, LUTs, fonts).
                    MenuItem("Export bundle for another phone…") { menuOpen = false; onIntent(HubIntent.RequestExportBundle(project)) }
                    MenuItem("Delete") { menuOpen = false; onIntent(HubIntent.RequestDelete(project)) }
                }
            }
        }
    }
}

/** The cached first frame of the project, made off the main thread; a plain tile while it loads or when there is none. */
@Composable
private fun ProjectThumbnail(project: ProjectSummary, thumbnails: ProjectThumbnails?) {
    val bitmap by produceState<Bitmap?>(initialValue = null, project.id, project.thumbnail) {
        value = thumbnails?.load(project.id, project.thumbnail)
    }
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .width(96.dp)
            .height(64.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = "First frame of ${project.name}",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text("▶", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MenuItem(label: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, onClick = onClick)
}

@Composable
private fun TextDialog(
    title: String,
    value: String,
    nameTaken: Boolean,
    confirmLabel: String,
    onValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                isError = nameTaken,
                supportingText = if (nameTaken) ({ Text(NAME_TAKEN_MESSAGE) }) else null,
            )
        },
        confirmButton = { TextButton(onClick = onConfirm, enabled = value.isNotBlank() && !nameTaken) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
