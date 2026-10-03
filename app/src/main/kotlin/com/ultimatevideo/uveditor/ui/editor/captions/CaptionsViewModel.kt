package com.ultimatevideo.uveditor.ui.editor.captions

import androidx.lifecycle.viewModelScope
import com.ultimatevideo.uveditor.domain.captions.CaptionPlanner
import com.ultimatevideo.uveditor.domain.captions.CaptionStyle
import com.ultimatevideo.uveditor.domain.captions.clipsFor
import com.ultimatevideo.uveditor.engine.captions.CaptionException
import com.ultimatevideo.uveditor.engine.captions.CaptionModelProvider
import com.ultimatevideo.uveditor.engine.captions.CaptionModels
import com.ultimatevideo.uveditor.engine.captions.Transcriber
import com.ultimatevideo.uveditor.engine.captions.TranscribeRequest
import com.ultimatevideo.uveditor.mvi.MviViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Drives the "Auto captions" sheet: picks the model (downloading it on first use), runs the
 * transcription of the target clip's audio, and turns the words into caption title clips that it
 * hands to the editor as [CaptionsEffect.ClipsReady]. Nothing touches the timeline from here, so
 * the generated captions arrive as one ordinary, undoable edit.
 */
class CaptionsViewModel(
    private val provider: CaptionModelProvider,
    private val transcriber: Transcriber,
    private val idGenerator: () -> String = { UUID.randomUUID().toString().take(ID_LENGTH) },
) : MviViewModel<CaptionsState, CaptionsIntent, CaptionsEffect>(CaptionsState(models = options(provider))) {

    private var job: Job? = null

    override fun onIntent(intent: CaptionsIntent) {
        when (intent) {
            is CaptionsIntent.Open -> open(intent.target)
            CaptionsIntent.Close -> close()
            is CaptionsIntent.SelectModel -> if (!state.value.busy) reduce { copy(modelId = CaptionModels.byId(intent.id).id, error = null) }
            is CaptionsIntent.SelectLanguage -> if (!state.value.busy) reduce { copy(languageCode = intent.code, error = null) }
            is CaptionsIntent.SelectStyle -> if (!state.value.busy) reduce { copy(styleId = CaptionStyle.byId(intent.id).id, error = null) }
            CaptionsIntent.Generate -> generate()
            CaptionsIntent.Cancel -> cancel()
            is CaptionsIntent.DeleteModel -> deleteModel(intent.id)
        }
    }

    private fun open(target: CaptionTarget) {
        if (state.value.busy) return
        reduce { copy(target = target, models = options(provider), error = null, progress = 0, phase = CaptionPhase.IDLE) }
    }

    private fun close() {
        cancel()
        reduce { copy(target = null, error = null) }
    }

    private fun cancel() {
        job?.cancel()
        job = null
        reduce { copy(phase = CaptionPhase.IDLE, progress = 0) }
    }

    private fun deleteModel(id: String) {
        if (state.value.busy) return
        provider.delete(CaptionModels.byId(id))
        reduce { copy(models = options(provider)) }
    }

    private fun generate() {
        val current = state.value
        val target = current.target ?: return
        if (current.busy) return
        val model = CaptionModels.byId(current.modelId)
        val style = CaptionStyle.byId(current.styleId)
        val language = current.languageCode

        reduce { copy(error = null, progress = 0) }
        job = viewModelScope.launch {
            try {
                if (!provider.isInstalled(model)) {
                    reduce { copy(phase = CaptionPhase.DOWNLOADING, progress = 0) }
                    provider.download(model).collect { p -> setProgress(p.percent) }
                    reduce { copy(models = options(provider)) }
                }

                reduce { copy(phase = CaptionPhase.TRANSCRIBING, progress = 0) }
                val clip = target.clip
                val request = TranscribeRequest(
                    assetUri = target.assetUri,
                    startMs = target.fps.framesToMicros(clip.sourceIn.value) / MICROS_PER_MILLI,
                    endMs = target.fps.framesToMicros(clip.sourceOut.value) / MICROS_PER_MILLI,
                    model = model,
                    language = language,
                )
                val transcript = transcriber.transcribe(request, ::setProgress)

                val cues = CaptionPlanner.plan(transcript.words, clip, target.fps, style.options)
                if (cues.isEmpty()) {
                    reduce { copy(phase = CaptionPhase.IDLE, progress = 0, error = "No speech was found in this clip") }
                    return@launch
                }
                val clips = style.clipsFor(cues, target.canvasHeight, idGenerator)
                emit(CaptionsEffect.ClipsReady(clips))
                emit(CaptionsEffect.ShowMessage("Added ${clips.size} captions"))
                reduce { copy(target = null, phase = CaptionPhase.IDLE, progress = 0) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: CaptionException) {
                reduce { copy(phase = CaptionPhase.IDLE, progress = 0, error = e.message, models = options(provider)) }
            }
        }
    }

    /** Called from background threads; the state flow takes concurrent updates. */
    private fun setProgress(percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        if (clamped != state.value.progress) reduce { copy(progress = clamped) }
    }

    private companion object {
        const val ID_LENGTH = 8
        const val MICROS_PER_MILLI = 1_000L

        fun options(provider: CaptionModelProvider) = CaptionModels.ALL.map { ModelOption(it, provider.isInstalled(it)) }
    }
}
