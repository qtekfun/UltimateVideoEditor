package com.qtekfun.ultimatevideoeditor.ui.editor.relink

import com.qtekfun.ultimatevideoeditor.R
import com.qtekfun.ultimatevideoeditor.data.MediaCaches
import com.qtekfun.ultimatevideoeditor.data.MediaImportException
import com.qtekfun.ultimatevideoeditor.data.MediaImporter
import com.qtekfun.ultimatevideoeditor.data.MediaProblem
import com.qtekfun.ultimatevideoeditor.data.MissingMedia
import com.qtekfun.ultimatevideoeditor.data.RelinkApply
import com.qtekfun.ultimatevideoeditor.data.RelinkCheck
import com.qtekfun.ultimatevideoeditor.data.RelinkVerdict
import com.qtekfun.ultimatevideoeditor.data.relink.FolderRelinkOutcome
import com.qtekfun.ultimatevideoeditor.data.relink.FolderRelinkProgress
import com.qtekfun.ultimatevideoeditor.data.relink.FolderRelinkRunner
import com.qtekfun.ultimatevideoeditor.data.relink.FolderScanException
import com.qtekfun.ultimatevideoeditor.data.relink.FolderScanner
import com.qtekfun.ultimatevideoeditor.ui.editor.*
import com.qtekfun.ultimatevideoeditor.ui.text.UiText
import com.qtekfun.ultimatevideoeditor.ui.text.reasonOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Relinking missing files: one file at a time from a picked replacement, or every missing file at once by scanning folders the user picked
 * (SPECS 5.40). The scan runs in the background and can be cancelled.
 */
