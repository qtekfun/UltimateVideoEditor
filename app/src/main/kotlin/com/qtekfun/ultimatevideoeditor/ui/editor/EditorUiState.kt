package com.qtekfun.ultimatevideoeditor.ui.editor

import com.qtekfun.ultimatevideoeditor.data.relink.FolderRelinkOutcome

/**
 * The stabiliser's state for the selected clip: whether its file has been analysed ([status]) and, while the
 * analysis runs, how far it is ([progress], 0..1; null when nothing runs).
 */
data class StabUiState(
    val clipId: String? = null,
    val status: com.qtekfun.ultimatevideoeditor.engine.stabilise.StabStatus = com.qtekfun.ultimatevideoeditor.engine.stabilise.StabStatus.Off,
    val progress: Float? = null,
)

/** A motion track of the selected clip and where its analysis stands. */
data class TrackItem(val track: com.qtekfun.ultimatevideoeditor.domain.MotionTrack, val status: com.qtekfun.ultimatevideoeditor.engine.track.TrackStatus)

/** A motion track of another clip that the selected clip can follow; [ready] when its analysis covers that clip. */
data class FollowItem(val track: com.qtekfun.ultimatevideoeditor.domain.MotionTrack, val ready: Boolean)

/**
 * Motion tracking (SPECS.md 9.15) for the selected clip: [canTrack] when it is a video clip, its [items], the tracks of
 * other clips it can [followable] follow, whether the preview is waiting for a pick ([picking], with the tap's box
 * [boxSide]), the analysis [progress] (null when none runs, [analysingId] says which), the [activeId] track drawn on
 * the preview and its canvas-space [overlay].
 */
data class TrackUiState(
    val clipId: String? = null,
    val canTrack: Boolean = false,
    val items: List<TrackItem> = emptyList(),
    val followable: List<FollowItem> = emptyList(),
    val picking: Boolean = false,
    val boxSide: Double = com.qtekfun.ultimatevideoeditor.domain.TrackSeed.DEFAULT_SIDE,
    val progress: Float? = null,
    val analysingId: String? = null,
    val activeId: String? = null,
    val overlay: List<com.qtekfun.ultimatevideoeditor.domain.TrackMath.CanvasPoint> = emptyList(),
)

/** What the timeline draws on top of a drag: the clips lifted with a shadow and a line where a moved edge snapped ([guideFrame], null for none). */
data class DragOverlay(val clipIds: List<String>, val guideFrame: Long?)

/** The state of "Relink by scanning a folder" (SPECS 5.40). */
sealed interface FolderRelinkUi {
    data object Idle : FolderRelinkUi

    /** Running: [files] media files seen so far in [folders] folders; then [checked] of [total] matches opened and checked (total 0 while scanning). */
    data class Running(val files: Int = 0, val folders: Int = 0, val checked: Int = 0, val total: Int = 0) : FolderRelinkUi

    /** Finished: what was relinked and what is left for the user. */
    data class Done(val outcome: FolderRelinkOutcome, val showList: Boolean = false) : FolderRelinkUi
}

/** The report without [assetId]: it was relinked or chosen by hand, so it no longer needs the user's attention. */
internal fun FolderRelinkUi.without(assetId: String): FolderRelinkUi = when (this) {
    is FolderRelinkUi.Done -> copy(outcome =
        outcome.copy(
            notFound = outcome.notFound.filter { it.assetId != assetId },
            ambiguous = outcome.ambiguous.filter { it.assetId != assetId },
        ),
    )
    else -> this
}

/** What Paste asks: [destinations] are the selected clips that a 'Style' answer would change, [copiedClips] how many a 'Content' answer adds. */
data class PasteChoice(val destinations: List<String>, val copiedClips: Int)
