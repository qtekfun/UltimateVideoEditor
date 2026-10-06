package com.ultimatevideo.uveditor.ui.frame

import com.ultimatevideo.uveditor.data.model.MediaAssetDto
import com.ultimatevideo.uveditor.domain.FrameRate
import com.ultimatevideo.uveditor.domain.ProjectColorSpace
import com.ultimatevideo.uveditor.domain.Timeline
import com.ultimatevideo.uveditor.domain.stillframe.DEFAULT_JPEG_QUALITY
import com.ultimatevideo.uveditor.domain.stillframe.FrameContent
import com.ultimatevideo.uveditor.domain.stillframe.FrameFit
import com.ultimatevideo.uveditor.domain.stillframe.FrameFormat
import com.ultimatevideo.uveditor.domain.stillframe.FrameRenderPlan
import com.ultimatevideo.uveditor.domain.stillframe.FrameSizePreset
import com.ultimatevideo.uveditor.domain.stillframe.FrameSource
import com.ultimatevideo.uveditor.domain.stillframe.FrameTarget
import com.ultimatevideo.uveditor.domain.stillframe.MAX_FRAME_SIDE
import com.ultimatevideo.uveditor.domain.stillframe.MAX_JPEG_QUALITY
import com.ultimatevideo.uveditor.domain.stillframe.MIN_JPEG_QUALITY
import com.ultimatevideo.uveditor.domain.stillframe.SelectedClipStatus
import com.ultimatevideo.uveditor.domain.stillframe.YOUTUBE_THUMBNAIL_MAX_BYTES
import com.ultimatevideo.uveditor.domain.stillframe.frameContent
import com.ultimatevideo.uveditor.domain.stillframe.frameTarget
import com.ultimatevideo.uveditor.domain.stillframe.planFrameRender
import com.ultimatevideo.uveditor.domain.stillframe.selectedClipStatus
import com.ultimatevideo.uveditor.mvi.UiEffect
import com.ultimatevideo.uveditor.mvi.UiIntent
import com.ultimatevideo.uveditor.mvi.UiState

/** A frozen copy of what the editor holds when the user asks to save the frame under the playhead. */
data class StillFrameInput(
    val projectId: String,
    val projectName: String,
    val projectWidth: Int,
    val projectHeight: Int,
    val fps: FrameRate,
    val timeline: Timeline,
    val assets: List<MediaAssetDto>,
    val colorSpace: ProjectColorSpace,
    /** The project frame to save: the playhead, an integer frame index. */
    val frame: Long,
    val selectedClipId: String?,
    /** Library files that cannot be read; a clip that needs one of them under the frame cannot be drawn. */
    val missingAssetIds: Set<String> = emptySet(),
)

sealed interface StillFramePhase {
    data object Configuring : StillFramePhase

    /** The picture is being drawn and encoded. */
    data object Working : StillFramePhase

    data class Saved(
        val uri: String,
        val fileName: String,
        val width: Int,
        val height: Int,
        val bytes: Long,
        /** The JPEG quality used, or null for PNG. */
        val quality: Int?,
        /** Things the user should know: quality lowered to fit the limit, a file over the limit, a reduced size. */
        val notes: List<String>,
    ) : StillFramePhase

    data class Failed(val message: String) : StillFramePhase
}

data class StillFrameState(
    val visible: Boolean = false,
    val projectName: String = "",
    val timecode: String = "",
    val projectWidth: Int = 1920,
    val projectHeight: Int = 1080,
    val hdrProject: Boolean = false,
    val content: FrameContent = FrameContent.PICTURE,
    val clipStatus: SelectedClipStatus = SelectedClipStatus.NO_SELECTION,
    val source: FrameSource = FrameSource.WHOLE_PICTURE,
    val preset: FrameSizePreset = FrameSizePreset.PROJECT,
    val customWidth: Int = 1920,
    val fit: FrameFit = FrameFit.LETTERBOX,
    val format: FrameFormat = FrameFormat.PNG,
    val jpegQuality: Int = DEFAULT_JPEG_QUALITY,
    val phase: StillFramePhase = StillFramePhase.Configuring,
) : UiState {
    val target: FrameTarget get() = frameTarget(preset, customWidth, projectWidth, projectHeight)
    val renderPlan: FrameRenderPlan get() = planFrameRender(target.size, projectWidth, projectHeight, fit)

    /** The shape of the output differs from the project's, so the fit choice matters. */
    val shapeDiffers: Boolean
        get() = target.size.width.toLong() * projectHeight != target.size.height.toLong() * projectWidth

    /** Only the YouTube thumbnail has a size limit; it applies to JPEG, whose quality can be searched. */
    val sizeLimit: Long? get() = if (preset == FrameSizePreset.YOUTUBE_THUMBNAIL) YOUTUBE_THUMBNAIL_MAX_BYTES else null
    val isWorking: Boolean get() = phase is StillFramePhase.Working
}