internal class RelinkHandler(
    host: EditorHost,
    private val importer: MediaImporter,
    private val mediaCaches: MediaCaches,
    private val folderScanner: FolderScanner,
) : EditorHost by host {

    /**
     * Points [assetId] at a different file. The new file must be able to stand in for the old one
     * (see [RelinkCheck]); differences that do not prevent it are reported as warnings. This is a saved
     * change of the media library, not a step of the undo history: undoing it would put back a file the
     * user just said does not exist.
     */
    fun relinkAsset(assetId: String, uri: String, fromFolder: Boolean = false) {
        val old = state.value.assets.firstOrNull { it.id == assetId }
        if (old == null) {
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_that_media_is_no_longer)))
            return
        }
        scope.launch {
            val probed = try {
                // A file inside a folder the user granted is read through the folder's permission, which cannot be taken per file.
                if (fromFolder) importer.verify(uri) else importer.import(uri)
            } catch (e: MediaImportException) {
                emit(EditorEffect.ShowMessage(reasonOf(e, UiText.res(R.string.ed_vm_could_not_open_the_file))))
                return@launch
            }
            val fps = state.value.fps
            val others = state.value.assets.filter { it.id != assetId }.map { it.uri }
            val verdict = RelinkCheck.evaluate(old, probed, uri, others, MissingMedia.requiredSourceMicros(history.timeline, assetId, fps))
            val warnings = when (verdict) {
                is RelinkVerdict.Rejected -> {
                    emit(EditorEffect.ShowMessage(verdict.reason))
                    return@launch
                }
                is RelinkVerdict.Accepted -> verdict.warnings
            }
            val relinked = RelinkApply.relinked(old, probed, uri, fps) ?: run {
                emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_the_file_is_too_short)))
                return@launch
            }
            // Waveforms and thumbnails were made from the old file; the new key makes the native side start over.
            mediaCaches.invalidate(assetId)
            assetKeys.rekey(assetId)
            reduce {
                val stillMissing = missingMedia - assetId
                copy(
                    assets = assets.map { if (it.id == assetId) relinked else it },
                    missingMedia = stillMissing,
                    relinkOpen = relinkOpen && (stillMissing.isNotEmpty() || folderRelink is FolderRelinkUi.Done),
                    folderRelink = folderRelink.without(assetId),
                )
            }
            scheduleSave()
            val name = MissingMedia.nameOf(relinked)
            emit(EditorEffect.ShowMessage(if (warnings.isEmpty()) UiText.res(R.string.ed_vm_relinked, name) else UiText.res(R.string.ed_vm_relinked_with_notes, name, UiText.join(". ", warnings))))
        }
    }

    private var folderRelinkJob: Job? = null

    /** What the dialog shows again when a scan is cancelled: the earlier results if the scan was started from them, else the list. */
    private var folderRelinkBeforeScan: FolderRelinkUi = FolderRelinkUi.Idle

    /**
     * Looks in the folder [treeUri] for every media file that cannot be read and relinks what it can identify, in one step
     * (SPECS 5.40). The scan and the checks run off the main thread and can be cancelled; nothing changes until they finish,
     * and then the whole library change is one state update and one save. Like a single relink it is not an undo step.
     */
    fun relinkFromFolder(treeUri: String) = relinkFromFolders(listOf(treeUri))

    /**
     * [relinkFromFolder] for several folders in a row: each one only looks for what the earlier ones did not find, the results are
     * folded into one report and the library changes in one update and one save. A folder that cannot be read is skipped; only if none
     * could be is the user told so.
     */
    fun relinkFromFolders(treeUris: List<String>) {
        if (treeUris.isEmpty() || folderRelinkJob?.isActive == true) return
        val snapshot = state.value
        val missing = snapshot.assets.filter { snapshot.missingMedia[it.id].let { p -> p == MediaProblem.UNREADABLE || p == MediaProblem.PERMISSION_LOST } }
        if (missing.isEmpty()) {
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_no_media_is_missing)))
            return
        }
        val fps = snapshot.fps
        val runner = FolderRelinkRunner(folderScanner, importer)
        // A scan started from the results screen adds to those results; a cancel or an error brings them back untouched.
        val previous = (snapshot.folderRelink as? FolderRelinkUi.Done)?.outcome
        val resting = snapshot.folderRelink as? FolderRelinkUi.Done ?: FolderRelinkUi.Idle
        folderRelinkBeforeScan = resting
        reduce { copy(folderRelink = FolderRelinkUi.Running(), relinkOpen = true) }
        folderRelinkJob = scope.launch {
            var combined: FolderRelinkOutcome? = null
            var failure: FolderScanException? = null
            try {
                for (treeUri in treeUris) {
                    val relinkedSoFar = combined?.relinked?.mapTo(HashSet()) { it.old.id }.orEmpty()
                    val wanted = missing.filter { it.id !in relinkedSoFar }
                    if (wanted.isEmpty()) break
                    val outcome = try {
                        runner.run(
                            treeUri = treeUri,
                            missing = wanted,
                            library = snapshot.assets,
                            fps = fps,
                            neededMicros = { MissingMedia.requiredSourceMicros(history.timeline, it, fps) },
                        ) { progress ->
                            reduce {
                                if (folderRelink !is FolderRelinkUi.Running) this
                                else copy(
                                    folderRelink = when (progress) {
                                        is FolderRelinkProgress.Scanning -> FolderRelinkUi.Running(files = progress.files, folders = progress.folders)
                                        is FolderRelinkProgress.Checking -> folderRelink.copy(checked = progress.done, total = progress.total)
                                    },
                                )
                            }
                        }
                    } catch (e: FolderScanException) {
                        failure = e
                        continue
                    }
                    combined = combined?.let { outcome.mergedInto(it) } ?: outcome
                }
            } catch (e: CancellationException) {
                reduce { copy(folderRelink = resting) }
                throw e
            }
            val result = combined
            if (result == null) {
                reduce { copy(folderRelink = resting) }
                emit(EditorEffect.ShowMessage(failure?.let { reasonOf(it, UiText.res(R.string.ed_vm_could_not_read_the_folder)) } ?: UiText.res(R.string.ed_vm_could_not_read_the_folder)))
                return@launch
            }
            applyFolderRelink(result, previous)
        }
    }

    fun cancelFolderRelink() {
        if (folderRelinkJob?.isActive != true) return
        folderRelinkJob?.cancel()
        reduce { copy(folderRelink = folderRelinkBeforeScan) }
        emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_folder_scan_cancelled_nothing_was)))
    }

    /** Stores every accepted match at once; items the user relinked meanwhile, or removed, are left as they are. */
    private fun applyFolderRelink(outcome: FolderRelinkOutcome, previous: FolderRelinkOutcome?) {
        val stillMissing = state.value.missingMedia
        val present = state.value.assets.map { it.id }.toSet()
        val accepted = outcome.relinked.filter { it.old.id in present && it.old.id in stillMissing }
        for (r in accepted) {
            // Waveforms and thumbnails were made from the old file; the new key makes the native side start over.
            mediaCaches.invalidate(r.old.id)
            assetKeys.rekey(r.old.id)
        }
        val replacements = accepted.associate { it.old.id to it.asset }
        val thisScan = outcome.copy(relinked = accepted)
        val report = if (previous == null) thisScan else thisScan.mergedInto(previous)
        reduce {
            copy(
                assets = assets.map { replacements[it.id] ?: it },
                missingMedia = missingMedia - replacements.keys,
                folderRelink = FolderRelinkUi.Done(report),
                relinkOpen = true,
            )
        }
        if (accepted.isNotEmpty()) scheduleSave()
        emit(EditorEffect.ShowMessage(scanSummaryText(report)))
    }
}
