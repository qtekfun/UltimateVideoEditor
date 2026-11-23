package com.qtekfun.ultimatevideoeditor.ui.editor.timeline

import com.qtekfun.ultimatevideoeditor.R
import com.qtekfun.ultimatevideoeditor.domain.ClipDeletion
import com.qtekfun.ultimatevideoeditor.domain.CutToBeat
import com.qtekfun.ultimatevideoeditor.domain.MarkerKind
import com.qtekfun.ultimatevideoeditor.domain.SetBeatMarkers
import com.qtekfun.ultimatevideoeditor.domain.beat.BeatMapping
import com.qtekfun.ultimatevideoeditor.domain.isFreeze
import com.qtekfun.ultimatevideoeditor.engine.timeline.BeatResult
import com.qtekfun.ultimatevideoeditor.engine.timeline.BeatSource
import com.qtekfun.ultimatevideoeditor.ui.editor.*
import com.qtekfun.ultimatevideoeditor.ui.editor.timeline.MarkerHandler
import com.qtekfun.ultimatevideoeditor.ui.text.UiText
import kotlinx.coroutines.launch

/**
 * Beat markers: detecting the beats of a music clip, placing them on the ruler and cutting the selected clips to them.
 */
internal class BeatHandler(
    host: EditorHost,
    private val beatSource: BeatSource,
    private val markers: MarkerHandler,
) : EditorHost by host {

    fun clearBeatMarkers() {
        if (history.timeline.markers.none { it.kind == MarkerKind.BEAT }) {
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_there_are_no_beat_markers)))
            return
        }
        execute(SetBeatMarkers(emptyList()))
    }

    fun analyzeBeats() {
        if (state.value.isAnalyzingBeats) return
        val clipId = state.value.selectedClipId
        val clip = clipId?.let { history.timeline.trackOfClip(it)?.clip(it) }
        val asset = clip?.assetId?.let { id -> state.value.assets.firstOrNull { it.id == id } }
        if (clip == null || !clip.hasMedia || asset == null || !asset.hasAudio || clip.isFreeze) {
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_select_a_clip_with_audio_3)))
            return
        }
        val fps = state.value.fps
        // Look a few seconds beyond the clip so a short clip still shows the song's repeating pulse.
        val startMicros = (fps.framesToMicros(clip.sourceIn.value) - BEAT_WINDOW_PADDING_MICROS).coerceAtLeast(0)
        val endMicros = fps.framesToMicros(clip.sourceOut.value) + BEAT_WINDOW_PADDING_MICROS
        reduce { copy(isAnalyzingBeats = true) }
        scope.launch {
            try {
                when (val result = beatSource.analyze(asset.id, startMicros, endMicros)) {
                    BeatResult.NoWaveform -> emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_the_waveform_is_still_being)))
                    BeatResult.NoBeat -> emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_no_clear_beat_found_in)))
                    is BeatResult.Found -> placeBeats(clip.id, result)
                }
            } finally {
                reduce { copy(isAnalyzingBeats = false) }
            }
        }
    }

    private fun placeBeats(clipId: String, found: BeatResult.Found) {
        // The clip may have been edited or removed while the analysis ran: work from where it is now.
        val clip = history.timeline.trackOfClip(clipId)?.clip(clipId) ?: return
        val run = idGenerator()
        val absolute = found.grid.beatsMicros.map { it + found.windowStartMicros }
        val markers = BeatMapping.markersFor(clip, absolute, state.value.fps) { "beat-$run-$it" }
        if (markers.isEmpty()) {
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_no_beats_fall_inside_this)))
            return
        }
        if (execute(SetBeatMarkers(markers, from = clip.timelineStart, until = clip.timelineEnd))) {
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_marked_beats_at_about_bpm, markers.size, found.grid.bpm.toInt())))
        }
    }

    fun cutToBeatFromSelected() {
        val timeline = history.timeline
        val base = ClipDeletion.baseTrack(timeline)
        val selected = state.value.selectedClipId?.let { id -> base?.clip(id) }
        if (base == null || selected == null) {
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_select_a_clip_on_the)))
            return
        }
        val ids = base.clips.filter { it.timelineStart >= selected.timelineStart }.map { it.id }
        val lengths = base.clips.associate { it.id to assetLengthFrames(it.assetId) }
        execute(CutToBeat(ids, timeline.markers.map { it.frame.value }, lengths))
    }
}
