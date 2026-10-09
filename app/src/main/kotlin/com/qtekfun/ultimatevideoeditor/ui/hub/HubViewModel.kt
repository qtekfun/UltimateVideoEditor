package com.qtekfun.ultimatevideoeditor.ui.hub

import com.qtekfun.ultimatevideoeditor.R
import com.qtekfun.ultimatevideoeditor.ui.text.UiText
import androidx.lifecycle.viewModelScope
import com.qtekfun.ultimatevideoeditor.data.ClipPeeker
import com.qtekfun.ultimatevideoeditor.data.ImportReport
import com.qtekfun.ultimatevideoeditor.data.MatchedClip
import com.qtekfun.ultimatevideoeditor.data.interchange.BundleChoice
import com.qtekfun.ultimatevideoeditor.data.interchange.BundleWriteResult
import com.qtekfun.ultimatevideoeditor.data.MediaImportException
import com.qtekfun.ultimatevideoeditor.data.NewProjectDefaults
import com.qtekfun.ultimatevideoeditor.data.ProjectError
import com.qtekfun.ultimatevideoeditor.data.ProjectNames
import com.qtekfun.ultimatevideoeditor.data.ProjectRepository
import com.qtekfun.ultimatevideoeditor.data.ProjectSummary
import com.qtekfun.ultimatevideoeditor.data.SavedProjectChoices
import com.qtekfun.ultimatevideoeditor.data.SessionStore
import com.qtekfun.ultimatevideoeditor.data.model.ProjectSettingsDto
import com.qtekfun.ultimatevideoeditor.engine.EngineClient
import com.qtekfun.ultimatevideoeditor.engine.EngineException
import com.qtekfun.ultimatevideoeditor.mvi.MviViewModel
import com.qtekfun.ultimatevideoeditor.ui.export.ExportBar
import com.qtekfun.ultimatevideoeditor.ui.export.ExportJobHost
import com.qtekfun.ultimatevideoeditor.ui.export.ImportJob
import com.qtekfun.ultimatevideoeditor.ui.export.ImportJobHost
import com.qtekfun.ultimatevideoeditor.ui.export.ImportJobState
import com.qtekfun.ultimatevideoeditor.ui.export.ImportReportText
import com.qtekfun.ultimatevideoeditor.ui.export.importViewFor
import com.qtekfun.ultimatevideoeditor.ui.export.exportBarFor
import com.qtekfun.ultimatevideoeditor.ui.library.BundleExportDraft
import com.qtekfun.ultimatevideoeditor.ui.library.BundleExportText
import com.qtekfun.ultimatevideoeditor.ui.library.ImportReportNotes
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HubViewModel(
    private val engine: EngineClient,
    private val projects: ProjectRepository,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val session: SessionStore? = null,
    private val defaults: NewProjectDefaults? = null,
    private val peeker: ClipPeeker? = null,
    private val mediaFolders: com.qtekfun.ultimatevideoeditor.data.interchange.MediaFolderSettings? = null,
    /** The process-wide export (see ExportCenter); the bar at the bottom mirrors it. Null in tests that do not need it. */
    private val exportJobs: ExportJobHost? = null,
    /** The process-wide project backup (see ExportCenter); its bar mirrors it, and exporting a bundle starts it. Null runs the backup inside this screen (tests). */
    private val bundleJobs: com.qtekfun.ultimatevideoeditor.ui.export.BundleJobHost? = null,
    /** The process-wide project import (see ExportCenter); its bar mirrors it, and importing starts it. Null runs the import inside this screen (tests). */
    private val importJobs: ImportJobHost? = null,
    /** Layout, sort key and direction survive restarts; the default remembers nothing. */
    private val viewStore: HubViewStore = NoHubViewStore,
    /** Measures the storage card off the main thread; null leaves the card out (tests that do not need it). */
    private val storageScanner: StorageScanner? = null,
    /** The name a new project starts with, in the language in use when the sheet opens (the base of "New project 2"). */
    private val defaultProjectName: () -> String = { "New project" },
) : MviViewModel<HubState, HubIntent, HubEffect>(HubState().withView(viewStore.load())) {

    /** Read once, before this process marks anything: what the previous run left open. */
    private val unfinishedProjectId: String? = session?.unfinishedProjectId()

    /** Set once the user has opened a project or dismissed the offer, so a refresh never offers it again. */
    private var resumeHandled = false

    /** The running storage scan; a newer request replaces it. */
    private var storageJob: kotlinx.coroutines.Job? = null

    /** The package waiting for the user to pick a media folder. */
    private var pendingImportUri: String? = null

    init {
        // Read from the process-wide state, not from anything this screen did: the bar is right after the screen is left and
        // entered again, after rotation, and when the export was started from an editor.
        exportJobs?.let { jobs -> viewModelScope.launch { jobs.state.collect { job -> reduce { copy(exportBar = exportBarFor(job)) } } } }
        bundleJobs?.let { jobs -> viewModelScope.launch { jobs.state.collect { job -> reduce { copy(bundleBar = com.qtekfun.ultimatevideoeditor.ui.export.bundleViewFor(job)) } } } }
        importJobs?.let { jobs -> viewModelScope.launch { jobs.state.collect { job -> onImportState(jobs, job) } } }
        onIntent(HubIntent.LoadEngineInfo)
        onIntent(HubIntent.Refresh)
    }

    /**
     * Mirrors the process-wide import into the bar and answers the ends that need the list: a finished import refreshes it; one that
     * ended before anything was shown says so in a message (and lists what it left out); a package that needs a media folder asks
     * for one; a cancelled one says nothing was added. Failures stay in the bar until dismissed.
     */
    private fun onImportState(jobs: ImportJobHost, job: ImportJobState) {
        reduce { copy(importBar = importViewFor(job)) }
        when (job) {
            is ImportJobState.NeedsMediaFolder -> {
                // A package holds footage that has to go somewhere the user knows about: ask for the folder, then start again.
                pendingImportUri = job.uri
                reduce { copy(mediaFolderPrompt = true) }
                jobs.acknowledge()
            }
            is ImportJobState.Cancelled -> {
                emit(HubEffect.ShowMessage(UiText.res(R.string.hub_msg_import_cancelled)))
                jobs.acknowledge()
            }
            is ImportJobState.Done -> {
                viewModelScope.launch { refreshNow() }
                if (job.quick) {
                    emit(HubEffect.ShowMessage(importMessage(job.report)))
                    importNotesOf(job.report)?.let { notes -> reduce { copy(importNotes = notes) } }
                    jobs.acknowledge()
                }
            }
            else -> Unit
        }
    }

    override fun onIntent(intent: HubIntent) {
        when (intent) {
            HubIntent.LoadEngineInfo -> loadEngineInfo()
            HubIntent.Refresh -> refresh()

            HubIntent.ShowNewProject -> reduce { copy(newProjectDraft = initialDraft(projects)) }
            is HubIntent.DraftNameChanged -> reduceDraft { copy(name = intent.name) }
            is HubIntent.DraftAspectSelected -> reduceDraft {
                // Switching to a custom size starts from the pixels the selectors gave, so it is an edit and not a blank.
                if (intent.preset.custom && !aspect.custom) copy(aspect = intent.preset, customWidth = width, customHeight = height)
                else copy(aspect = intent.preset)
            }
            is HubIntent.DraftTierSelected -> reduceDraft {
                if (intent.preset.custom && !tier.custom) copy(tier = intent.preset, customShortSide = minOf(width, height))
                else copy(tier = intent.preset)
            }
            is HubIntent.DraftCustomWidthChanged -> reduceDraft { copy(customWidth = typedPixels(intent.text)) }
            is HubIntent.DraftCustomHeightChanged -> reduceDraft { copy(customHeight = typedPixels(intent.text)) }
            is HubIntent.DraftCustomShortSideChanged -> reduceDraft { copy(customShortSide = typedPixels(intent.text)) }
            is HubIntent.DraftFpsSelected -> reduceDraft { copy(fps = intent.preset) }
            is HubIntent.DraftColorSpaceSelected -> reduceDraft { copy(colorSpace = intent.preset) }
            is HubIntent.DraftQuickPreset -> reduceDraft {
                copy(
                    aspect = intent.preset.aspect,
                    tier = intent.preset.tier,
                    fps = intent.preset.fps,
                    colorSpace = intent.preset.colorSpace,
                    startMode = if (intent.preset.matchFirstClip) StartMode.MATCH_FIRST_CLIP else StartMode.BLANK,
                    matchError = null,
                )
            }
            is HubIntent.DraftStartModeSelected -> reduceDraft { copy(startMode = intent.mode, matchError = null) }
            is HubIntent.MatchFromClip -> matchFromClip(intent.uri)
            HubIntent.ConfirmCreate -> confirmCreate()

            is HubIntent.SearchChanged -> reduce { copy(query = intent.text) }
            HubIntent.ToggleSearch -> reduce { toggledSearch() }
            is HubIntent.SortSelected -> changeView { withSort(intent.sort) }
            HubIntent.ToggleSortDirection -> changeView { copy(sortAscending = !sortAscending) }
            is HubIntent.ViewModeSelected -> changeView { copy(viewMode = intent.mode) }

            is HubIntent.EnterSelection -> reduce { enterSelection(intent.projectId) }
            is HubIntent.ToggleSelected -> reduce { toggled(intent.projectId) }
            HubIntent.SelectAll -> reduce { selectAllVisible() }
            HubIntent.ExitSelection -> reduce { exitSelection() }
            HubIntent.DeleteSelected -> reduce { if (selecting) copy(deleteTargets = selectedProjects) else this }
            HubIntent.DuplicateSelected -> duplicateSelected()
            HubIntent.RenameSelected -> onSingleSelected { project ->
                reduce { copy(renameDraft = RenameDraft(project.id, project.name), selected = emptySet()) }
            }
            HubIntent.ExportSelectedFile -> onSingleSelected { project ->
                reduce { exitSelection() }
                emit(HubEffect.LaunchExportPicker(project.id, "${project.name}.json"))
            }
            HubIntent.ExportSelectedBundle -> onSingleSelected { project ->
                reduce { exitSelection() }
                openBundleDialog(project, includeMedia = false)
            }
            HubIntent.RefreshStorage -> refreshStorage()

            is HubIntent.OpenProject -> {
                resumeHandled = true
                reduce { copy(resumeProject = null) }
                emit(HubEffect.OpenEditor(intent.projectId))
            }
            is HubIntent.Clone -> launchProjectOp { projects.clone(intent.projectId); refreshNow() }

            is HubIntent.RequestRename ->
                reduce { copy(renameDraft = RenameDraft(intent.project.id, intent.project.name)) }
            is HubIntent.RenameNameChanged -> reduce { copy(renameDraft = renameDraft?.copy(name = intent.name)) }
            HubIntent.ConfirmRename -> confirmRename()

            is HubIntent.RequestDelete -> reduce { copy(deleteTargets = listOf(intent.project)) }
            HubIntent.ConfirmDelete -> confirmDelete()

            is HubIntent.RequestExport ->
                emit(HubEffect.LaunchExportPicker(intent.project.id, "${intent.project.name}.json"))
            is HubIntent.ExportTo -> launchProjectOp {
                projects.exportTo(intent.projectId, intent.uri)
                emit(HubEffect.ShowMessage(UiText.res(R.string.hub_msg_project_exported)))
            }
            is HubIntent.ImportFrom -> if (importJobs != null) {
                // Returns at once: the picker's result handler never waits for the file. The work is on the IO dispatcher.
                val job = ImportJob(intent.uri) { observer -> projects.importWithReport(intent.uri, observer) }
                val refused = importJobs.start(job) as? com.qtekfun.ultimatevideoeditor.ui.export.BundleStart.Refused
                if (refused != null) emit(HubEffect.ShowMessage(refused.reason))
            } else {
                launchProjectOp {
                    try {
                        val report = projects.importWithReport(intent.uri)
                        refreshNow()
                        emit(HubEffect.ShowMessage(importMessage(report)))
                        importNotesOf(report)?.let { notes -> reduce { copy(importNotes = notes) } }
                    } catch (e: ProjectError.MediaFolderRequired) {
                        // A package holds footage that has to go somewhere the user knows about: ask for the folder, then continue.
                        pendingImportUri = intent.uri
                        reduce { copy(mediaFolderPrompt = true) }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: ProjectError) {
                        throw e
                    } catch (e: Exception) {
                        // Whatever went wrong is shown with its cause: an import never fails silently.
                        emit(HubEffect.ShowMessage(UiText.res(R.string.hub_msg_import_failed, e.javaClass.simpleName, e.message.orEmpty())))
                    }
                }
            }
            HubIntent.ChooseMediaFolder -> {
                reduce { copy(mediaFolderPrompt = false) }
                emit(HubEffect.LaunchMediaFolderPicker)
            }
            is HubIntent.MediaFolderPicked -> {
                val settings = mediaFolders
                val retry = pendingImportUri
                pendingImportUri = null
                if (settings == null) {
                    emit(HubEffect.ShowMessage(UiText.res(R.string.hub_msg_folder_unavailable)))
                } else {
                    try {
                        settings.set(intent.uri)
                        emit(HubEffect.ShowMessage(UiText.res(R.string.hub_msg_folder_set)))
                        if (retry != null) onIntent(HubIntent.ImportFrom(retry))
                    } catch (e: SecurityException) {
                        emit(HubEffect.ShowMessage(UiText.res(R.string.folder_access_failed, e.message.orEmpty())))
                    }
                }
            }
            HubIntent.DismissMediaFolderPrompt -> {
                pendingImportUri = null
                reduce { copy(mediaFolderPrompt = false) }
            }
            HubIntent.CancelImport -> importJobs?.cancel()
            HubIntent.DismissImportBar -> importJobs?.acknowledge()
            HubIntent.ShowImportDetails -> importJobs?.showDetails()
            HubIntent.OpenImported -> state.value.importBar?.projectId?.let {
                importJobs?.acknowledge()
                resumeHandled = true
                reduce { copy(resumeProject = null) }
                emit(HubEffect.OpenEditor(it))
            }
            is HubIntent.RequestExportBundle -> openBundleDialog(intent.project, intent.includeMedia)
            is HubIntent.BundleChoiceChanged ->
                reduce { copy(bundleExport = bundleExport?.let { it.copy(draft = it.draft.copy(choice = intent.choice)) }) }
            HubIntent.DismissBundleExport -> reduce { copy(bundleExport = null) }
            HubIntent.ConfirmBundleExport -> {
                val dialog = state.value.bundleExport
                if (dialog != null && dialog.draft.canExport) {
                    reduce { copy(bundleExport = null) }
                    emit(HubEffect.LaunchBundleExportPicker(dialog.project.id, "${dialog.project.name}.uvbundle", dialog.draft.choice))
                }
            }
            is HubIntent.ExportBundleTo -> if (bundleJobs != null) {
                startBundleJob(bundleJobs, intent)
            } else {
                launchProjectOp {
                    val result = projects.exportBundle(intent.projectId, intent.uri, intent.choice)
                    emit(HubEffect.ShowMessage(bundleExportMessage(intent.choice, result)))
                }
            }
            HubIntent.DismissImportNotes -> reduce { copy(importNotes = null) }

            is HubIntent.RecoverProject -> launchProjectOp {
                val project = projects.recover(intent.projectId)
                refreshNow()
                emit(HubEffect.ShowMessage(UiText.res(R.string.hub_msg_recovered, project.name)))
            }
            is HubIntent.DeleteUnreadable -> launchProjectOp {
                projects.delete(intent.projectId)
                refreshNow()
                emit(HubEffect.ShowMessage(UiText.res(R.string.hub_msg_removed_unreadable)))
            }
            HubIntent.ResumeSession -> state.value.resumeProject?.let {
                resumeHandled = true
                reduce { copy(resumeProject = null) }
                emit(HubEffect.OpenEditor(it.id))
            }
            HubIntent.DismissResume -> {
                resumeHandled = true
                session?.markClosed()
                reduce { copy(resumeProject = null) }
            }

            HubIntent.CancelExport -> exportJobs?.cancel()
            HubIntent.DismissExportBar -> exportJobs?.acknowledge()
            HubIntent.ShareExport -> (state.value.exportBar as? ExportBar.Finished)?.let { emit(HubEffect.ShareExport(it.uri)) }
            HubIntent.OpenExportProject -> state.value.exportBar?.let { emit(HubEffect.OpenExport(it.projectId)) }
            HubIntent.CancelBundle -> bundleJobs?.cancel()
            HubIntent.DismissBundleBar -> bundleJobs?.acknowledge()
            HubIntent.ShowBundleDetails -> bundleJobs?.showDetails()
            HubIntent.ShareBundle -> state.value.bundleBar?.takeIf { it.canShare }?.uri?.let { emit(HubEffect.ShareBundle(it)) }
            HubIntent.DismissDialogs ->
                reduce { copy(newProjectDraft = null, renameDraft = null, deleteTargets = emptyList(), bundleExport = null) }
        }
    }

    /**
     * Hands the backup to the process-wide executor, which keeps running when this screen is left, and which refuses (with words)
     * while a movie export or another backup runs. The progress dialog opens by itself; the bar and the notification follow.
     */
    private fun startBundleJob(jobs: com.qtekfun.ultimatevideoeditor.ui.export.BundleJobHost, intent: HubIntent.ExportBundleTo) {
        val name = state.value.projects.firstOrNull { it.id == intent.projectId }?.name ?: "project"
        val job = com.qtekfun.ultimatevideoeditor.ui.export.BundleJob(intent.projectId, name, intent.uri) { observer ->
            projects.exportBundle(intent.projectId, intent.uri, intent.choice, observer = observer)
        }
        val refused = jobs.start(job) as? com.qtekfun.ultimatevideoeditor.ui.export.BundleStart.Refused ?: return
        emit(HubEffect.ShowMessage(refused.reason))
    }

    /** Applies a change of layout or order and remembers it. */
    private fun changeView(change: HubState.() -> HubState) {
        reduce(change)
        val now = state.value
        viewStore.save(HubViewPrefs(now.viewMode, now.sort, now.sortAscending))
    }

    /** Runs [action] for the one ticked project; with none or several ticked it only says why not. */
    private fun onSingleSelected(action: (ProjectSummary) -> Unit) {
        val project = state.value.selectedProjects.singleOrNull()
        if (project == null) {
            if (state.value.selecting) emit(HubEffect.ShowMessage(SelectionActions.ONE_AT_A_TIME))
            return
        }
        action(project)
    }

    private fun duplicateSelected() {
        val targets = state.value.selectedProjects
        if (targets.isEmpty()) return
        launchProjectOp {
            var failure: String? = null
            var made = 0
            for (target in targets) {
                try {
                    projects.clone(target.id)
                    made++
                } catch (e: ProjectError) {
                    failure = failure ?: e.message
                }
            }
            reduce { exitSelection() }
            refreshNow()
            if (failure != null) emit(HubEffect.ShowMessage(UiText.res(R.string.hub_msg_duplicated_some, made, targets.size, failure)))
            else if (made > 1) emit(HubEffect.ShowMessage(UiText.plural(R.plurals.hub_msg_duplicated, made)))
        }
    }

    /** Measures the app's storage on the work dispatcher; the card shows the last result meanwhile. */
    private fun refreshStorage() {
        val scanner = storageScanner ?: return
        storageJob?.cancel()
        storageJob = viewModelScope.launch {
            val snapshot = withContext(workDispatcher) { scanner.scan() }
            reduce { copy(storage = snapshot) }
        }
    }

    private fun suggestedName(existing: List<ProjectSummary>): String =
        ProjectNames.unique(defaultProjectName(), existing.map { it.name }, ProjectRepository.MAX_NAME_LENGTH) { b, n -> "$b $n" }

    /** The sheet as it opens: the last choices (or the defaults) and a free project name. */
    private fun initialDraft(existing: List<ProjectSummary>): NewProjectDraft {
        val saved = defaults?.load()
        val base = if (saved == null) NewProjectDraft() else draftFrom(saved)
        return base.copy(name = suggestedName(existing))
    }

    private fun draftFrom(saved: SavedProjectChoices): NewProjectDraft = NewProjectDraft(
        aspect = ProjectPresets.aspectById(saved.aspectId) ?: ProjectPresets.defaultAspect,
        tier = ProjectPresets.tierById(saved.tierId) ?: ProjectPresets.defaultTier,
        customWidth = saved.customWidth.takeIf { it > 0 } ?: 1920,
        customHeight = saved.customHeight.takeIf { it > 0 } ?: 1080,
        customShortSide = saved.customShortSide.takeIf { it > 0 } ?: 1080,
        fps = ProjectPresets.fpsFor(saved.fpsNum, saved.fpsDen),
        colorSpace = ProjectPresets.colorSpaces.firstOrNull { it.id == saved.colorSpaceId } ?: ProjectPresets.defaultColorSpace,
    )

    private fun typedPixels(text: String): Int = text.filter(Char::isDigit).take(MAX_TYPED_DIGITS).toIntOrNull() ?: 0

    /** Reads the picked clip's format and copies it into the draft; a clip that cannot be read is reported in the sheet. */
    private fun matchFromClip(uri: String) {
        val reader = peeker
        if (reader == null) {
            emit(HubEffect.ShowMessage(UiText.res(R.string.hub_msg_peek_unavailable)))
            return
        }
        reduceDraft { copy(isMatching = true, matchError = null) }
        viewModelScope.launch {
            try {
                val clip = reader.peek(uri)
                reduceDraft { matched(clip) }
            } catch (e: MediaImportException) {
                reduceDraft { copy(isMatching = false, matchError = e.message?.let { UiText.Raw(it) } ?: UiText.res(R.string.hub_match_cannot_read)) }
            }
        }
    }

    private fun NewProjectDraft.matched(clip: MatchedClip): NewProjectDraft = copy(
        aspect = ProjectPresets.customAspect,
        customWidth = ProjectSizing.toEven(clip.width),
        customHeight = ProjectSizing.toEven(clip.height),
        fps = if (clip.fpsNum != null && clip.fpsDen != null) ProjectPresets.fpsFor(clip.fpsNum, clip.fpsDen) else fps,
        colorSpace = clip.colorSpace?.let(ProjectPresets::colorSpaceFor) ?: colorSpace,
        matchedClip = clip,
        isMatching = false,
        matchError = null,
    )

    private fun reduceDraft(change: NewProjectDraft.() -> NewProjectDraft) {
        reduce { copy(newProjectDraft = newProjectDraft?.change()) }
    }

    private fun loadEngineInfo() {
        viewModelScope.launch {
            try {
                val version = withContext(workDispatcher) { engine.version() }
                reduce { copy(engineVersion = version, engineError = null) }
            } catch (e: EngineException) {
                reduce { copy(engineVersion = null, engineError = e.message) }
            }
        }
    }

    private fun refresh() {
        launchProjectOp { refreshNow() }
    }

    private suspend fun refreshNow() {
        val listing = projects.list()
        reduce {
            copy(
                isLoading = false,
                projects = listing.projects,
                unreadable = listing.unreadable,
                // Only offered while it still exists and the editor is not already open on it.
                resumeProject = resumeProject?.let { old -> listing.projects.firstOrNull { it.id == old.id } }
                    ?: listing.projects.firstOrNull { it.id == unfinishedProjectId }?.takeIf { !resumeHandled },
            ).prunedSelection()
        }
        refreshStorage()
    }

    private fun confirmCreate() {
        if (state.value.newNameTaken) return
        val draft = state.value.newProjectDraft?.takeIf { it.canCreate } ?: return
        val settings = ProjectSettingsDto(
            width = draft.width,
            height = draft.height,
            fpsNum = draft.fps.num,
            fpsDen = draft.fps.den,
            colorSpace = draft.colorSpace.id,
        )
        launchProjectOp {
            projects.create(draft.name, settings)
            // A format copied from one clip is not a habit: only the selectors' choices are remembered.
            if (draft.startMode == StartMode.BLANK) {
                defaults?.save(
                    SavedProjectChoices(
                        aspectId = draft.aspect.id,
                        tierId = draft.tier.id,
                        customWidth = draft.customWidth,
                        customHeight = draft.customHeight,
                        customShortSide = draft.customShortSide,
                        fpsNum = draft.fps.num,
                        fpsDen = draft.fps.den,
                        colorSpaceId = draft.colorSpace.id,
                    ),
                )
            }
            reduce { copy(newProjectDraft = null) }
            refreshNow()
        }
    }

    private fun confirmRename() {
        if (state.value.renameNameTaken) return
        val draft = state.value.renameDraft ?: return
        launchProjectOp {
            projects.rename(draft.projectId, draft.name)
            reduce { copy(renameDraft = null) }
            refreshNow()
        }
    }

    private fun confirmDelete() {
        val targets = state.value.deleteTargets.ifEmpty { return }
        launchProjectOp {
            var failure: String? = null
            var failed = 0
            for (target in targets) {
                try {
                    projects.delete(target.id)
                } catch (e: ProjectError) {
                    failed++
                    failure = failure ?: e.message
                }
            }
            reduce { copy(deleteTargets = emptyList(), selected = emptySet()) }
            refreshNow()
            if (failed > 0) emit(HubEffect.ShowMessage(UiText.res(R.string.hub_msg_delete_some_failed, failed, targets.size, failure.orEmpty())))
            else if (targets.size > 1) emit(HubEffect.ShowMessage(UiText.plural(R.plurals.hub_msg_deleted, targets.size)))
        }
    }

    /** Runs a store operation; a [ProjectError] is shown to the user instead of being dropped. */
    /** What an import did: the project's name and, for a bundle, what became of its media. */
    internal fun importMessage(report: ImportReport): UiText = ImportReportText.message(report)

    internal fun bundleExportMessage(choice: BundleChoice, result: BundleWriteResult): UiText =
        BundleExportText.exportMessage(UiText.res(R.string.bundle_exported), choice, result)

    /** The list of LUTs and fonts an import could not install, or null when it went fully through. */
    internal fun importNotesOf(report: ImportReport): ImportReportNotes? = ImportReportText.notes(report)

    /** Opens the bundle dialog at once and fills in what the project holds when it has been measured. */
    private fun openBundleDialog(project: ProjectSummary, includeMedia: Boolean) {
        val choice = BundleChoice(includeMedia = includeMedia)
        reduce { copy(bundleExport = HubBundleExport(project, BundleExportDraft(choice = choice))) }
        viewModelScope.launch {
            val draft = try {
                BundleExportDraft(preview = projects.bundlePreview(project.id), choice = state.value.bundleExport?.draft?.choice ?: choice)
            } catch (e: ProjectError) {
                BundleExportDraft(choice = choice, failed = e.message?.let { UiText.Raw(it) } ?: UiText.res(R.string.bundle_project_unreadable))
            }
            // The dialog may have been closed or reopened for another project while the project was measured.
            reduce { if (bundleExport?.project?.id == project.id) copy(bundleExport = HubBundleExport(project, draft)) else this }
        }
    }

    private fun launchProjectOp(block: suspend () -> Unit): kotlinx.coroutines.Job {
        return viewModelScope.launch {
            try {
                block()
            } catch (e: ProjectError) {
                reduce { copy(isLoading = false) }
                emit(HubEffect.ShowMessage(e.message?.let { UiText.Raw(it) } ?: UiText.res(R.string.hub_msg_operation_failed)))
            }
        }
    }

    private companion object {
        const val MAX_TYPED_DIGITS = 5
    }
}
