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
    /** Text and emphasis colour overrides of the chosen style; null keeps the style's own. */
    val textColor: Int? = null,
    val highlightColor: Int? = null,
    val phase: CaptionPhase = CaptionPhase.IDLE,
    /** 0..100 of the running download or transcription. */
    val progress: Int = 0,
    val error: String? = null,
    /** Generated captions already on the timeline, which the chosen style can be applied to. */
    val existingCaptions: Int = 0,
    /** True when the sheet was opened only to restyle existing captions (no clip to transcribe). */
    val restyleOnly: Boolean = false,
    /** Canvas height for restyling; the transcription target carries its own. */
    val canvasHeight: Int = 0,
) : UiState {
    val isOpen: Boolean get() = target != null || restyleOnly
    val busy: Boolean get() = phase != CaptionPhase.IDLE
    val selectedModel: ModelOption? get() = models.firstOrNull { it.model.id == modelId }

    /** The chosen style with the colour overrides applied. */
    val style: CaptionStyle
        get() = CaptionStyle.byId(styleId).let { it.withColors(textColor ?: it.colorArgb, highlightColor ?: it.highlightArgb) }
}

sealed interface CaptionsIntent : UiIntent {
    data class Open(val target: CaptionTarget, val existingCaptions: Int = 0) : CaptionsIntent

    /** Opens the sheet without a clip, only to put the captions already on the timeline in another style. */
    data class OpenRestyle(val existingCaptions: Int, val canvasHeight: Int) : CaptionsIntent
    data class SelectTextColor(val argb: Int?) : CaptionsIntent
    data class SelectHighlightColor(val argb: Int?) : CaptionsIntent

    /** Puts every caption already on the timeline in the chosen style (one undo step in the editor). */
    data object ApplyToExisting : CaptionsIntent

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

    /** Every caption on the timeline should take [style]. */
    data class Restyle(val style: CaptionStyle, val canvasHeight: Int) : CaptionsEffect
    data class ShowMessage(val text: String) : CaptionsEffect
}