sealed interface StillFrameIntent : UiIntent {
    data class Open(val input: StillFrameInput) : StillFrameIntent
    data object Dismiss : StillFrameIntent
    data class SelectSource(val source: FrameSource) : StillFrameIntent
    data class SelectPreset(val preset: FrameSizePreset) : StillFrameIntent
    data class SetCustomWidth(val width: Int) : StillFrameIntent
    data class SelectFit(val fit: FrameFit) : StillFrameIntent
    data class SelectFormat(val format: FrameFormat) : StillFrameIntent
    data class SetQuality(val quality: Int) : StillFrameIntent

    /** Save was pressed: ask where. */
    data object ChooseLocation : StillFrameIntent

    /** The document picker returned; null means it was dismissed. */
    data class LocationChosen(val uri: String?) : StillFrameIntent
    data object Cancel : StillFrameIntent

    /** From a failure or a result back to the settings. */
    data object Back : StillFrameIntent
    data object Share : StillFrameIntent
}

sealed interface StillFrameEffect : UiEffect {
    data class LaunchCreateDocument(val suggestedName: String, val mime: String) : StillFrameEffect
    data class ShareFile(val uri: String, val mime: String) : StillFrameEffect

    /** Shown in the editor, for example "Another export is running: Holiday". */
    data class Message(val text: String) : StillFrameEffect
}

/** The state a freshly opened dialog starts in, from what the editor holds. */
internal fun openedState(input: StillFrameInput, timecode: String): StillFrameState {
    val status = selectedClipStatus(input.timeline, input.selectedClipId, input.frame)
    return StillFrameState(
        visible = true,
        projectName = input.projectName,
        timecode = timecode,
        projectWidth = input.projectWidth,
        projectHeight = input.projectHeight,
        hdrProject = input.colorSpace.isHdr,
        content = frameContent(input.timeline, input.frame),
        clipStatus = status,
        source = FrameSource.WHOLE_PICTURE,
        customWidth = input.projectWidth.coerceAtMost(MAX_FRAME_SIDE),
    )
}

/**
 * The pure part of the reducer: every intent that only edits the settings. Choices that are not available are ignored
 * (the clip source without a usable clip), the YouTube preset moves the format to JPEG, which its size limit needs.
 */
internal fun StillFrameState.edited(intent: StillFrameIntent): StillFrameState {
    if (phase !is StillFramePhase.Configuring) return this
    return when (intent) {
        is StillFrameIntent.SelectSource ->
            if (intent.source == FrameSource.SELECTED_CLIP && clipStatus != SelectedClipStatus.USABLE) this else copy(source = intent.source)
        is StillFrameIntent.SelectPreset -> copy(
            preset = intent.preset,
            format = if (intent.preset == FrameSizePreset.YOUTUBE_THUMBNAIL) FrameFormat.JPEG else format,
        )
        is StillFrameIntent.SetCustomWidth -> copy(customWidth = intent.width.coerceIn(1, Int.MAX_VALUE))
        is StillFrameIntent.SelectFit -> copy(fit = intent.fit)
        is StillFrameIntent.SelectFormat -> copy(format = intent.format)
        is StillFrameIntent.SetQuality -> copy(jpegQuality = intent.quality.coerceIn(MIN_JPEG_QUALITY, MAX_JPEG_QUALITY))
        else -> this
    }
}
