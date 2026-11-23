package com.qtekfun.ultimatevideoeditor.ui.editor.media

import com.qtekfun.ultimatevideoeditor.R
import com.qtekfun.ultimatevideoeditor.data.MediaCaches
import com.qtekfun.ultimatevideoeditor.data.MediaImportException
import com.qtekfun.ultimatevideoeditor.data.MediaImporter
import com.qtekfun.ultimatevideoeditor.data.MediaProblem
import com.qtekfun.ultimatevideoeditor.data.MissingMedia
import com.qtekfun.ultimatevideoeditor.data.ProbedMedia
import com.qtekfun.ultimatevideoeditor.data.ProjectError
import com.qtekfun.ultimatevideoeditor.data.ProjectStore
import com.qtekfun.ultimatevideoeditor.data.lacksVideoFacts
import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto
import com.qtekfun.ultimatevideoeditor.data.withVideoFacts
import com.qtekfun.ultimatevideoeditor.ui.editor.*
import com.qtekfun.ultimatevideoeditor.ui.editor.appearance.HdrLevelHandler
import com.qtekfun.ultimatevideoeditor.ui.editor.playback.PlaybackController
import com.qtekfun.ultimatevideoeditor.ui.text.UiText
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Whether the project's files can still be read: the check when a project opens, the report of a decoder that failed on a file, and the
 * recheck after the user reconnects a drive. Unreadable files are recorded in the state and counted for the Projects screen.
 */
