package com.ultimatevideo.uveditor.ui.export

import com.ultimatevideo.uveditor.data.model.MediaAssetDto
import com.ultimatevideo.uveditor.domain.FrameRate
import com.ultimatevideo.uveditor.domain.ProjectColorSpace
import com.ultimatevideo.uveditor.domain.Timeline
import com.ultimatevideo.uveditor.engine.export.ExportCodec
import com.ultimatevideo.uveditor.engine.verify.VerificationOutcome
import com.ultimatevideo.uveditor.mvi.UiEffect
import com.ultimatevideo.uveditor.mvi.UiIntent
import com.ultimatevideo.uveditor.mvi.UiState

/** A frozen copy of what the editor holds when the user opens the export dialog. */
data class ExportInput(
    val projectId: String,
    val projectName: String,
    val projectWidth: Int,
    val projectHeight: Int,
    val fps: FrameRate,
    val timeline: Timeline,
    val assets: List<MediaAssetDto>,
    val colorSpace: ProjectColorSpace = ProjectColorSpace.REC709_SDR,
    /** Library files that cannot be read; clips that need them cannot be exported. */
    val missingAssetIds: Set<String> = emptySet(),
)

sealed interface ExportPhase {
    data object Configuring : ExportPhase
    /** [startedAtMs] is the wall-clock start (for a ticking elapsed time); [estimate] is what the engine's progress implies. */
    data class Running(
        val progressPermille: Int,
        val startedAtMs: Long = 0,
        val estimate: ExportEstimate = ExportEstimate(),
        /** The movie is finished and the saved file is being checked; [progressPermille] is the check's progress. */
        val verifying: Boolean = false,
    ) : ExportPhase

    /** [note] is the exporter's remark (repeated frames); [verification] what the check of the saved file found. */
    data class Done(val uri: String, val fileName: String, val note: String = "", val verification: VerificationOutcome? = null) : ExportPhase
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
    /** The project is HDR (HLG) and the device can encode HEVC Main10 HLG at the chosen size: HDR can be offered. */
    val hdrAvailable: Boolean = false,
    /** Export as HDR (HLG, 10-bit HEVC) instead of SDR. Only ever true when [hdrAvailable]. */
    val hdr: Boolean = false,
    /** Smart export: copy untouched parts of the footage without re-encoding. Off by default; only with HEVC. */
    val smart: Boolean = false,
    /** The project is HDR but this device cannot export it as HDR, so it goes out as SDR. */
    val hdrUnsupportedNotice: Boolean = false,
    /** The user hid the dialog of this project's running export; progress updates then leave it hidden until it ends. */
    val hiddenWhileRunning: Boolean = false,
    /** The project that is exporting while this editor's project is not: the Export button is refused with its name. */
    val blockedBy: String? = null,
    /** What the timeline's video clips say about quality; the defaults and the advice under the bitrate follow it. */
    val sources: UsedSources = UsedSources(),
    /** The settings derived from [sources] at open; the bitrate is recomputed when size, rate or codec change. Null before the dialog opens. */
    val recommendation: ExportRecommendation? = null,
    /** Length of the movie in project frames and the project's rate, for the size estimate. */
    val movieFrames: Long = 0,
    val projectFps: FrameRate? = null,
    /** Free bytes of the device's shared storage, or null when unknown. */
    val freeBytes: Long? = null,
) : UiState {
    val isRunning: Boolean get() = phase is ExportPhase.Running

    /** About how big the file will be with the chosen bitrate (recomputed with every setting; null for an empty movie). */
    val sizeEstimate: SizeEstimate?
        get() = projectFps?.let { estimateExportSize(movieFrames, it, bitrateMbps, if (sources.hasAudio) ESTIMATE_AUDIO_BITRATE else 0L) }
}

sealed interface ExportIntent : UiIntent {
    data class Open(val input: ExportInput) : ExportIntent

    /** Show this project's export (running, finished or failed) in the dialog: from the notification or the project list. */
    data object ShowProgress : ExportIntent
    data object Dismiss : ExportIntent
    data class SelectResolution(val option: ResolutionOption) : ExportIntent
    data class SelectFrameRate(val rate: FrameRate) : ExportIntent
    data class SelectCodec(val codec: ExportCodec) : ExportIntent
    data class SelectBitrate(val mbps: Int) : ExportIntent

    /** HDR (HLG) or SDR output; HDR also switches the codec to HEVC. */
    data class SelectHdr(val hdr: Boolean) : ExportIntent

    /** Smart export on or off (HEVC only). */
    data class SelectSmart(val smart: Boolean) : ExportIntent

    /** Fill resolution, rate, codec and bitrate for an upload destination. */
    data class SelectPreset(val preset: ExportPreset) : ExportIntent

    /** The user pressed Export: ask where to save. */
    data object ChooseLocation : ExportIntent

    /** The document picker returned; null means it was dismissed. */
    data class LocationChosen(val uri: String?) : ExportIntent
    data object Cancel : ExportIntent
    data object Share : ExportIntent

    /** After a failed verification: keep the file and go back to the settings to export again. */
    data object ExportAgain : ExportIntent
}

sealed interface ExportEffect : UiEffect {
    data class LaunchCreateDocument(val suggestedName: String) : ExportEffect
    data class ShareFile(val uri: String) : ExportEffect

    /** Shown in the editor, for example "Another export is running: Holiday". */
    data class Message(val text: String) : ExportEffect
}
