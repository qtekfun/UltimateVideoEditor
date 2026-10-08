package com.qtekfun.ultimatevideoeditor.ui.hub

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qtekfun.ultimatevideoeditor.data.ProjectThumbnails
import com.qtekfun.ultimatevideoeditor.data.UnreadableProject
import com.qtekfun.ultimatevideoeditor.data.interchange.BundleChoice
import com.qtekfun.ultimatevideoeditor.ui.export.shareBundleIntent
import com.qtekfun.ultimatevideoeditor.ui.export.shareExportedMovie
import com.qtekfun.ultimatevideoeditor.ui.library.BundleExportDialog
import com.qtekfun.ultimatevideoeditor.ui.library.ImportProgressDialog
import com.qtekfun.ultimatevideoeditor.ui.library.ImportReportDialog
import com.qtekfun.ultimatevideoeditor.ui.templates.TemplateWizardSheet
import com.qtekfun.ultimatevideoeditor.ui.templates.TemplateWizardViewModel
import kotlinx.coroutines.launch

@Composable
fun HubScreen(
    viewModel: HubViewModel,
    onOpenProject: (String) -> Unit,
    thumbnails: ProjectThumbnails? = null,
    templates: TemplateWizardViewModel? = null,
    onOpenAbout: () -> Unit = {},
    /** Open the editor of a project with its export dialog showing (the export bar was tapped). */
    onOpenExport: (String) -> Unit = onOpenProject,
) {
    val context = LocalContext.current
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
    // Opens the picker in ultimateVE/Project-Backups of the media folder when one is chosen.
    val backupsHint = remember(context) { com.qtekfun.ultimatevideoeditor.data.PreferencesMediaFolderSettings(context.applicationContext) }
    val bundleContract = remember { CreateDocumentAt("application/octet-stream") }
    val bundleLauncher = rememberLauncherForActivityResult(
        // A generic type keeps the suggested ".uvbundle" name (a zip type makes Android add ".zip").
        bundleContract,
    ) { uri ->
        backupsHint.backupsPickerDone(saved = uri != null)
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
                    // Creating the folder talks to the document provider: off the main thread.
                    bundleContract.initialUri = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { backupsHint.backupsPickerUri() }
                    bundleLauncher.launch(effect.suggestedFileName)
                }
                is HubEffect.OpenEditor -> onOpenProject(effect.projectId)
                is HubEffect.OpenExport -> onOpenExport(effect.projectId)
                is HubEffect.ShareExport -> shareExportedMovie(context, effect.uri)
                is HubEffect.ShareBundle -> context.startActivity(shareBundleIntent(effect.uri))
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
    val scope = rememberCoroutineScope()
    // Back leaves selection mode first, then closes the search field; with neither open it goes on to the system.
    BackHandler(enabled = state.handlesBack) {
        onIntent(if (state.selecting) HubIntent.ExitSelection else HubIntent.ToggleSearch)
    }
    // Pick up storage changes made elsewhere (clearing caches in About, a long editing session).
    LifecycleResumeEffect(Unit) {
        onIntent(HubIntent.RefreshStorage)
        onPauseOrDispose { }
    }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            if (state.selecting) {
                SelectionTopBar(
                    count = state.selected.size,
                    allSelected = state.selected.size >= state.visibleProjects.size,
                    onClose = { onIntent(HubIntent.ExitSelection) },
                    onSelectAll = { onIntent(HubIntent.SelectAll) },
                )
            } else {
                HubTopBar(
                    searchOpen = state.searchOpen,
                    canSearch = state.projects.isNotEmpty(),
                    onToggleSearch = { onIntent(HubIntent.ToggleSearch) },
                    onImport = onImport,
                    onOpenAbout = onOpenAbout,
                    onTemplates = onTemplates,
                )
            }
        },
        floatingActionButton = {
            if (!state.selecting) {
                ExtendedFloatingActionButton(
                    onClick = { onIntent(HubIntent.ShowNewProject) },
                    icon = { Icon(NewProjectIcon, contentDescription = null) },
                    text = { Text("New project") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        // The Scaffold puts the New project button above this bar.
        bottomBar = {
            Column {
                state.exportBar?.let { ExportBarView(it, onIntent) }
                state.bundleBar?.let { BundleBarView(it, onIntent) }
                if (state.selecting) {
                    SelectionBar(state.selectionActions, onIntent) { reason -> scope.launch { snackbar.showSnackbar(reason) } }
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // A broken engine is worth a line; a working one is in About.
            state.engineError?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (state.searchOpen) SearchField(state, onIntent)
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.isLoading -> Unit
                    state.projects.isEmpty() -> WelcomeState(state.unreadable, onIntent, onImport, onTemplates)
                    else -> ProjectLibrary(state, onIntent, thumbnails, onOpenAbout)
                }
            }
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
                        "ultimateVE creates its own subfolder called ultimateVE inside that folder, and puts the footage in ultimateVE/Media, " +
                        "in one folder named after the project. Nothing is put loose in the folder you pick. " +
                        "Deleting the project later does not delete the files. You can change the folder in About, under Media folder.",
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
    if (state.deleteTargets.isNotEmpty()) {
        val targets = state.deleteTargets
        AlertDialog(
            onDismissRequest = { onIntent(HubIntent.DismissDialogs) },
            title = { Text(if (targets.size == 1) "Delete project?" else "Delete ${targets.size} projects?") },
            text = {
                Text(
                    if (targets.size == 1) "\"${targets.single().name}\" will be removed from this device. Source media is not touched."
                    else "${targets.size} projects will be removed from this device. Source media is not touched.",
                )
            },
            confirmButton = { TextButton(onClick = { onIntent(HubIntent.ConfirmDelete) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { onIntent(HubIntent.DismissDialogs) }) { Text("Cancel") } },
        )
    }
}

/** The search field under the top bar; it takes the focus when it opens. */
@Composable
private fun SearchField(state: HubState, onIntent: (HubIntent) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    OutlinedTextField(
        value = state.query,
        onValueChange = { onIntent(HubIntent.SearchChanged(it)) },
        label = { Text("Search projects") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).focusRequester(focus),
    )
}

/**
 * The library: Continue card, storage summary, sort bar, then the projects as dense rows or as a poster grid. One lazy
 * grid serves both layouts (one column or adaptive columns); on a wide screen the content keeps a readable width.
 */
@Composable
private fun ProjectLibrary(state: HubState, onIntent: (HubIntent) -> Unit, thumbnails: ProjectThumbnails?, onOpenAbout: () -> Unit) {
    val visible = state.visibleProjects
    val grid = state.viewMode == HubViewMode.GRID
    // Read once per composition: relative dates do not need to tick while the screen is open.
    val now = remember(state.projects) { System.currentTimeMillis() }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyVerticalGrid(
            columns = if (grid) GridCells.Adaptive(minSize = POSTER_MIN_WIDTH) else GridCells.Fixed(1),
            modifier = Modifier.fillMaxHeight().widthIn(max = if (grid) GRID_MAX_WIDTH else LIST_MAX_WIDTH),
            contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 96.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(if (grid) 16.dp else 8.dp),
        ) {
            state.continueCard?.let { model ->
                item(key = "continue", span = { GridItemSpan(maxLineSpan) }) { ContinueCard(model, thumbnails, onIntent) }
            }
            item(key = "storage", span = { GridItemSpan(maxLineSpan) }) {
                StorageCard(state.projects.size, state.storage, onOpenAbout)
            }
            item(key = "sort", span = { GridItemSpan(maxLineSpan) }) { SortBar(state, onIntent) }
            if (visible.isEmpty()) {
                item(key = "nomatch", span = { GridItemSpan(maxLineSpan) }) { NoMatch(state.query) }
            }
            items(visible, key = { it.id }) { project ->
                val selected = project.id in state.selected
                val bytes = state.storage?.perProject?.get(project.id)
                if (grid) {
                    ProjectPoster(project, now, state.selecting, selected, thumbnails, onIntent, Modifier.animateItem())
                } else {
                    ProjectRow(project, bytes, now, state.selecting, selected, thumbnails, onIntent, Modifier.animateItem())
                }
            }
            if (state.unreadable.isNotEmpty()) {
                item(key = "unreadable", span = { GridItemSpan(maxLineSpan) }) { UnreadableProjects(state.unreadable, onIntent) }
            }
        }
    }
}

private val POSTER_MIN_WIDTH = 160.dp
private val LIST_MAX_WIDTH = 720.dp
private val GRID_MAX_WIDTH = 1100.dp

/** The first-run and empty-library screen: the mark, a short welcome and the ways to get a project. */
@Composable
private fun WelcomeState(
    unreadable: List<UnreadableProject>,
    onIntent: (HubIntent) -> Unit,
    onImport: () -> Unit,
    onTemplates: (() -> Unit)?,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        WordmarkGlyph(size = 72.dp)
        Text("Welcome to ultimateVE", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp))
        Text(
            "Create a project to start editing, or bring one in. Your footage stays where it is; projects only point to it.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        Button(onClick = { onIntent(HubIntent.ShowNewProject) }, modifier = Modifier.padding(top = 20.dp)) {
            Icon(NewProjectIcon, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("New project", modifier = Modifier.padding(start = 8.dp))
        }
        OutlinedButton(onClick = onImport) { Text("Import a project") }
        if (onTemplates != null) TextButton(onClick = onTemplates) { Text("Start from a template") }
        if (unreadable.isNotEmpty()) UnreadableProjects(unreadable, onIntent)
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

/** The system "save as" picker, opened at [initialUri] (a document of a tree the app may use) when that is set. */
private class CreateDocumentAt(mimeType: String) : ActivityResultContracts.CreateDocument(mimeType) {
    var initialUri: String? = null

    override fun createIntent(context: android.content.Context, input: String): android.content.Intent {
        val intent = super.createIntent(context, input)
        initialUri?.let { intent.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, android.net.Uri.parse(it)) }
        return intent
    }
}
