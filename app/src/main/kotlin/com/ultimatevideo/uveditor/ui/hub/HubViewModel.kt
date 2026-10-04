package com.ultimatevideo.uveditor.ui.hub

import androidx.lifecycle.viewModelScope
import com.ultimatevideo.uveditor.data.ClipPeeker
import com.ultimatevideo.uveditor.data.ImportReport
import com.ultimatevideo.uveditor.data.MatchedClip
import com.ultimatevideo.uveditor.data.interchange.BundleWriteResult
import com.ultimatevideo.uveditor.data.MediaImportException
import com.ultimatevideo.uveditor.data.NewProjectDefaults
import com.ultimatevideo.uveditor.data.ProjectError
import com.ultimatevideo.uveditor.data.ProjectNames
import com.ultimatevideo.uveditor.data.ProjectRepository
import com.ultimatevideo.uveditor.data.ProjectSummary
import com.ultimatevideo.uveditor.data.SavedProjectChoices
import com.ultimatevideo.uveditor.data.SessionStore
import com.ultimatevideo.uveditor.data.model.ProjectSettingsDto
import com.ultimatevideo.uveditor.engine.EngineClient
import com.ultimatevideo.uveditor.engine.EngineException
import com.ultimatevideo.uveditor.mvi.MviViewModel
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
) : MviViewModel<HubState, HubIntent, HubEffect>(HubState()) {

    /** Read once, before this process marks anything: what the previous run left open. */
    private val unfinishedProjectId: String? = session?.unfinishedProjectId()

    /** Set once the user has opened a project or dismissed the offer, so a refresh never offers it again. */
    private var resumeHandled = false

    init {
        onIntent(HubIntent.LoadEngineInfo)
        onIntent(HubIntent.Refresh)
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
            is HubIntent.SortSelected -> reduce { copy(sort = intent.sort) }

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

            is HubIntent.RequestDelete -> reduce { copy(deleteTarget = intent.project) }
            HubIntent.ConfirmDelete -> confirmDelete()

            is HubIntent.RequestExport ->
                emit(HubEffect.LaunchExportPicker(intent.project.id, "${intent.project.name}.json"))
            is HubIntent.ExportTo -> launchProjectOp {
                projects.exportTo(intent.projectId, intent.uri)
                emit(HubEffect.ShowMessage("Project exported"))
            }
            is HubIntent.ImportFrom -> launchProjectOp {
                val report = projects.importWithReport(intent.uri)
                refreshNow()
                emit(HubEffect.ShowMessage(importMessage(report)))
            }
            is HubIntent.RequestExportBundle ->
                emit(HubEffect.LaunchBundleExportPicker(intent.project.id, "${intent.project.name}.uvbundle", intent.includeMedia))
            is HubIntent.ExportBundleTo -> launchProjectOp {
                val result = projects.exportBundle(intent.projectId, intent.uri, intent.includeMedia)
                emit(HubEffect.ShowMessage(bundleExportMessage(intent.includeMedia, result)))
            }

            is HubIntent.RecoverProject -> launchProjectOp {
                val project = projects.recover(intent.projectId)
                refreshNow()
                emit(HubEffect.ShowMessage("Recovered \"${project.name}\" from its last good copy"))
            }
            is HubIntent.DeleteUnreadable -> launchProjectOp {
                projects.delete(intent.projectId)
                refreshNow()
                emit(HubEffect.ShowMessage("Removed the unreadable project"))
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

            HubIntent.DismissDialogs ->
                reduce { copy(newProjectDraft = null, renameDraft = null, deleteTarget = null) }
        }
    }

    private fun suggestedName(existing: List<ProjectSummary>): String =
        ProjectNames.unique(DEFAULT_PROJECT_NAME, existing.map { it.name }, ProjectRepository.MAX_NAME_LENGTH) { b, n -> "$b $n" }

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
            emit(HubEffect.ShowMessage("Reading a clip's format is not available"))
            return
        }
        reduceDraft { copy(isMatching = true, matchError = null) }
        viewModelScope.launch {
            try {
                val clip = reader.peek(uri)
                reduceDraft { matched(clip) }
            } catch (e: MediaImportException) {
                reduceDraft { copy(isMatching = false, matchError = e.message ?: "Cannot read the selected file") }
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
                resumeProject = resumeProject ?: listing.projects.firstOrNull { it.id == unfinishedProjectId }?.takeIf { !resumeHandled },
            )
        }
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
        val target = state.value.deleteTarget ?: return
        launchProjectOp {
            projects.delete(target.id)
            reduce { copy(deleteTarget = null) }
            refreshNow()
        }
    }

    /** Runs a store operation; a [ProjectError] is shown to the user instead of being dropped. */
    /** What an import did: the project's name and, for a bundle, what became of its media. */
    internal fun importMessage(report: ImportReport): String {
        val name = report.project.name
        val bundle = report.bundle ?: return "Imported \"$name\""
        return buildString {
            append("Imported \"$name\"")
            if (bundle.mediaCopied > 0) append(". ${bundle.mediaCopied} media file${if (bundle.mediaCopied == 1) "" else "s"} came with it")
            if (bundle.relinked > 0) append(". ${bundle.relinked} found on this device by name and size")
            if (bundle.missing.isNotEmpty()) {
                append(". Missing (relink in the editor): ${bundle.missing.take(3).joinToString()}")
                if (bundle.missing.size > 3) append(" and ${bundle.missing.size - 3} more")
            }
        }
    }

    internal fun bundleExportMessage(includeMedia: Boolean, result: BundleWriteResult): String = buildString {
        append("Bundle exported")
        if (includeMedia) append(" with ${result.mediaCopied} media file${if (result.mediaCopied == 1) "" else "s"}")
        if (result.mediaSkipped.isNotEmpty()) {
            append(". Not copied (cannot be read): ${result.mediaSkipped.take(3).joinToString()}")
            if (result.mediaSkipped.size > 3) append(" and ${result.mediaSkipped.size - 3} more")
        }
    }

    private fun launchProjectOp(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: ProjectError) {
                reduce { copy(isLoading = false) }
                emit(HubEffect.ShowMessage(e.message ?: "Project operation failed"))
            }
        }
    }

    private companion object {
        const val DEFAULT_PROJECT_NAME = "New project"
        const val MAX_TYPED_DIGITS = 5
    }
}
