package com.ultimatevideo.uveditor.ui.export

import com.ultimatevideo.uveditor.data.model.MediaAssetDto
import com.ultimatevideo.uveditor.domain.FrameRate
import com.ultimatevideo.uveditor.domain.Timeline
import com.ultimatevideo.uveditor.engine.export.ExportCodec
import com.ultimatevideo.uveditor.mvi.UiEffect
import com.ultimatevideo.uveditor.mvi.UiIntent
import com.ultimatevideo.uveditor.mvi.UiState

/** A frozen copy of what the editor holds when the user opens the export dialog. */
data class ExportInput(
    val projectName: String,
    val projectWidth: Int,
    val projectHeight: Int,
    val fps: FrameRate,
    val timeline: Timeline,
    val assets: List<MediaAssetDto>,
)

sealed interface ExportPhase {
    data object Configuring : ExportPhase
    data class Running(val progressPermille: Int) : ExportPhase
    data class Done(val uri: String, val fileName: String) : ExportPhase
    data class Failed(val message: String) : ExportPhase
}

data class ExportState(
    val visible: Boolean = false,
    val projectName: String = "",
    val resolutions: List<ResolutionOption> = emptyList(),
    val resolution: ResolutionOption? = null,
    val frameRates: List<FrameRate> = emptyList(),
    val frameRate: FrameRate? = null,
    val codec: ExportCodec = ExportCodec.H264,
    val bitrateMbps: Int = 12,
    val phase: ExportPhase = ExportPhase.Configuring,
    /** The destination preset that produced the current settings, until one of them is changed by hand. */
    val preset: ExportPreset? = null,
    /** Shape of the project ("9:16"), compared with a preset's to warn about a mismatch. */
    val projectAspect: String = "",
) : UiState {
    val isRunning: Boolean get() = phase is ExportPhase.Running
}

sealed interface ExportIntent : UiIntent {
    data class Open(val input: ExportInput) : ExportIntent
    data object Dismiss : ExportIntent
    data class SelectResolution(val option: ResolutionOption) : ExportIntent
    data class SelectFrameRate(val rate: FrameRate) : ExportIntent
    data class SelectCodec(val codec: ExportCodec) : ExportIntent
    data class SelectBitrate(val mbps: Int) : ExportIntent

    /** Fill resolution, rate, codec and bitrate for an upload destination. */
    data class SelectPreset(val preset: ExportPreset) : ExportIntent

    /** The user pressed Export: ask where to save. */
    data object ChooseLocation : ExportIntent

    /** The document picker returned; null means it was dismissed. */
    data class LocationChosen(val uri: String?) : ExportIntent
    data object Cancel : ExportIntent
    data object Share : ExportIntent
}

sealed interface ExportEffect : UiEffect {
    data class LaunchCreateDocument(val suggestedName: String) : ExportEffect
    data class ShareFile(val uri: String) : ExportEffect
}
