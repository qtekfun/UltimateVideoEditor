package com.ultimatevideo.uveditor.ui.editor.captions

import com.ultimatevideo.uveditor.domain.Clip
import com.ultimatevideo.uveditor.domain.FrameRate
import com.ultimatevideo.uveditor.engine.captions.CaptionLanguage
import com.ultimatevideo.uveditor.engine.captions.CaptionModel
import com.ultimatevideo.uveditor.engine.captions.CaptionModels
import com.ultimatevideo.uveditor.mvi.UiEffect
import com.ultimatevideo.uveditor.mvi.UiIntent
import com.ultimatevideo.uveditor.mvi.UiState
import com.ultimatevideo.uveditor.domain.captions.CaptionStyle

/** The clip to caption and what is needed to place the captions: its media, the frame rate and the canvas height. */
data class CaptionTarget(
    val clip: Clip,
    val assetUri: String,
    val fps: FrameRate,
    val canvasHeight: Int,
)

enum class CaptionPhase { IDLE, DOWNLOADING, TRANSCRIBING }

data class ModelOption(val model: CaptionModel, val installed: Boolean)

data class CaptionsState(
    /** Non-null while the captions sheet is open. */
    val target: CaptionTarget? = null,
    val models: List<ModelOption> = emptyList(),
    val modelId: String = CaptionModels.DEFAULT.id,
    val languageCode: String = CaptionLanguage.AUTO,
    val styleId: String = CaptionStyle.CLASSIC.id,
    val phase: CaptionPhase = CaptionPhase.IDLE,
    /** 0..100 of the running download or transcription. */
    val progress: Int = 0,
    val error: String? = null,
) : UiState {
    val isOpen: Boolean get() = target != null
    val busy: Boolean get() = phase != CaptionPhase.IDLE
    val selectedModel: ModelOption? get() = models.firstOrNull { it.model.id == modelId }
}

sealed interface CaptionsIntent : UiIntent {
    data class Open(val target: CaptionTarget) : CaptionsIntent

    /** Closing while busy cancels the work. */
    data object Close : CaptionsIntent
    data class SelectModel(val id: String) : CaptionsIntent
    data class SelectLanguage(val code: String) : CaptionsIntent
    data class SelectStyle(val id: String) : CaptionsIntent

    /** Downloads the selected model if needed, transcribes the clip's audio and produces the captions. */
    data object Generate : CaptionsIntent
    data object Cancel : CaptionsIntent
    data class DeleteModel(val id: String) : CaptionsIntent
}

sealed interface CaptionsEffect : UiEffect {
    /** Generated title clips, ready to be put on a new caption track by the editor. */
    data class ClipsReady(val clips: List<Clip>) : CaptionsEffect
    data class ShowMessage(val text: String) : CaptionsEffect
}