internal class MediaHealthHandler(
    host: EditorHost,
    private val projectId: String,
    private val store: ProjectStore,
    private val importer: MediaImporter,
    private val mediaCaches: MediaCaches,
    private val playback: PlaybackController,
    private val hdr: HdrLevelHandler,
) : EditorHost by host {

    /**
     * Opens every library file once. Files that cannot be read are recorded in [EditorState.missingMedia]:
     * their clips stay on the timeline, marked, while the preview, the mixer and the thumbnail workers
     * leave them alone, and the user is offered Relink. Probing also repairs two things in place:
     * projects saved before `hasVideo`/`hasAudio` existed read as "has both" (a file without an audio
     * track would reach the mixer and fail), projects saved before names were kept lack a file name, and projects saved before the
     * export dialog read the source's size, bit rate and codec lack those (it falls back to fixed defaults for them).
     * Reading a file also re-takes its persisted permission where Android allows it.
     */
    suspend fun verifyAssets(loaded: List<MediaAssetDto>) {
        val missing = HashMap<String, MediaProblem>()
        val probed = HashMap<String, ProbedMedia>()
        for (asset in loaded) {
            try {
                probed[asset.id] = importer.verify(asset.uri)
            } catch (e: MediaImportException) {
                // A drive that is not connected, a lost grant, a dead provider: the file is reported missing, not fatal.
                missing[asset.id] = e.problem
            }
        }
        val stale = staleFacts(state.value.assets, probed)
        // Apply to the current list: media may have been imported while probing.
        reduce {
            copy(
                missingMedia = missingMedia + missing,
                mediaChecked = true,
                assets = withFacts(assets, stale),
            )
        }
        // The count of unreadable files reaches the Projects screen without rewriting the project (see publishMissingCount);
        // refreshed facts are stored with the next save, and opening alone never rewrites project.json.
        if (stale.isNotEmpty()) scheduleSave()
        publishMissingCount()
        hdr.refreshHdrLevels()
    }

    /** The probed files whose library entry lacks something the probe found (see [verifyAssets]). */
    fun staleFacts(current: List<MediaAssetDto>, probed: Map<String, ProbedMedia>): Map<String, ProbedMedia> =
        probed.filter { (id, media) ->
            current.firstOrNull { it.id == id }?.let {
                (it.hasVideo to it.hasAudio) != (media.hasVideo to media.hasAudio) || (it.displayName == null && media.displayName != null) ||
                    it.lacksVideoFacts(media)
            } == true
        }

    fun withFacts(assets: List<MediaAssetDto>, stale: Map<String, ProbedMedia>): List<MediaAssetDto> =
        if (stale.isEmpty()) assets else assets.map { asset ->
            stale[asset.id]?.let { media ->
                asset.copy(hasVideo = media.hasVideo, hasAudio = media.hasAudio, displayName = asset.displayName ?: media.displayName)
                    .withVideoFacts(media)
            } ?: asset
        }

    /**
     * Tells the Projects screen how many files are unreadable, once they have been checked, in `media-status.json` beside the
     * project: a save would also move the project to the top of the list (SPECS 4.1). The store skips the write when the
     * screen already shows this number. A failure only costs the "N missing" label, which the next check or save sets.
     */
    fun publishMissingCount() {
        if (!state.value.mediaChecked) return
        scope.launch {
            try {
                store.saveMediaStatus(projectId, state.value.missingMedia.size)
            } catch (e: ProjectError) {
                mediaStatusError = e.message
            }
        }
    }

    private var mediaStatusError: String? = null

    /** Assets whose failure is being looked into, so a burst of decoder errors for one lost drive checks once. */
    private val lossChecks = HashSet<String>()

    /**
     * A decoder or the mixer failed on the file with native key [assetKey] (a preview decode error or a mixer fault): see
     * [EditorIntent.MediaFailureReported]. A vanished volume makes every decoder of its files fail within moments, and each file is
     * reported once; later failures of a file that is already missing are expected and say nothing.
     */
    fun mediaFailureReported(assetKey: Long, detail: String) {
        val id = if (assetKey < 0) null else assetKeys.idFor(assetKey % LANE_STRIDE)
        val asset = state.value.assets.firstOrNull { it.id == id }
        if (asset == null) {
            emit(EditorEffect.ShowMessage(detail))
            return
        }
        if (asset.id in state.value.missingMedia || !lossChecks.add(asset.id)) return
        scope.launch {
            try {
                val problem = try {
                    importer.verify(asset.uri)
                    null
                } catch (e: MediaImportException) {
                    e.problem
                }
                if (problem == null) {
                    emit(EditorEffect.ShowMessage(detail))  // the file is fine: an ordinary decode error
                    return@launch
                }
                if (asset.id in state.value.missingMedia || state.value.assets.none { it.id == asset.id }) return@launch
                playback.pausePlayback()
                reduce { copy(missingMedia = missingMedia + (asset.id to problem)) }
                emit(EditorEffect.AssetUnavailable(assetKeys.keyFor(asset.id)))
                emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_media_for_is_no_longer, MissingMedia.nameOf(asset))))
                publishMissingCount()
            } finally {
                lossChecks.remove(asset.id)
            }
        }
    }

    private var recheckJob: Job? = null

    /**
     * "Check again" (SPECS 5.40): verifies every unreadable file once more and clears the flag of those that can be read now,
     * without reopening the project. A file that is back gets a new key, so no cache of the vanished file (a waveform, a thumbnail
     * strip, a stale decoder) is mistaken for it. The project is not changed, so nothing is saved except the Projects screen's count.
     */
    fun recheckMissingMedia() {
        if (recheckJob?.isActive == true) return
        val pending = state.value.assets.filter { it.id in state.value.missingMedia }
        if (pending.isEmpty()) {
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_no_media_is_missing)))
            return
        }
        recheckJob = scope.launch {
            val readable = HashMap<String, ProbedMedia>()
            val unreadable = HashMap<String, MediaProblem>()
            for (asset in pending) {
                try {
                    readable[asset.id] = importer.verify(asset.uri)
                } catch (e: MediaImportException) {
                    unreadable[asset.id] = e.problem
                }
            }
            // Relinked or removed while checking: leave those alone.
            val back = readable.filterKeys { it in state.value.missingMedia }
            for (id in back.keys) {
                mediaCaches.invalidate(id)
                assetKeys.rekey(id)
            }
            val stale = staleFacts(state.value.assets, back)
            reduce {
                val remaining = missingMedia - back.keys
                copy(
                    missingMedia = remaining.mapValues { (id, old) -> unreadable[id] ?: old },
                    assets = withFacts(assets, stale),
                    relinkOpen = relinkOpen && (remaining.isNotEmpty() || folderRelink is FolderRelinkUi.Done),
                )
            }
            if (stale.isNotEmpty()) scheduleSave()
            publishMissingCount()
            val left = state.value.missingMedia.size
            emit(
                EditorEffect.ShowMessage(
                    when {
                        back.isEmpty() -> UiText.res(R.string.ed_vm_still_missing)
                        left == 0 -> UiText.res(R.string.ed_vm_all_readable)
                        else -> UiText.res(R.string.ed_vm_some_readable, back.size, pending.size, left)
                    },
                ),
            )
        }
    }
}
