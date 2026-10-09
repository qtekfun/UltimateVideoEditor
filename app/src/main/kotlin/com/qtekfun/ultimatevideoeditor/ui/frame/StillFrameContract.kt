package com.qtekfun.ultimatevideoeditor.ui.frame

import com.qtekfun.ultimatevideoeditor.ui.text.UiText
import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto
import com.qtekfun.ultimatevideoeditor.domain.FrameRate
import com.qtekfun.ultimatevideoeditor.domain.Timeline
import com.qtekfun.ultimatevideoeditor.mvi.UiEffect
import com.qtekfun.ultimatevideoeditor.mvi.UiIntent
import com.qtekfun.ultimatevideoeditor.mvi.UiState

/** A frozen copy of what the editor holds when the user taps the frame button. */
data class StillFrameInput(
    val projectId: String,
    val projectName: String,
    val projectWidth: Int,
    val projectHeight: Int,
    val fps: FrameRate,
    val timeline: Timeline,
    val assets: List<MediaAssetDto>,
    /** The project frame to save: the playhead, an integer frame index. */
    val frame: Long,
    /** Library files that cannot be read; a clip that needs one of them under the frame cannot be drawn. */
    val missingAssetIds: Set<String> = emptySet(),
)

/** A saved picture the snackbar offers to share or open. */
data class SavedFrame(val uri: String, val fileName: String, val folder: String, val width: Int, val height: Int, val notes: List<UiText>)

sealed interface StillFramePhase {
    data object Idle : StillFramePhase

    /** The picture is being drawn, encoded and written. */
    data object Saving : StillFramePhase

    data class Saved(val frame: SavedFrame) : StillFramePhase

    data class Failed(val message: UiText) : StillFramePhase
}

data class StillFrameState(val phase: StillFramePhase = StillFramePhase.Idle) : UiState {
    val isSaving: Boolean get() = phase is StillFramePhase.Saving
}

sealed interface StillFrameIntent : UiIntent {
    /** The frame button was tapped: save the frame now, with no questions. */
    data class Save(val input: StillFrameInput) : StillFrameIntent

    /** Stops a save that is running. */
    data object Cancel : StillFrameIntent

    /** Share the last saved picture. */
    data object Share : StillFrameIntent

    /** Open the last saved picture in the system viewer. */
    data object Open : StillFrameIntent
}

sealed interface StillFrameEffect : UiEffect {
    /** The snackbar text while the picture is made. */
    data object Started : StillFrameEffect

    data class Saved(val frame: SavedFrame) : StillFrameEffect

    /** A refusal or failure, shown as a snackbar: "Another export is running: Holiday". */
    data class Message(val text: UiText) : StillFrameEffect

    data class ShareFile(val uri: String) : StillFrameEffect

    data class OpenFile(val uri: String) : StillFrameEffect
}
