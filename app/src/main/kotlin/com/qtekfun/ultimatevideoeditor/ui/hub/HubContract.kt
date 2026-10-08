package com.qtekfun.ultimatevideoeditor.ui.hub

import com.qtekfun.ultimatevideoeditor.data.MatchedClip
import com.qtekfun.ultimatevideoeditor.data.ProjectNames
import com.qtekfun.ultimatevideoeditor.data.ProjectSummary
import com.qtekfun.ultimatevideoeditor.data.UnreadableProject
import com.qtekfun.ultimatevideoeditor.data.interchange.BundleChoice
import com.qtekfun.ultimatevideoeditor.mvi.UiEffect
import com.qtekfun.ultimatevideoeditor.mvi.UiIntent
import com.qtekfun.ultimatevideoeditor.mvi.UiState
import com.qtekfun.ultimatevideoeditor.ui.export.ExportBar
import com.qtekfun.ultimatevideoeditor.ui.library.BundleExportDraft
import com.qtekfun.ultimatevideoeditor.ui.library.ImportReportNotes
import java.util.Locale

/** The bundle dialog of one project: what could go in and what is ticked. */
data class HubBundleExport(val project: ProjectSummary, val draft: BundleExportDraft)

/**
 * The New project sheet. The picture size is [aspect] x [tier] (or typed pixels for a custom aspect, or a
 * typed short side for a custom tier); [startMode] chooses between those selectors and a size taken from a clip.
 */
data class NewProjectDraft(
    val name: String = "",
    val aspect: AspectPreset = ProjectPresets.defaultAspect,
    val tier: ResolutionTier = ProjectPresets.defaultTier,
    /** Typed pixels for a custom aspect; 0 means "not typed". */
    val customWidth: Int = 1920,
    val customHeight: Int = 1080,
    /** Typed short side for a custom tier; 0 means "not typed". */
    val customShortSide: Int = 1080,
    val fps: FpsPreset = ProjectPresets.defaultFps,
    val colorSpace: ColorSpacePreset = ProjectPresets.defaultColorSpace,
    val startMode: StartMode = StartMode.BLANK,
    /** The clip the format was copied from, once picked in [StartMode.MATCH_FIRST_CLIP]. */
    val matchedClip: MatchedClip? = null,
    val isMatching: Boolean = false,
    val matchError: String? = null,
) {
    val width: Int get() = size.first
    val height: Int get() = size.second

    private val size: Pair<Int, Int>
        get() = when {
            aspect.custom -> customWidth to customHeight
            tier.custom -> ProjectSizing.sizeFor(aspect, customShortSide.coerceAtLeast(0))
            else -> ProjectSizing.sizeFor(aspect, tier.shortSide)
        }

    /** The exact picture size the project will have. */
    val resolution: ResolutionPreset get() = ResolutionPreset(sizeLabel(width, height), width, height)

    /** Why the typed size cannot be used, or null. */
    val sizeProblem: String?
        get() = when {
            !aspect.custom && tier.custom && customShortSide <= 0 -> "Enter the short side in pixels"
            else -> ProjectSizing.problemWith(width, height)
        }

    val canCreate: Boolean
        get() = name.isNotBlank() && sizeProblem == null && (startMode == StartMode.BLANK || matchedClip != null)

    /** "1920 × 1080, 30 fps, SDR". */
    val summary: String
        get() = "${sizeLabel(width, height)}, ${fps.label} fps, ${colorSpaceShortName(colorSpace.id)}"
}

data class RenameDraft(val projectId: String, val name: String)

