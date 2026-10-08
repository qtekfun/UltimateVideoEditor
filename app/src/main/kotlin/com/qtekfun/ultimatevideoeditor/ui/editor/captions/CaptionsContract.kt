package com.qtekfun.ultimatevideoeditor.ui.editor.captions

import com.qtekfun.ultimatevideoeditor.domain.Clip
import com.qtekfun.ultimatevideoeditor.domain.FrameRate
import com.qtekfun.ultimatevideoeditor.domain.captions.CaptionStyle
import com.qtekfun.ultimatevideoeditor.mvi.UiEffect
import com.qtekfun.ultimatevideoeditor.mvi.UiIntent
import com.qtekfun.ultimatevideoeditor.mvi.UiState

data class CaptionsState(
    /** True while the captions sheet is open. */
    val isOpen: Boolean = false,
    val fps: FrameRate = FrameRate(30, 1),
    val canvasHeight: Int = 0,
    /** Captions already on the timeline, which the chosen style can be applied to. */
    val existingCaptions: Int = 0,
    val styleId: String = CaptionStyle.CLASSIC.id,
    /** Text and emphasis colour overrides of the chosen style; null keeps the style's own. */
    val textColor: Int? = null,
    val highlightColor: Int? = null,
    /** The caption being typed: its text, where it starts and how long it lasts, in project frames. */
    val draftText: String = "",
    val draftStart: Long = 0,
    val draftLength: Long = 0,
    /** Imported subtitle files start at the playhead instead of the start of the project. */
    val importAtPlayhead: Boolean = false,
    val playhead: Long = 0,
    val importing: Boolean = false,
    val error: String? = null,
) : UiState {
    val canAdd: Boolean get() = draftText.isNotBlank() && draftLength > 0

    /** The chosen style with the colour overrides applied. */
    val style: CaptionStyle
        get() = CaptionStyle.byId(styleId).let { it.withColors(textColor ?: it.colorArgb, highlightColor ?: it.highlightArgb) }
}

sealed interface CaptionsIntent : UiIntent {
    /** Opens the sheet; [playhead] is where a new caption starts by default. */
    data class Open(val fps: FrameRate, val canvasHeight: Int, val playhead: Long, val existingCaptions: Int) : CaptionsIntent

    data object Close : CaptionsIntent
    data class SelectStyle(val id: String) : CaptionsIntent
    data class SelectTextColor(val argb: Int?) : CaptionsIntent
    data class SelectHighlightColor(val argb: Int?) : CaptionsIntent

    /** Puts every caption already on the timeline in the chosen style (one undo step in the editor). */
    data object ApplyToExisting : CaptionsIntent

    data class SetDraftText(val text: String) : CaptionsIntent
    data class NudgeStart(val deltaFrames: Long) : CaptionsIntent
    data class NudgeLength(val deltaFrames: Long) : CaptionsIntent

    /** Adds the typed caption, in the chosen style, to the caption track. */
    data object AddDraft : CaptionsIntent
    data class SetImportAtPlayhead(val enabled: Boolean) : CaptionsIntent

    /** Reads a `.srt` or `.vtt` file the user picked and adds its subtitles as captions. */
    data class ImportFile(val uri: String) : CaptionsIntent
}

sealed interface CaptionsEffect : UiEffect {
    /**
     * Caption title clips for the editor to place as one undo step: on a new track on top, or, with
     * [intoExistingTrack], on the caption track already on the timeline (a new one if there is none).
     */
    data class ClipsReady(val clips: List<Clip>, val intoExistingTrack: Boolean) : CaptionsEffect

    /** Every caption on the timeline should take [style]. */
    data class Restyle(val style: CaptionStyle, val canvasHeight: Int) : CaptionsEffect
    data class ShowMessage(val text: String) : CaptionsEffect
}
