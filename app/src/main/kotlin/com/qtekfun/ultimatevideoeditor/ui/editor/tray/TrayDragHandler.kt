package com.qtekfun.ultimatevideoeditor.ui.editor.tray

import com.qtekfun.ultimatevideoeditor.R
import com.qtekfun.ultimatevideoeditor.data.MediaImportException
import com.qtekfun.ultimatevideoeditor.data.MissingMedia
import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto
import com.qtekfun.ultimatevideoeditor.domain.Clip
import com.qtekfun.ultimatevideoeditor.domain.DropAim
import com.qtekfun.ultimatevideoeditor.domain.DropHint
import com.qtekfun.ultimatevideoeditor.domain.DropKind
import com.qtekfun.ultimatevideoeditor.domain.DropPlan
import com.qtekfun.ultimatevideoeditor.domain.DropTarget
import com.qtekfun.ultimatevideoeditor.domain.EditCommand
import com.qtekfun.ultimatevideoeditor.domain.EditResult
import com.qtekfun.ultimatevideoeditor.domain.FrameIndex
import com.qtekfun.ultimatevideoeditor.domain.SnapGuides
import com.qtekfun.ultimatevideoeditor.domain.Timeline
import com.qtekfun.ultimatevideoeditor.domain.TimelineOps
import com.qtekfun.ultimatevideoeditor.domain.Track
import com.qtekfun.ultimatevideoeditor.domain.TrackType
import com.qtekfun.ultimatevideoeditor.ui.editor.*
import com.qtekfun.ultimatevideoeditor.ui.editor.timeline.TimelineDragHandler
import com.qtekfun.ultimatevideoeditor.ui.editor.tray.AssetKind
import com.qtekfun.ultimatevideoeditor.ui.editor.tray.MediaImportHandler
import com.qtekfun.ultimatevideoeditor.ui.editor.tray.PlacementHandler
import com.qtekfun.ultimatevideoeditor.ui.text.UiText
import com.qtekfun.ultimatevideoeditor.ui.text.reasonOf
import kotlinx.coroutines.launch

/**
 * Dragging media that is not on the timeline yet, from the tray or from another app, onto the canvas: the lane it would land on, the guide line, the
 * drop hint, and the placement on release.
 */
