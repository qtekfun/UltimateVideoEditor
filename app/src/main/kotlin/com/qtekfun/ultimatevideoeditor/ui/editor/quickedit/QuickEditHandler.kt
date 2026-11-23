package com.qtekfun.ultimatevideoeditor.ui.editor.quickedit

import com.qtekfun.ultimatevideoeditor.R
import com.qtekfun.ultimatevideoeditor.domain.AutoCut
import com.qtekfun.ultimatevideoeditor.domain.AutoCutPlanner
import com.qtekfun.ultimatevideoeditor.domain.ClipDeletion
import com.qtekfun.ultimatevideoeditor.domain.ReframeClip
import com.qtekfun.ultimatevideoeditor.domain.ReframePoint
import com.qtekfun.ultimatevideoeditor.domain.SilenceDetector
import com.qtekfun.ultimatevideoeditor.domain.TrackType
import com.qtekfun.ultimatevideoeditor.engine.timeline.EnvelopeResult
import com.qtekfun.ultimatevideoeditor.engine.timeline.EnvelopeSource
import com.qtekfun.ultimatevideoeditor.engine.track.MotionTracker
import com.qtekfun.ultimatevideoeditor.ui.editor.*
import com.qtekfun.ultimatevideoeditor.ui.text.UiText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Quick edits: the silence auto cut and the manual reframe of a clip (SPECS.md 9.16).
 */
