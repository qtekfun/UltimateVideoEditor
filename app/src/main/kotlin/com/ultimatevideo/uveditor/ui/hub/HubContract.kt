package com.ultimatevideo.uveditor.ui.hub

import com.ultimatevideo.uveditor.data.MatchedClip
import com.ultimatevideo.uveditor.data.ProjectNames
import com.ultimatevideo.uveditor.data.ProjectSummary
import com.ultimatevideo.uveditor.data.UnreadableProject
import com.ultimatevideo.uveditor.data.interchange.BundleChoice
import com.ultimatevideo.uveditor.mvi.UiEffect
import com.ultimatevideo.uveditor.mvi.UiIntent
import com.ultimatevideo.uveditor.mvi.UiState
import com.ultimatevideo.uveditor.ui.export.ExportBar
import com.ultimatevideo.uveditor.ui.library.BundleExportDraft
import com.ultimatevideo.uveditor.ui.library.ImportReportNotes
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

/** The order of the project list. */
enum class ProjectSort(val label: String) {
    RECENT("Recent"),
    NAME("Name"),
}

data class HubState(
    val isLoading: Boolean = true,
    val projects: List<ProjectSummary> = emptyList(),
    /** Project folders whose file cannot be read; each can be recovered from a backup (if one is usable) or deleted. */
    val unreadable: List<UnreadableProject> = emptyList(),
    /** The project that was open when the app last stopped without leaving the editor: offered for reopening. */
    val resumeProject: ProjectSummary? = null,
    val newProjectDraft: NewProjectDraft? = null,
    val renameDraft: RenameDraft? = null,
    val deleteTarget: ProjectSummary? = null,
    val query: String = "",
    val sort: ProjectSort = ProjectSort.RECENT,
    val engineVersion: String? = null,
    val engineError: String? = null,
    /** The dialog that asks what a bundle of [HubBundleExport.project] should contain, or null when closed. */
    val bundleExport: HubBundleExport? = null,
    /** What an imported bundle could not install (LUTs and fonts), listed until dismissed; null when nothing went wrong. */
    val importNotes: ImportReportNotes? = null,
    /** Footage being unpacked from a LumaFusion package; null otherwise. */
    val importProgress: com.ultimatevideo.uveditor.data.interchange.ImportProgress? = null,
    /** A package needs somewhere to put its footage and no media folder is chosen: the dialog that asks for one. */
    val mediaFolderPrompt: Boolean = false,
    /** The export that is running, or that ended and was not dismissed yet (bar above the New project button); null when none. */
    val exportBar: ExportBar? = null,
    /** The project backup (`.uvbundle`) that is running or ended and was not dismissed yet; shown in a bar like [exportBar]. */
    val bundleBar: com.ultimatevideo.uveditor.ui.export.BundleView? = null,
) : UiState {
    val unreadableCount: Int get() = unreadable.size

    /** Search and sorting only appear once the list is longer than a screen. */
    val showSearch: Boolean get() = projects.size > SEARCH_THRESHOLD

    /** The list as shown: filtered by the search text (ignoring case) and sorted. */
    val visibleProjects: List<ProjectSummary>
        get() {
            val needle = query.trim().lowercase(Locale.ROOT)
            val filtered = if (!showSearch || needle.isEmpty()) projects else projects.filter { it.name.lowercase(Locale.ROOT).contains(needle) }
            return when (sort) {
                ProjectSort.RECENT -> filtered.sortedByDescending { it.lastModifiedMillis }
                ProjectSort.NAME -> filtered.sortedBy { it.name.lowercase(Locale.ROOT) }
            }
        }

    val newNameTaken: Boolean
        get() = newProjectDraft?.let { ProjectNames.isTaken(it.name, projects.map(ProjectSummary::name)) } == true

    val renameNameTaken: Boolean
        get() = renameDraft?.let { draft ->
            ProjectNames.isTaken(draft.name, projects.filter { it.id != draft.projectId }.map(ProjectSummary::name))
        } == true

    companion object {
        /** More projects than this and the hub offers search and sorting. */
        const val SEARCH_THRESHOLD = 6
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
    data class SortSelected(val sort: ProjectSort) : HubIntent

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
