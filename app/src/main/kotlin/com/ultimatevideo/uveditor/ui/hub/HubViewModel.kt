package com.ultimatevideo.uveditor.ui.hub

import androidx.lifecycle.viewModelScope
import com.ultimatevideo.uveditor.data.ProjectError
import com.ultimatevideo.uveditor.data.ProjectNames
import com.ultimatevideo.uveditor.data.ProjectRepository
import com.ultimatevideo.uveditor.data.ProjectSummary
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
) : MviViewModel<HubState, HubIntent, HubEffect>(HubState()) {

    init {
        onIntent(HubIntent.LoadEngineInfo)
        onIntent(HubIntent.Refresh)
    }

    override fun onIntent(intent: HubIntent) {
        when (intent) {
            HubIntent.LoadEngineInfo -> loadEngineInfo()
            HubIntent.Refresh -> refresh()

            HubIntent.ShowNewProject -> reduce { copy(newProjectDraft = NewProjectDraft(name = suggestedName(projects))) }
            is HubIntent.DraftNameChanged -> reduceDraft { copy(name = intent.name) }
            is HubIntent.DraftResolutionSelected -> reduceDraft { copy(resolution = intent.preset) }
            is HubIntent.DraftFpsSelected -> reduceDraft { copy(fps = intent.preset) }
            is HubIntent.DraftColorSpaceSelected -> reduceDraft { copy(colorSpace = intent.preset) }
            HubIntent.ConfirmCreate -> confirmCreate()

            is HubIntent.OpenProject -> emit(HubEffect.OpenEditor(intent.projectId))
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
                val imported = projects.importFrom(intent.uri)
                refreshNow()
                emit(HubEffect.ShowMessage("Imported \"${imported.name}\""))
            }

            HubIntent.DismissDialogs ->
                reduce { copy(newProjectDraft = null, renameDraft = null, deleteTarget = null) }
        }
    }

    private fun suggestedName(existing: List<ProjectSummary>): String =
        ProjectNames.unique(DEFAULT_PROJECT_NAME, existing.map { it.name }, ProjectRepository.MAX_NAME_LENGTH) { b, n -> "$b $n" }

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
            copy(isLoading = false, projects = listing.projects, unreadableCount = listing.unreadable.size)
        }
    }

    private fun confirmCreate() {
        if (state.value.newNameTaken) return
        val draft = state.value.newProjectDraft?.takeIf { it.canCreate } ?: return
        val settings = ProjectSettingsDto(
            width = draft.resolution.width,
            height = draft.resolution.height,
            fpsNum = draft.fps.num,
            fpsDen = draft.fps.den,
            colorSpace = draft.colorSpace.id,
        )
        launchProjectOp {
            projects.create(draft.name, settings)
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
    }
}