internal class TrayDragHandler(
    host: EditorHost,
    private val timelineDrag: TimelineDragHandler,
    private val placement: PlacementHandler,
    private val mediaImport: MediaImportHandler,
) : EditorHost by host {

    /**
     * A drag of media that is not on the timeline yet: [clip] is the clip a release would create ([type] is
     * the kind of lane it needs). [assetId] is null while files from another app are only hovering.
     */
    private class TrayDragSession(val assetId: String?, val clip: Clip, val type: TrackType) {
        var target: DropTarget? = null
        var latch = NewLaneLatch()
        var command: EditCommand? = null
    }

    private var trayDrag: TrayDragSession? = null

    fun trayDragStart(assetId: String) {
        if (timelineDrag.isDragging || trayDrag != null) return
        val asset = state.value.assets.firstOrNull { it.id == assetId }
        if (asset == null) {
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_that_media_is_no_longer)))
            return
        }
        if (asset.id in state.value.missingMedia) {
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_is_missing_relink_it_first, MissingMedia.nameOf(asset))))
            return
        }
        val (clip, type) = placement.newClipFor(asset) ?: return
        trayDrag = TrayDragSession(asset.id, clip, type)
    }

    /** Hovering files are only known by kind: a stand-in clip of a plausible length lets the indicator show where they would land. */
    fun externalDragStart(kinds: List<AssetKind>) {
        if (timelineDrag.isDragging || trayDrag != null) return
        val kind = kinds.firstOrNull() ?: return
        val type = if (kind == AssetKind.AUDIO) TrackType.AUDIO else TrackType.VIDEO
        val length = if (kind == AssetKind.PHOTO) stillLengthFrames() else state.value.fps.microsToFrames(HOVER_MICROS).coerceAtLeast(1)
        trayDrag = TrayDragSession(null, Clip("clip-hover", null, FrameIndex.ZERO, FrameIndex.ZERO, FrameIndex(length)), type)
    }

    /** The lane under the finger; across a gap between lanes the last one stays, and with none yet the drop cancels. */
    private fun trayTarget(session: TrayDragSession, committed: Timeline, trackIndex: Int, zone: DragZone): DropTarget {
        val shown = state.value.dragPreview ?: committed
        val resolved = resolveDropTarget(
            zone, trackIndex, shown.tracks.map { it.id }, committed.tracks.mapTo(HashSet()) { it.id }, session.target, session.latch,
            session.type == TrackType.VIDEO,
        )
        session.latch = resolved.latch
        return resolved.target ?: DropTarget.Outside
    }

    fun trayDragMove(frame: Long, trackIndex: Int, zone: DragZone) {
        val session = trayDrag ?: return
        val base = history.timeline
        val target = trayTarget(session, base, trackIndex, zone)
        session.target = target
        val decision = DropPlan.decideNew(
            base, session.clip, session.type, FrameIndex(frame), target, timelineDrag.snapWith(base, state.value.playhead),
            DropAim(choice = timelineDrag.effectiveDropChoice(), finger = FrameIndex(frame.coerceAtLeast(0))),
        )
        session.command = decision.command
        if (decision.kind == DropKind.NEW_LANE) {
            // The canvas draws the new-lane placeholder on a lane of the timeline it shows, so show one (empty).
            val top = base.tracks.indexOfFirst { it.type == TrackType.VIDEO }.coerceAtLeast(0)
            val preview = (TimelineOps.addTrack(base, Track(DROP_LANE_ID, TrackType.VIDEO), top) as? EditResult.Success)?.value
            reduce { copy(dragPreview = preview, dropHint = decision.hint.copy(trackId = DROP_LANE_ID), dragOverlay = trayGuide(base, session, frame, decision.hint)) }
            return
        }
        // Free space is shown like an overwrite: the tinted range is where the clip will land.
        val hint = if (decision.kind == DropKind.MOVE) decision.hint.copy(kind = DropKind.OVERWRITE) else decision.hint
        reduce { copy(dragPreview = null, dropHint = hint, dragOverlay = trayGuide(base, session, frame, decision.hint)) }
    }

    /**
     * The snap line for a carried asset: where the clip's start or end was pulled onto the playhead, a marker or a clip edge.
     * Inserts go to a junction, not through snapping, so they have none.
     */
    private fun trayGuide(base: Timeline, session: TrayDragSession, requestedFrame: Long, hint: DropHint): DragOverlay? {
        if (hint.kind != DropKind.OVERWRITE && hint.kind != DropKind.MOVE && hint.kind != DropKind.NEW_LANE) return null
        val targets = SnapGuides.targets(base, emptySet(), timelineDrag.snapWith(base, state.value.playhead))
        val requestedStart = maxOf(requestedFrame, 0L)
        val length = session.clip.durationFrames
        val end = hint.startFrame + length
        val guide = when {
            hint.startFrame == requestedStart -> null
            hint.startFrame in targets -> hint.startFrame
            end in targets -> end
            else -> null
        }
        return guide?.let { DragOverlay(emptyList(), it) }
    }

    fun trayDragEnd(commit: Boolean) {
        val session = trayDrag
        val inserting = state.value.dropHint?.kind == DropKind.INSERT
        trayDrag = null
        reduce { copy(dragPreview = null, dropHint = null, dragOverlay = null) }
        if (!commit || session == null || session.assetId == null) return
        val command = session.command ?: return
        if (execute(placement.placing(command, session.clip, session.type))) {
            placement.selectPlaced(session.clip.id)
            if (inserting) timelineDrag.parkPlayheadAfter(listOf(session.clip.id))
        }
    }

    /** Files dropped from another app: imported first (their length is unknown until probed), then placed where they landed. */
    fun externalDrop(uris: List<String>, frame: Long, trackIndex: Int, zone: DragZone) {
        val session = trayDrag
        // Resolved before the preview is cleared: the lane index was hit on the timeline as it was shown.
        val resolved = session?.let { trayTarget(it, history.timeline, trackIndex, zone) }
        trayDrag = null
        reduce { copy(dragPreview = null, dropHint = null, dragOverlay = null) }
        if (uris.isEmpty()) return
        val target = resolved ?: trayTarget(TrayDragSession(null, Clip("clip-hover", null, FrameIndex.ZERO, FrameIndex.ZERO, FrameIndex(1)), TrackType.VIDEO), history.timeline, trackIndex, zone)
        scope.launch {
            reduce { copy(isImporting = true) }
            var cursor: FrameIndex? = null
            for (uri in uris) {
                try {
                    val asset = mediaImport.assetFor(uri)
                    cursor = if (cursor == null) placeDropped(asset, frame, target) else placement.place(asset, cursor)
                } catch (e: MediaImportException) {
                    emit(EditorEffect.ShowMessage(reasonOf(e, UiText.res(R.string.ed_vm_could_not_import_the_file))))
                }
            }
            reduce { copy(isImporting = false) }
        }
    }

    /** Places [asset] the way a drop at [frame] over [target] would; returns where the clip ends, or null if nothing was placed. */
    private fun placeDropped(asset: MediaAssetDto, frame: Long, target: DropTarget): FrameIndex? {
        val (clip, type) = placement.newClipFor(asset) ?: return null
        val decision = DropPlan.decideNew(
            history.timeline, clip, type, FrameIndex(frame), target, timelineDrag.snapWith(history.timeline, state.value.playhead),
            DropAim(choice = timelineDrag.effectiveDropChoice(), finger = FrameIndex(frame.coerceAtLeast(0))),
        )
        val command = decision.command
        if (command == null) {
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_drop_on_a_lane, MissingMedia.nameOf(asset), UiText.res(type.nameRes()))))
            return null
        }
        if (!execute(placement.placing(command, clip, type))) return null
        placement.selectPlaced(clip.id)
        return history.timeline.trackOfClip(clip.id)?.clip(clip.id)?.timelineEnd
    }
}