data class HubState(
    val isLoading: Boolean = true,
    val projects: List<ProjectSummary> = emptyList(),
    /** Project folders whose file cannot be read; each can be recovered from a backup (if one is usable) or deleted. */
    val unreadable: List<UnreadableProject> = emptyList(),
    /** The project that was open when the app last stopped without leaving the editor: offered for reopening. */
    val resumeProject: ProjectSummary? = null,
    val newProjectDraft: NewProjectDraft? = null,
    val renameDraft: RenameDraft? = null,
    /** The projects the delete confirmation names; empty when it is closed. */
    val deleteTargets: List<ProjectSummary> = emptyList(),
    val query: String = "",
    /** The search field is showing (the top bar's search icon). */
    val searchOpen: Boolean = false,
    val sort: ProjectSort = ProjectSort.LAST_EDITED,
    val sortAscending: Boolean = ProjectSort.LAST_EDITED.defaultAscending,
    val viewMode: HubViewMode = HubViewMode.LIST,
    /** Ids of the projects ticked in selection mode; selection mode is on exactly while this is not empty. */
    val selected: Set<String> = emptySet(),
    /** What the app keeps on disk; null until the first scan ends. Never measured on the main thread. */
    val storage: StorageSnapshot? = null,
    val engineVersion: String? = null,
    val engineError: String? = null,
    /** The dialog that asks what a bundle of [HubBundleExport.project] should contain, or null when closed. */
    val bundleExport: HubBundleExport? = null,
    /** What an imported bundle could not install (LUTs and fonts), listed until dismissed; null when nothing went wrong. */
    val importNotes: ImportReportNotes? = null,
    /** The project import (`.uvbundle`, LumaFusion package, project file) that is running or ended and was not dismissed yet; shown in a bar like [bundleBar]. */
    val importBar: com.qtekfun.ultimatevideoeditor.ui.export.ImportView? = null,
    /** A package needs somewhere to put its footage and no media folder is chosen: the dialog that asks for one. */
    val mediaFolderPrompt: Boolean = false,
    /** The export that is running, or that ended and was not dismissed yet (bar above the New project button); null when none. */
    val exportBar: ExportBar? = null,
    /** The project backup (`.uvbundle`) that is running or ended and was not dismissed yet; shown in a bar like [exportBar]. */
    val bundleBar: com.qtekfun.ultimatevideoeditor.ui.export.BundleView? = null,
) : UiState {
    val unreadableCount: Int get() = unreadable.size

    val selecting: Boolean get() = selected.isNotEmpty()

    val selectedProjects: List<ProjectSummary> get() = projects.filter { it.id in selected }

    /** Back closes selection mode first, then the search field. */
    val handlesBack: Boolean get() = selecting || searchOpen

    /** The list as shown: filtered by the search text (ignoring case) while the search is open, then sorted. */
    val visibleProjects: List<ProjectSummary>
        get() {
            val needle = query.trim().lowercase(Locale.ROOT)
            val filtered = if (!searchOpen || needle.isEmpty()) projects else projects.filter { it.name.lowercase(Locale.ROOT).contains(needle) }
            return ProjectSorting.sort(filtered, sort, sortAscending, storage?.perProject.orEmpty())
        }

    /**
     * The card at the top: the project that was open when the app stopped (with the "app closed" text) or else the
     * most recently edited one. Hidden while searching, selecting and when there are no projects.
     */
    val continueCard: ContinueModel?
        get() {
            if (searchOpen || selecting || projects.isEmpty()) return null
            resumeProject?.let { return ContinueModel(it, resume = true) }
            return ContinueModel(projects.maxBy { it.lastModifiedMillis }, resume = false)
        }

    /** What the selection bar can do for the current selection. */
    val selectionActions: SelectionActions get() = SelectionActions.of(selected.size)

    val newNameTaken: Boolean
        get() = newProjectDraft?.let { ProjectNames.isTaken(it.name, projects.map(ProjectSummary::name)) } == true

    val renameNameTaken: Boolean
        get() = renameDraft?.let { draft ->
            ProjectNames.isTaken(draft.name, projects.filter { it.id != draft.projectId }.map(ProjectSummary::name))
        } == true
}

/** The Continue card: [resume] means the app closed while [project] was open, which changes its text and buttons. */
data class ContinueModel(val project: ProjectSummary, val resume: Boolean)

/**
 * Which actions of the selection bar are enabled for [count] ticked projects. Duplicate and delete work on several;
 * exporting is one at a time (the app runs one long export job at once) and so is rename.
 */
data class SelectionActions(
    val duplicate: Boolean,
    val delete: Boolean,
    val rename: Boolean,
    val exportFile: Boolean,
    val exportBundle: Boolean,
    /** Why the export actions are disabled, for the tooltip; null when they are enabled. */
    val exportDisabledReason: String?,
) {
    companion object {
        const val ONE_AT_A_TIME = "Export works on one project at a time. Select just one."

        fun of(count: Int) = SelectionActions(
            duplicate = count >= 1,
            delete = count >= 1,
            rename = count == 1,
            exportFile = count == 1,
            exportBundle = count == 1,
            exportDisabledReason = if (count > 1) ONE_AT_A_TIME else null,
        )
    }
}

sealed interface HubIntent : UiIntent {
    data object LoadEngineInfo : HubIntent
    data object Refresh : HubIntent