internal class QuickEditHandler(
    host: EditorHost,
    private val motionTracker: MotionTracker,
    private val trackDispatcher: CoroutineDispatcher,
    private val envelopeSource: EnvelopeSource,
) : EditorHost by host {

    fun quickEditIntent(intent: QuickEditIntent) {
        when (intent) {
            QuickEditIntent.OpenAutoCut -> openAutoCut()
            QuickEditIntent.CloseAutoCut -> reduce { copy(quickEdits = quickEdits.copy(autoCut = AutoCutUiState())) }
            is QuickEditIntent.SetSilenceSettings -> reduce {
                copy(quickEdits = quickEdits.copy(autoCut = quickEdits.autoCut.copy(settings = intent.settings, cuts = emptyList(), excluded = emptySet(), message = null)))
            }
            QuickEditIntent.FindSilences -> findSilences()
            is QuickEditIntent.ToggleCut -> reduce {
                val auto = quickEdits.autoCut
                val excluded = if (intent.index in auto.excluded) auto.excluded - intent.index else auto.excluded + intent.index
                copy(quickEdits = quickEdits.copy(autoCut = auto.copy(excluded = excluded)))
            }
            QuickEditIntent.ApplyAutoCut -> applyAutoCut()
            QuickEditIntent.OpenReframe -> openReframe()
            QuickEditIntent.CloseReframe -> reduce { copy(quickEdits = quickEdits.copy(reframe = ReframeUiState())) }
            is QuickEditIntent.SetReframe -> reduce {
                copy(quickEdits = quickEdits.copy(reframe = quickEdits.reframe.copy(u = intent.u.coerceIn(0.0, 1.0), v = intent.v.coerceIn(0.0, 1.0), zoom = intent.zoom, message = null)))
            }
            QuickEditIntent.MarkReframePoint -> markReframePoint()
            QuickEditIntent.ClearReframePoints -> reduce { copy(quickEdits = quickEdits.copy(reframe = quickEdits.reframe.copy(points = emptyList(), message = null))) }
            QuickEditIntent.ApplyReframe -> applyReframe()
        }
    }

    private fun openAutoCut() {
        val base = ClipDeletion.baseTrack(history.timeline)
        val clip = state.value.selectedClipId?.let { id -> base?.clip(id) }
        val asset = clip?.assetId?.let { id -> state.value.assets.firstOrNull { it.id == id } }
        when {
            clip == null -> emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_select_a_clip_on_the)))
            !clip.hasMedia || asset == null || !asset.hasAudio -> emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_select_a_clip_with_audio_4)))
            !AutoCutPlanner.supports(clip) -> emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_cutting_silences_does_not_work)))
            else -> reduce { copy(quickEdits = quickEdits.copy(autoCut = AutoCutUiState(open = true, clipId = clip.id))) }
        }
    }

    private fun findSilences() {
        val auto = state.value.quickEdits.autoCut
        val clip = auto.clipId?.let { id -> history.timeline.trackOfClip(id)?.clip(id) }
        val asset = clip?.assetId?.let { id -> state.value.assets.firstOrNull { it.id == id } }
        if (clip == null || asset == null || auto.analyzing) return
        auto.settings.problem()?.let { problem ->
            reduce { copy(quickEdits = quickEdits.copy(autoCut = quickEdits.autoCut.copy(message = UiText.Raw(problem)))) }
            return
        }
        val fps = state.value.fps
        val startMicros = fps.framesToMicros(clip.sourceIn.value)
        val endMicros = fps.framesToMicros(clip.sourceOut.value)
        reduce { copy(quickEdits = quickEdits.copy(autoCut = quickEdits.autoCut.copy(analyzing = true, message = null))) }
        scope.launch {
            var cuts: List<com.qtekfun.ultimatevideoeditor.domain.CutSpan> = emptyList()
            val message = try {
                when (val found = envelopeSource.envelope(asset.id, startMicros, endMicros)) {
                    EnvelopeResult.NoWaveform -> UiText.res(R.string.autocut_waveform_wait)
                    is EnvelopeResult.Found -> {
                        // The clip may have been edited while the analysis ran: map the silences onto where it is now.
                        val now = history.timeline.trackOfClip(clip.id)?.clip(clip.id)
                        if (now == null) {
                            UiText.res(R.string.autocut_clip_gone)
                        } else {
                            val spans = SilenceDetector.detect(found.envelope, auto.settings)
                            cuts = AutoCutPlanner.cuts(now, fps, found.windowStartMicros, spans)
                            if (cuts.isEmpty()) UiText.res(R.string.autocut_none) else null
                        }
                    }
                }
            } finally {
                reduce { copy(quickEdits = quickEdits.copy(autoCut = quickEdits.autoCut.copy(analyzing = false))) }
            }
            reduce { copy(quickEdits = quickEdits.copy(autoCut = quickEdits.autoCut.copy(cuts = cuts, excluded = emptySet(), message = message))) }
        }
    }

    private fun applyAutoCut() {
        val cuts = state.value.quickEdits.autoCut.chosen
        if (cuts.isEmpty()) return
        if (execute(AutoCut(cuts))) {
            val fps = state.value.fps
            val seconds = cuts.sumOf { it.length } * fps.den.toDouble() / fps.num
            reduce { copy(quickEdits = quickEdits.copy(autoCut = AutoCutUiState())) }
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_removed_silences, cuts.size, seconds)))
        }
    }

    private fun openReframe() {
        val clip = state.value.selectedClipId?.let { id -> history.timeline.trackOfClip(id)?.clip(id) }
        val onVideoTrack = clip?.let { history.timeline.trackOfClip(it.id)?.type == TrackType.VIDEO } == true
        if (clip == null || !onVideoTrack || !clip.hasMedia) {
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_select_a_video_or_photo_2)))
            return
        }
        reduce { copy(quickEdits = quickEdits.copy(reframe = ReframeUiState(open = true, clipId = clip.id))) }
    }

    private fun markReframePoint() {
        val frame = state.value.selectedClipFrame
        val reframe = state.value.quickEdits.reframe
        if (frame == null) {
            reduce { copy(quickEdits = quickEdits.copy(reframe = quickEdits.reframe.copy(message = UiText.res(R.string.reframe_playhead)))) }
            return
        }
        val points = (reframe.points.filter { it.frame != frame } + ReframePoint(frame, reframe.u, reframe.v)).sortedBy { it.frame }
        reduce { copy(quickEdits = quickEdits.copy(reframe = quickEdits.reframe.copy(points = points, message = null))) }
    }

    private fun applyReframe() {
        val s = state.value
        val reframe = s.quickEdits.reframe
        val clip = reframe.clipId?.let { id -> history.timeline.trackOfClip(id)?.clip(id) } ?: return
        val asset = clip.assetId?.let { id -> s.assets.firstOrNull { it.id == id } } ?: return
        val points = reframe.points.ifEmpty { listOf(ReframePoint(0, reframe.u, reframe.v)) }
        scope.launch {
            val aspect = withContext(trackDispatcher) { motionTracker.frameAspect(asset) }
            if (aspect == null) {
                reduce { copy(quickEdits = quickEdits.copy(reframe = quickEdits.reframe.copy(message = UiText.res(R.string.reframe_shape)))) }
                return@launch
            }
            if (execute(ReframeClip(clip.id, points, aspect, s.canvasWidth, s.canvasHeight, reframe.zoom))) {
                reduce { copy(quickEdits = quickEdits.copy(reframe = ReframeUiState())) }
                emit(EditorEffect.ShowMessage(if (points.size > 1) UiText.res(R.string.ed_vm_reframed_with_keyframes, points.size) else UiText.res(R.string.ed_vm_reframed)))
            }
        }
    }
}
