package com.qtekfun.ultimatevideoeditor.ui.editor.project

import com.qtekfun.ultimatevideoeditor.R
import com.qtekfun.ultimatevideoeditor.data.ProjectError
import com.qtekfun.ultimatevideoeditor.data.ProjectStore
import com.qtekfun.ultimatevideoeditor.data.TimelineMapper
import com.qtekfun.ultimatevideoeditor.data.model.ProjectDto
import com.qtekfun.ultimatevideoeditor.ui.editor.*
import com.qtekfun.ultimatevideoeditor.ui.editor.playback.PlaybackController
import com.qtekfun.ultimatevideoeditor.ui.text.UiText
import com.qtekfun.ultimatevideoeditor.ui.text.reasonOf
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Autosave of the project: the debounced write after each edit, the retries after a failed write, and the flush on leaving. It keeps the
 * last project document written or read ([baseProject]) so a save only replaces the timeline and the library in it.
 */
internal class ProjectPersistence(
    host: EditorHost,
    private val store: ProjectStore,
    private val saveDebounceMillis: Long,
    private val playback: PlaybackController,
) : EditorHost by host {

    var baseProject: ProjectDto? = null

    var saveJob: Job? = null

    private var saveRetryJob: Job? = null

    private var saveRetries = 0

    var dirty = false

    override fun scheduleSave() {
        dirty = true
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(saveDebounceMillis)
            persist()
        }
    }

    fun flush(thenClose: Boolean) {
        playback.pausePlayback()
        scope.launch {
            saveJob?.cancelAndJoin()
            val saved = if (dirty) persist() else true
            if (!thenClose) return@launch
            // Closing on top of a failed save would silently throw the edits away: ask first.
            if (saved) emit(EditorEffect.Close) else reduce { copy(leaveBlockedBySave = true) }
        }
    }

    fun retrySave() {
        reduce { copy(leaveBlockedBySave = false) }
        saveRetries = 0
        saveRetryJob?.cancel()
        saveJob?.cancel()
        saveJob = scope.launch { persist() }
    }

    /**
     * Writes any unsaved edit now, for what reads the project file (a bundle is made from it); throws when the write fails.
     */
    suspend fun saveNow() {
        saveJob?.cancelAndJoin()
        if (dirty && !persist()) throw IOException("the project could not be saved first")
    }

    /** Writes the project; returns whether it is on disk. A failure stays visible in [EditorState.saveError] and is retried. */
    suspend fun persist(): Boolean {
        val base = baseProject ?: return true
        val snapshot = state.value
        val mapped = TimelineMapper.toDto(base, history.timeline, snapshot.assets)
        // Before the files were checked the stored count is the best knowledge there is; the hub shows it without probing.
        val dto = if (snapshot.mediaChecked) mapped.copy(missingMedia = snapshot.missingMedia.size) else mapped
        return try {
            store.save(dto)
            baseProject = dto
            dirty = false
            saveRetries = 0
            saveRetryJob?.cancel()
            if (state.value.saveError != null) reduce { copy(saveError = null, leaveBlockedBySave = false) }
            true
        } catch (e: ProjectError) {
            val first = state.value.saveError == null
            reduce { copy(saveError = reasonOf(e, UiText.res(R.string.s5_unknown_error))) }
            if (first) emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_could_not_save_the_project, reasonOf(e, UiText.Empty))))
            saveRetryJob?.cancel()
            // A few quiet retries cover a transient failure (a full disk someone just cleared); after that the
            // banner stays and Retry is the user's call, instead of writing to a broken disk for ever.
            if (saveRetries < MAX_SAVE_RETRIES) {
                saveRetries++
                saveRetryJob = scope.launch {
                    delay(SAVE_RETRY_MILLIS)
                    persist()
                }
            }
            false
        }
    }

    fun currentProjectDto(): ProjectDto? {
        val base = baseProject ?: return null
        return TimelineMapper.toDto(base, history.timeline, state.value.assets)
    }
}
