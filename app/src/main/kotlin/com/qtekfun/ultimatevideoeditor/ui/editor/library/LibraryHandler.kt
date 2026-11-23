package com.qtekfun.ultimatevideoeditor.ui.editor.library

import com.qtekfun.ultimatevideoeditor.R
import com.qtekfun.ultimatevideoeditor.data.MediaCaches
import com.qtekfun.ultimatevideoeditor.data.MissingMedia
import com.qtekfun.ultimatevideoeditor.ui.editor.*
import com.qtekfun.ultimatevideoeditor.ui.editor.interchange.InterchangeHandler
import com.qtekfun.ultimatevideoeditor.ui.editor.playback.PlaybackController
import com.qtekfun.ultimatevideoeditor.ui.editor.timeline.SelectionHandler
import com.qtekfun.ultimatevideoeditor.ui.editor.tray.usageCounts
import com.qtekfun.ultimatevideoeditor.ui.library.Library
import com.qtekfun.ultimatevideoeditor.ui.library.LibraryQuery
import com.qtekfun.ultimatevideoeditor.ui.text.UiText

/**
 * The media library sheet: searching and filtering, tags and notes, removing files that nothing uses, and finding where a file is used on the
 * timeline. Exports to other tools start here (the bundle dialog) and are carried out by the interchange handler.
 */
internal class LibraryHandler(
    host: EditorHost,
    private val mediaCaches: MediaCaches,
    private val playback: PlaybackController,
    private val interchangeExport: InterchangeHandler,
    private val selection: SelectionHandler,
) : EditorHost by host {

    fun libraryIntent(intent: LibraryIntent) {
        when (intent) {
            is LibraryIntent.Open -> reduce { copy(library = library.copy(open = true, highlightAssetId = intent.assetId)) }
            LibraryIntent.Close -> reduce { copy(library = library.copy(open = false, highlightAssetId = null)) }
            LibraryIntent.RevealSelectedInLibrary -> {
                val clipId = state.value.selectedClipId
                val assetId = clipId?.let { Library.assetOfClip(history.timeline, it) }
                if (clipId != null && assetId == null) emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_a_title_or_sticker_has)))
                reduce { copy(library = library.copy(open = true, highlightAssetId = assetId, query = if (assetId != null) LibraryQuery() else library.query)) }
            }
            is LibraryIntent.QueryChanged -> reduce { copy(library = library.copy(query = library.query.copy(text = intent.text))) }
            is LibraryIntent.FilterSelected -> reduce { copy(library = library.copy(query = library.query.copy(filter = intent.filter))) }
            is LibraryIntent.TagSelected -> reduce { copy(library = library.copy(query = library.query.copy(tag = intent.tag))) }
            is LibraryIntent.EditAsset -> {
                val asset = state.value.assets.firstOrNull { it.id == intent.assetId } ?: return
                val draft = AssetEditDraft(asset.id, MissingMedia.nameOf(asset), asset.tags.joinToString(", "), asset.note.orEmpty())
                reduce { copy(library = library.copy(editing = draft)) }
            }
            is LibraryIntent.TagsChanged -> reduce { copy(library = library.copy(editing = library.editing?.copy(tags = intent.text))) }
            is LibraryIntent.NoteChanged -> reduce { copy(library = library.copy(editing = library.editing?.copy(note = intent.text.take(Library.MAX_NOTE_LENGTH)))) }
            LibraryIntent.ConfirmAssetEdit -> confirmAssetEdit()
            LibraryIntent.DismissAssetEdit -> reduce { copy(library = library.copy(editing = null)) }
            LibraryIntent.AskDeleteUnused -> askDeleteUnused()
            LibraryIntent.ConfirmDeleteUnused -> confirmDeleteUnused()
            LibraryIntent.DismissDeleteUnused -> reduce { copy(library = library.copy(confirmDeleteUnused = null)) }
            is LibraryIntent.FindInTimeline -> findInTimeline(intent.assetId)
            is LibraryIntent.RequestExport -> interchangeExport.requestExport(intent.kind)
            is LibraryIntent.ExportTo -> interchangeExport.exportTo(intent.kind, intent.uri)
            is LibraryIntent.BundleChoiceChanged ->
                reduce { copy(library = library.copy(bundleDraft = library.bundleDraft?.copy(choice = intent.choice))) }
            LibraryIntent.DismissBundleExport -> reduce { copy(library = library.copy(bundleDraft = null)) }
            LibraryIntent.ConfirmBundleExport -> interchangeExport.confirmBundleExport()
        }
    }

    /** Tags and notes belong to the library, like its order: saved with the project, not part of the undo history. */
    private fun confirmAssetEdit() {
        val draft = state.value.library.editing ?: return
        var assets = Library.withTags(state.value.assets, draft.assetId, Library.parseTags(draft.tags))
        assets = Library.withNote(assets, draft.assetId, draft.note)
        reduce { copy(assets = assets, library = library.copy(editing = null)) }
        scheduleSave()
    }

    /**
     * How many uses each file has anywhere the user could still get back to: the timeline, every state in the
     * undo and redo history, and the clipboard. Cleaning up must not remove a file that an undo would need.
     */
    private fun protectedUsage(): Map<String, Int> {
        val counts = HashMap<String, Int>()
        for (timeline in history.reachableTimelines()) {
            for ((id, n) in usageCounts(timeline)) counts[id] = maxOf(counts[id] ?: 0, n)
        }
        selection.clipboardAssetIds().forEach { counts[it] = maxOf(counts[it] ?: 0, 1) }
        return counts
    }

    private fun askDeleteUnused() {
        val removable = Library.unused(state.value.assets, protectedUsage())
        if (removable.isEmpty()) {
            emit(EditorEffect.ShowMessage(if (Library.unused(state.value.assets, usageCounts(history.timeline)).isEmpty()) UiText.res(R.string.ed_vm_every_file_in_the_library) else UiText.res(R.string.ed_vm_nothing_can_be_removed_while)))
            return
        }
        reduce { copy(library = library.copy(confirmDeleteUnused = removable.size)) }
    }

    private fun confirmDeleteUnused() {
        val usage = protectedUsage()
        val removable = Library.unused(state.value.assets, usage).map { it.id }.toSet()
        reduce { copy(assets = Library.withoutUnused(assets, usage), library = library.copy(confirmDeleteUnused = null)) }
        if (removable.isEmpty()) return
        removable.forEach { mediaCaches.invalidate(it) }
        scheduleSave()
        emit(EditorEffect.ShowMessage(UiText.plural(R.plurals.ed_vm_removed_unused, removable.size)))
    }

    private fun findInTimeline(assetId: String) {
        val timeline = history.timeline
        val uses = Library.uses(timeline, assetId, state.value.fps)
        val use = Library.nextUse(uses, state.value.playhead.value)
        if (use == null) {
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_that_file_is_not_used)))
            return
        }
        val trackId = timeline.trackOfClip(use.clipId)?.id
        playback.seekTo(use.startFrame)
        reduce {
            copy(
                selectedClipId = use.clipId,
                selectedClipIds = emptySet(),
                selectedTrackId = trackId ?: selectedTrackId,
                library = library.copy(open = false),
            )
        }
        val position = uses.indexOf(use) + 1
        emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_use_of_choose_find_in, position, uses.size, use.trackLabel)))
    }
}