    data object ShowNewProject : HubIntent
    data class DraftNameChanged(val name: String) : HubIntent
    data class DraftAspectSelected(val preset: AspectPreset) : HubIntent
    data class DraftTierSelected(val preset: ResolutionTier) : HubIntent
    data class DraftCustomWidthChanged(val text: String) : HubIntent
    data class DraftCustomHeightChanged(val text: String) : HubIntent
    data class DraftCustomShortSideChanged(val text: String) : HubIntent
    data class DraftFpsSelected(val preset: FpsPreset) : HubIntent
    data class DraftColorSpaceSelected(val preset: ColorSpacePreset) : HubIntent
    data class DraftQuickPreset(val preset: QuickPreset) : HubIntent
    data class DraftStartModeSelected(val mode: StartMode) : HubIntent

    /** The user picked a clip to copy the format from. */
    data class MatchFromClip(val uri: String) : HubIntent
    data object ConfirmCreate : HubIntent

    data class SearchChanged(val text: String) : HubIntent
    data object ToggleSearch : HubIntent
    data class SortSelected(val sort: ProjectSort) : HubIntent
    data object ToggleSortDirection : HubIntent
    data class ViewModeSelected(val mode: HubViewMode) : HubIntent

    /** Long press on a project: selection mode starts with it ticked. */
    data class EnterSelection(val projectId: String) : HubIntent
    data class ToggleSelected(val projectId: String) : HubIntent
    data object SelectAll : HubIntent
    data object ExitSelection : HubIntent
    data object DuplicateSelected : HubIntent
    data object DeleteSelected : HubIntent
    data object RenameSelected : HubIntent
    data object ExportSelectedFile : HubIntent
    data object ExportSelectedBundle : HubIntent

    /** Measure the app's storage again (screen resumed). */
    data object RefreshStorage : HubIntent

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

    /**
     * A `.uvbundle` of the project. Opens the dialog that asks what goes in (media files, LUTs, fonts), with
     * [includeMedia] as the starting position of the media switch.
     */
    data class RequestExportBundle(val project: ProjectSummary, val includeMedia: Boolean = false) : HubIntent
    data class BundleChoiceChanged(val choice: BundleChoice) : HubIntent
    data object ConfirmBundleExport : HubIntent
    data object DismissBundleExport : HubIntent
    data class ExportBundleTo(val projectId: String, val uri: String, val choice: BundleChoice) : HubIntent
    data object DismissImportNotes : HubIntent
    data object CancelImport : HubIntent

    /** The package needs a media folder: the user agreed to pick one, picked [uri], or declined. */
    data object ChooseMediaFolder : HubIntent
    data class MediaFolderPicked(val uri: String) : HubIntent
    data object DismissMediaFolderPrompt : HubIntent

    data class RecoverProject(val projectId: String) : HubIntent
    data class DeleteUnreadable(val projectId: String) : HubIntent
    data object ResumeSession : HubIntent
    data object DismissResume : HubIntent

    data object DismissDialogs : HubIntent

    /** The bar's buttons: stop the running export, forget a finished or failed one, share the file, open the project's editor on its export. */
    data object CancelExport : HubIntent
    data object DismissExportBar : HubIntent
    data object ShareExport : HubIntent
    data object OpenExportProject : HubIntent

    /** The backup bar's buttons: stop it, forget a finished one, share the file, open the progress dialog. */
    data object CancelBundle : HubIntent
    data object DismissBundleBar : HubIntent
    data object ShareBundle : HubIntent
    data object ShowBundleDetails : HubIntent

    /** The import bar's buttons: forget a finished or failed import, open the imported project, open the progress dialog. */
    data object DismissImportBar : HubIntent
    data object OpenImported : HubIntent
    data object ShowImportDetails : HubIntent
}

sealed interface HubEffect : UiEffect {
    data class ShowMessage(val text: String) : HubEffect
    data class LaunchExportPicker(val projectId: String, val suggestedFileName: String) : HubEffect
    data class LaunchBundleExportPicker(val projectId: String, val suggestedFileName: String, val choice: BundleChoice) : HubEffect
    data class OpenEditor(val projectId: String) : HubEffect
    data object LaunchMediaFolderPicker : HubEffect
    data class ShareExport(val uri: String) : HubEffect
    data class ShareBundle(val uri: String) : HubEffect

    /** Open the editor of [projectId] with its export dialog showing. */
    data class OpenExport(val projectId: String) : HubEffect
}
