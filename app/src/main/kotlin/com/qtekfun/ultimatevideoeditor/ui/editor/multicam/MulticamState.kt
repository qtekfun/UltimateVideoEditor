package com.qtekfun.ultimatevideoeditor.ui.editor.multicam

import com.qtekfun.ultimatevideoeditor.domain.multicam.AngleCut

/** How an angle was lined up with the reference one. */
sealed interface SyncOutcome {
    /** The first angle: everything else is measured against it. */
    data object Reference : SyncOutcome

    /** Found by listening: [offsetFrames] later than the reference (negative: earlier); [confident] is false for a doubtful match. */
    data class Found(val offsetFrames: Long, val confidence: Double, val confident: Boolean) : SyncOutcome

    /** Could not be synced: [reason] says why (no waveform yet, silence, too short). */
    data class Failed(val reason: String) : SyncOutcome
}

/** The angles being chosen for a new multicam clip, in order; the first is the reference. */
data class MulticamDraft(
    val assetIds: List<String> = emptyList(),
    /** Where each angle starts in shared time, in project frames, by asset id (sync result plus manual nudges). */
    val offsets: Map<String, Long> = emptyMap(),
    val outcomes: Map<String, SyncOutcome> = emptyMap(),
) {
    fun offsetOf(assetId: String): Long = offsets[assetId] ?: 0L
}

/** The multicam sheet: choosing and syncing angles, then cutting between them. */
data class MulticamUiState(
    val open: Boolean = false,
    val draft: MulticamDraft = MulticamDraft(),
    /** Listening for matching sound to line the angles up. */
    val syncing: Boolean = false,
    /** Angle taps are collected (with the playhead frame) and applied as one undo step when recording stops. */
    val recording: Boolean = false,
    val pendingCuts: List<AngleCut> = emptyList(),
)

sealed interface MulticamIntent {
    data object Open : MulticamIntent

    data object Close : MulticamIntent

    /** Adds or removes a library file from the draft (at most six angles). */
    data class ToggleAsset(val assetId: String) : MulticamIntent

    /** Lines the draft's angles up by their sound. */
    data object Sync : MulticamIntent

    data class NudgeDraft(val assetId: String, val deltaFrames: Long) : MulticamIntent

    /** Builds the multicam clip from the draft at the playhead and puts it on the base. */
    data object Create : MulticamIntent

    /** Shows [angle] from the playhead; collected while recording. */
    data class CutTo(val angle: Int) : MulticamIntent

    data object ToggleRecording : MulticamIntent

    data class NudgeAngle(val groupId: String, val angle: Int, val deltaFrames: Long) : MulticamIntent

    data class SetAudioAngle(val groupId: String, val angle: Int) : MulticamIntent

    /** Removes the cut at the playhead (the angle before it carries on). */
    data class RemoveCutHere(val groupId: String) : MulticamIntent

    /** Listens again and applies the offsets found to an existing multicam clip. */
    data class Resync(val groupId: String) : MulticamIntent

    data class Flatten(val groupId: String) : MulticamIntent
}
