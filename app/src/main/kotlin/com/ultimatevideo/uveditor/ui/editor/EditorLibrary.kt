package com.ultimatevideo.uveditor.ui.editor

import com.ultimatevideo.uveditor.domain.MarkerColor
import com.ultimatevideo.uveditor.ui.library.LibraryFilter
import com.ultimatevideo.uveditor.ui.library.LibraryQuery

/** What the media library sheet is showing; pure data, the files themselves are `EditorState.assets`. */
data class LibraryUiState(
    val open: Boolean = false,
    val query: LibraryQuery = LibraryQuery(),
    /** A file to bring into view and mark (opened from a selected clip, or after "find in library"). */
    val highlightAssetId: String? = null,
    /** The tags and note dialog of one file, or null when closed. */
    val editing: AssetEditDraft? = null,
    /** The "remove the files nothing uses" confirmation, with how many it would remove. */
    val confirmDeleteUnused: Int? = null,
    /** An export in progress ("Writing the bundle…"), or null when idle. */
    val busy: String? = null,
)

/** The tags (comma separated) and the note being typed for one library file. */
data class AssetEditDraft(val assetId: String, val name: String, val tags: String, val note: String)

/** The note and colour being typed for the marker at [frame]. */
data class MarkerEditDraft(val markerId: String, val frame: Long, val note: String, val color: MarkerColor?)

/** The ways a project can leave the app besides the movie export. */
enum class InterchangeKind(val label: String, val mime: String, val extension: String) {
    // A generic type keeps the name as suggested: with a specific one Android appends its own extension
    // (".uvbundle.zip", ".fcpxml.xml"), which other tools would not recognise.
    BUNDLE("Project bundle (names and sizes)", INTERCHANGE_MIME, "uvbundle"),
    BUNDLE_WITH_MEDIA("Project bundle with media files", INTERCHANGE_MIME, "uvbundle"),
    EDL("EDL (CMX3600, one file per track)", INTERCHANGE_MIME, "edl"),
    FCPXML("Final Cut Pro XML (FCPXML 1.9)", INTERCHANGE_MIME, "fcpxml"),
}

/** The type used when asking for a file to write, so the picker keeps the suggested name. */
const val INTERCHANGE_MIME = "application/octet-stream"

/** An EDL of several tracks is a zip of the files, and a zip is named as such. */
const val ZIP_MIME = "application/zip"

/** The media library (tags, notes, usage, cleanup), marker notes, and exports to other tools. */
sealed interface LibraryIntent : EditorIntent {
    /** Opens the sheet; [assetId] is brought into view and marked. */
    data class Open(val assetId: String? = null) : LibraryIntent
    data object Close : LibraryIntent

    /** Opens the sheet on the file the selected clip reads (find in library). */
    data object RevealSelectedInLibrary : LibraryIntent

    data class QueryChanged(val text: String) : LibraryIntent
    data class FilterSelected(val filter: LibraryFilter) : LibraryIntent
    data class TagSelected(val tag: String?) : LibraryIntent

    data class EditAsset(val assetId: String) : LibraryIntent
    data class TagsChanged(val text: String) : LibraryIntent
    data class NoteChanged(val text: String) : LibraryIntent
    data object ConfirmAssetEdit : LibraryIntent
    data object DismissAssetEdit : LibraryIntent

    data object AskDeleteUnused : LibraryIntent
    data object ConfirmDeleteUnused : LibraryIntent
    data object DismissDeleteUnused : LibraryIntent

    /** Selects the next clip on the timeline that uses [assetId] and moves the playhead there; again steps to the one after. */
    data class FindInTimeline(val assetId: String) : LibraryIntent

    /** Asks the screen for a place to write the export (the document picker); [ExportTo] follows. */
    data class RequestExport(val kind: InterchangeKind) : LibraryIntent
    data class ExportTo(val kind: InterchangeKind, val uri: String) : LibraryIntent

    /** Opens the note and colour dialog of the marker at the playhead. */
    data object OpenMarkerEdit : LibraryIntent
    data class MarkerNoteChanged(val text: String) : LibraryIntent
    data class MarkerColorSelected(val color: MarkerColor?) : LibraryIntent
    data object ConfirmMarkerEdit : LibraryIntent
    data object DismissMarkerEdit : LibraryIntent
}
