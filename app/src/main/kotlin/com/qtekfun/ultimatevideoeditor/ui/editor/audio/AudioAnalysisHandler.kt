package com.qtekfun.ultimatevideoeditor.ui.editor.audio

import com.qtekfun.ultimatevideoeditor.R
import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto
import com.qtekfun.ultimatevideoeditor.domain.Clip
import com.qtekfun.ultimatevideoeditor.domain.ClipAudio
import com.qtekfun.ultimatevideoeditor.domain.Denoise
import com.qtekfun.ultimatevideoeditor.domain.EditCommand
import com.qtekfun.ultimatevideoeditor.domain.isFreeze
import com.qtekfun.ultimatevideoeditor.domain.retime
import com.qtekfun.ultimatevideoeditor.ui.editor.*
import com.qtekfun.ultimatevideoeditor.ui.text.UiText
import com.qtekfun.ultimatevideoeditor.ui.text.reasonOf
import kotlinx.coroutines.launch

/**
 * Measuring a clip's audio: normalising its loudness to a target and learning a noise profile to suppress. The analyser is set by the screen
 * once the audio engine is up.
 */
internal class AudioAnalysisHandler(
    host: EditorHost,
    private val loudnessCache: LoudnessCache,
) : EditorHost by host {

    /** Measures loudness and noise profiles for the audio tools; null where the engine is unavailable. */
    var audioAnalyzer: AudioAnalyzer? = null

    /** The selected clip and its library file when both can be analysed for sound, else a message. */
    private fun analysableSelection(message: UiText): Pair<Clip, MediaAssetDto>? {
        val clipId = state.value.selectedClipId
        val clip = clipId?.let { history.timeline.trackOfClip(it)?.clip(it) }
        val asset = clip?.assetId?.let { id -> state.value.assets.firstOrNull { it.id == id } }
        if (clip == null || !clip.hasMedia || asset == null || !asset.hasAudio || clip.isFreeze) {
            emit(EditorEffect.ShowMessage(message))
            return null
        }
        if (audioAnalyzer == null) {
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_audio_measurements_are_not_available)))
            return null
        }
        if (state.value.audioBusy != null) return null
        return clip to asset
    }

    fun normalizeLoudness(targetLufs: Double) {
        if (!targetLufs.isFinite() || targetLufs !in MIN_TARGET_LUFS..MAX_TARGET_LUFS) {
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_the_loudness_target_must_be, MIN_TARGET_LUFS, MAX_TARGET_LUFS)))
            return
        }
        val (clip, asset) = analysableSelection(UiText.res(R.string.ed_vm_select_audio_normalise)) ?: return
        val analyzer = checkNotNull(audioAnalyzer)
        val fps = state.value.fps
        val key = LoudnessCache.keyOf(asset, clip.sourceIn.value, clip.sourceOut.value)
        reduce { copy(audioBusy = UiText.res(R.string.audio_busy_loudness)) }
        scope.launch {
            try {
                val cached = loudnessCache.get(key)
                val lufs = cached ?: analyzer.loudness(
                    asset, assetKeys.keyFor(asset.id),
                    fps.framesToMicros(clip.sourceIn.value), fps.framesToMicros(clip.sourceOut.value),
                ).lufs?.also { loudnessCache.put(key, it) }
                if (lufs == null) {
                    emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_this_clip_is_silent_so)))
                    return@launch
                }
                // The clip may have been edited while it was measured: act on what is there now.
                val now = history.timeline.trackOfClip(clip.id)?.clip(clip.id) ?: return@launch
                if (now.sourceIn != clip.sourceIn || now.sourceOut != clip.sourceOut) {
                    emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_the_clip_changed_while_it)))
                    return@launch
                }
                val gain = (targetLufs - lufs).coerceIn(-ClipAudio.MAX_NORMALIZE_DB, ClipAudio.MAX_NORMALIZE_DB)
                if (execute(EditCommand.SetClipAudio(now.id, now.audio.copy(normalizeDb = gain, targetLufs = targetLufs)))) {
                    emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_measured_lufs, lufs, gain, targetLufs)))
                }
            } catch (e: AudioAnalysisException) {
                emit(EditorEffect.ShowMessage(reasonOf(e, UiText.res(R.string.ed_vm_the_loudness_could_not_be))))
            } finally {
                reduce { copy(audioBusy = null) }
            }
        }
    }

    fun clearNormalize() = withSelection { clipId ->
        val clip = history.timeline.trackOfClip(clipId)?.clip(clipId) ?: return@withSelection
        if (clip.audio.targetLufs == null && clip.audio.normalizeDb == 0.0) {
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_this_clip_is_not_normalised)))
            return@withSelection
        }
        execute(EditCommand.SetClipAudio(clipId, clip.audio.copy(normalizeDb = 0.0, targetLufs = null)))
    }

    fun markNoiseRegion(atStart: Boolean) {
        val clipId = state.value.selectedClipId
        val clip = clipId?.let { history.timeline.trackOfClip(it)?.clip(it) }
        if (clip == null || !clip.hasMedia) {
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_select_a_clip_with_audio_2)))
            return
        }
        val offset = state.value.playhead - clip.timelineStart
        if (offset < 0 || offset > clip.durationFrames) {
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_move_the_playhead_inside_the_2)))
            return
        }
        val current = state.value.noiseRegion?.takeIf { it.clipId == clip.id } ?: NoiseRegion(clip.id, null, null)
        var next = if (atStart) current.copy(startFrame = offset) else current.copy(endFrame = offset)
        // A start after the end (or the reverse) means the user is picking a new stretch.
        val s = next.startFrame
        val e = next.endFrame
        if (s != null && e != null && s >= e) {
            next = if (atStart) next.copy(endFrame = null) else next.copy(startFrame = null)
        }
        reduce { copy(noiseRegion = next) }
    }

    fun analyzeNoise(strength: Double) {
        if (!strength.isFinite() || strength <= 0.0 || strength > 1.0) {
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_noise_suppression_strength_must_be)))
            return
        }
        val (clip, asset) = analysableSelection(UiText.res(R.string.ed_vm_select_audio_noise)) ?: return
        val region = state.value.noiseRegion?.takeIf { it.clipId == clip.id && it.isComplete }
        if (region == null) {
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_mark_a_quiet_stretch_first)))
            return
        }
        val fps = state.value.fps
        val retime = clip.retime
        val a = retime.sourceFrameAt(checkNotNull(region.startFrame))
        val b = retime.sourceFrameAt(checkNotNull(region.endFrame))
        val startMicros = fps.framesToMicros(minOf(a, b))
        val endMicros = fps.framesToMicros(maxOf(a, b))
        if (endMicros - startMicros < MIN_NOISE_SAMPLE_MICROS) {
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_the_quiet_stretch_must_be)))
            return
        }
        val analyzer = checkNotNull(audioAnalyzer)
        reduce { copy(audioBusy = UiText.res(R.string.audio_busy_noise)) }
        scope.launch {
            try {
                val profile = analyzer.noiseProfile(asset, assetKeys.keyFor(asset.id), startMicros, endMicros)
                val now = history.timeline.trackOfClip(clip.id)?.clip(clip.id) ?: return@launch
                if (execute(EditCommand.SetClipAudio(now.id, now.audio.copy(denoise = Denoise(strength, profile.toList()))))) {
                    emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_noise_suppression_is_on)))
                }
            } catch (e: AudioAnalysisException) {
                emit(EditorEffect.ShowMessage(reasonOf(e, UiText.res(R.string.ed_vm_the_noise_could_not_be))))
            } finally {
                reduce { copy(audioBusy = null) }
            }
        }
    }

    fun removeNoiseSuppression() = withSelection { clipId ->
        val clip = history.timeline.trackOfClip(clipId)?.clip(clipId) ?: return@withSelection
        if (clip.audio.denoise == null) {
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_noise_suppression_is_not_on)))
            return@withSelection
        }
        execute(EditCommand.SetClipAudio(clipId, clip.audio.copy(denoise = null)))
    }
}
