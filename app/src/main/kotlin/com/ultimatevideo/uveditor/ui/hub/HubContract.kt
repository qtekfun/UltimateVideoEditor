package com.ultimatevideo.uveditor.ui.hub

import com.ultimatevideo.uveditor.data.ProjectSummary
import com.ultimatevideo.uveditor.mvi.UiEffect
import com.ultimatevideo.uveditor.mvi.UiIntent
import com.ultimatevideo.uveditor.mvi.UiState

data class NewProjectDraft(
    val name: String = "New project",
    val resolution: ResolutionPreset = ProjectPresets.defaultResolution,
    val fps: FpsPreset = ProjectPresets.defaultFps,
    val colorSpace: ColorSpacePreset = ProjectPresets.defaultColorSpace,
) {
    val canCreate: Boolean get() = name.isNotBlank()
}

data class RenameDraft(val projectId: String, val name: String)

data class HubState(
    val isLoading: Boolean = true,
    val projects: List<ProjectSummary> = emptyList(),
    val unreadableCount: Int = 0,
    val newProjectDraft: NewProjectDraft? = null,
    val renameDraft: RenameDraft? = null,
    val deleteTarget: ProjectSummary? = null,
    val engineVersion: String? = null,
    val engineError: String? = null,
) : UiState

sealed interface HubIntent : UiIntent {
    data object LoadEngineInfo : HubIntent
    data object Refresh : HubIntent

    data object ShowNewProject : HubIntent
    data class DraftNameChanged(val name: String) : HubIntent
    data class DraftResolutionSelected(val preset: ResolutionPreset) : HubIntent
    data class DraftFpsSelected(val preset: FpsPreset) : HubIntent
    data class DraftColorSpaceSelected(val preset: ColorSpacePreset) : HubIntent
    data object ConfirmCreate : HubIntent

    data class OpenProject(val projectId: String) : HubIntent
    data class Clone(val projectId: String) : HubIntent

    data class RequestRename(val project: ProjectSummary) : HubIntent
    data class RenameNameChanged(val name: String) : HubIntent
    data object ConfirmRename : HubIntent

    data class RequestDelete(val project: ProjectSummary) : HubIntent
    data object ConfirmDelete : HubIntent

    data class RequestExport(val project: ProjectSummary) : HubIntent
    data class ExportTo(val projectId: String, val uri: String) : HubIntent
    data class ImportFrom(val uri: String) : HubIntent

    data object DismissDialogs : HubIntent
}

sealed interface HubEffect : UiEffect {
    data class ShowMessage(val text: String) : HubEffect
    data class LaunchExportPicker(val projectId: String, val suggestedFileName: String) : HubEffect
    data class OpenEditor(val projectId: String) : HubEffect
}
