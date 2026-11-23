package com.qtekfun.ultimatevideoeditor.ui.editor.appearance

import com.qtekfun.ultimatevideoeditor.R
import com.qtekfun.ultimatevideoeditor.engine.stabilise.StabOutcome
import com.qtekfun.ultimatevideoeditor.engine.stabilise.StabStatus
import com.qtekfun.ultimatevideoeditor.engine.stabilise.Stabiliser
import com.qtekfun.ultimatevideoeditor.ui.editor.*
import com.qtekfun.ultimatevideoeditor.ui.text.UiText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Camera-shake correction: analysing a clip, and rebuilding the correction tables of stabilised clips when a project is opened or the
 * timeline changes.
 */
internal class StabiliseHandler(
    host: EditorHost,
    private val stabiliser: Stabiliser,
    private val stabDispatcher: CoroutineDispatcher,
) : EditorHost by host {

    private var stabJob: Job? = null

    /**
     * Makes the correction tables of every stabilised clip available to the preview and the exporter, and refreshes the
     * selected clip's status for the inspector. Runs off the main thread (it reads cache files).
     */
    fun refreshStabilise() {
        val timeline = history.timeline
        val assets = state.value.assets
        val fps = state.value.fps
        val selected = state.value.selectedClipId?.let { timeline.trackOfClip(it)?.clip(it) }
        val stabilised = timeline.tracks.flatMap { it.clips }.filter { it.stabilise != null }
        if (stabilised.isEmpty() && selected?.stabilise == null) {
            if (state.value.stab.status != StabStatus.Off || state.value.stab.clipId != null) {
                reduce { copy(stab = StabUiState(progress = stab.progress)) }
            }
            return
        }
        scope.launch(stabDispatcher) {
            for (clip in stabilised) assets.firstOrNull { it.id == clip.assetId }?.let { stabiliser.register(it, clip, fps) }
            val asset = selected?.let { clip -> assets.firstOrNull { it.id == clip.assetId } }
            val status = if (selected != null && asset != null) stabiliser.statusOf(asset, selected, fps) else StabStatus.Off
            reduce { copy(stab = StabUiState(clipId = selected?.id, status = status, progress = stab.progress)) }
        }
    }

    fun analyseStabilise() {
        if (stabJob?.isActive == true) return
        val clipId = state.value.selectedClipId
        val clip = clipId?.let { history.timeline.trackOfClip(it)?.clip(it) }
        val asset = clip?.assetId?.let { id -> state.value.assets.firstOrNull { it.id == id } }
        if (clip == null || clip.stabilise == null || asset == null) {
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_turn_the_stabiliser_on_for)))
            return
        }
        val fps = state.value.fps
        reduce { copy(stab = StabUiState(clipId = clip.id, status = stab.status.takeIf { it != StabStatus.Off } ?: StabStatus.NotAnalysed, progress = 0f)) }
        stabJob = scope.launch {
            val outcome = stabiliser.analyse(asset, clip, fps) { progress -> reduce { copy(stab = stab.copy(progress = progress)) } }
            if (outcome is StabOutcome.Failed) emit(EditorEffect.ShowMessage(outcome.message))
            reduce { copy(stab = stab.copy(progress = null)) }
            refreshStabilise()
        }
    }
}
