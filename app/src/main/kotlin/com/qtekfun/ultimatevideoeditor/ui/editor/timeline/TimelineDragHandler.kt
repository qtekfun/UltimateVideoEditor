package com.qtekfun.ultimatevideoeditor.ui.editor.timeline

import com.qtekfun.ultimatevideoeditor.domain.ClipDeletion
import com.qtekfun.ultimatevideoeditor.domain.DropAim
import com.qtekfun.ultimatevideoeditor.domain.DropChoice
import com.qtekfun.ultimatevideoeditor.domain.DropHint
import com.qtekfun.ultimatevideoeditor.domain.DropKind
import com.qtekfun.ultimatevideoeditor.domain.DropPlan
import com.qtekfun.ultimatevideoeditor.domain.DropTarget
import com.qtekfun.ultimatevideoeditor.domain.EditCommand
import com.qtekfun.ultimatevideoeditor.domain.EditResult
import com.qtekfun.ultimatevideoeditor.domain.FrameIndex
import com.qtekfun.ultimatevideoeditor.domain.GroupMove
import com.qtekfun.ultimatevideoeditor.domain.GroupMoveToLanes
import com.qtekfun.ultimatevideoeditor.domain.GroupOps
import com.qtekfun.ultimatevideoeditor.domain.MoveMarker
import com.qtekfun.ultimatevideoeditor.domain.Snap
import com.qtekfun.ultimatevideoeditor.domain.SnapGuides
import com.qtekfun.ultimatevideoeditor.domain.Timeline
import com.qtekfun.ultimatevideoeditor.domain.Track
import com.qtekfun.ultimatevideoeditor.domain.TrackType
import com.qtekfun.ultimatevideoeditor.domain.TrimEdge
import com.qtekfun.ultimatevideoeditor.domain.markersFrom
import com.qtekfun.ultimatevideoeditor.engine.timeline.HitKind
import com.qtekfun.ultimatevideoeditor.engine.timeline.TimelineHit
import com.qtekfun.ultimatevideoeditor.ui.editor.*
import com.qtekfun.ultimatevideoeditor.ui.editor.playback.PlaybackController
import com.qtekfun.ultimatevideoeditor.ui.editor.timeline.LaneHandler
import kotlin.math.abs

/**
 * Dragging on the timeline canvas: moving and trimming clips (one or a selected group), scrubbing the playhead and dragging a marker. A
 * drag is shown live as a preview and committed as one undo step on release; the drop mode decides how a moved clip lands (SPECS.md 5).
 */
internal class TimelineDragHandler(
    host: EditorHost,
    private val playback: PlaybackController,
    private val lanes: LaneHandler,
    private val markers: MarkerHandler,
) : EditorHost by host {

    private enum class DragMode { MOVE, TRIM_START, TRIM_END, PLAYHEAD, MARKER }

    private class DragSession(val clipId: String, val mode: DragMode, val grabOffset: Long, val group: List<String>? = null) {
        /** Last lane the finger was over, kept while it crosses a gap between lanes. */
        var target: DropTarget? = null

        /** Holds the 'add a lane' target steady while its provisional row shifts the layout under the finger. */
        var latch = NewLaneLatch()

        /** The last finger position, to decide again when the user flips the mode without moving. */
        var last: LastMove? = null

        /** The clips an INSERT at the current position would drop into a cut; the playhead parks after them once it is applied. */
        var insertIds: List<String>? = null
    }

    private class LastMove(val frame: Long, val trackIndex: Int, val zone: DragZone, val reachFrames: Long)

    private var drag: DragSession? = null

    /** True while a drag on the canvas is in progress; edits that would change the timeline under it wait. */
    val isDragging: Boolean get() = drag != null

    /** Drops the drag in progress without committing it (the timeline was replaced under it). */
    fun cancel() {
        drag = null
        pendingDragCommand = null
    }

    private var pendingDragCommand: EditCommand? = null

    /** True if a drag that starts on [hit] should edit the clip instead of scrolling the timeline. */
    fun canDrag(hit: TimelineHit): Boolean {
        // The playhead (or anywhere on the ruler) scrubs; clips only drag once selected.
        if (hit.kind == HitKind.PLAYHEAD || hit.kind == HitKind.RULER || hit.kind == HitKind.MARKER) return true
        if (state.value.selectedClipId == null) return false
        val onClip = hit.kind == HitKind.CLIP || hit.kind == HitKind.CLIP_LEFT_EDGE || hit.kind == HitKind.CLIP_RIGHT_EDGE
        return onClip && clipKeys.idFor(hit.clipKey) in state.value.selection
    }

    fun dragStart(hit: TimelineHit) {
        if (!canDrag(hit)) return
        if (hit.kind == HitKind.PLAYHEAD || hit.kind == HitKind.RULER) {
            playback.pausePlayback()
            drag = DragSession(clipId = "", mode = DragMode.PLAYHEAD, grabOffset = 0)
            pendingDragCommand = null
            playback.setPlayhead(hit.frame)
            return
        }
        if (hit.kind == HitKind.MARKER) {
            val marker = history.timeline.markers.getOrNull(hit.clipKey.toInt()) ?: return
            markers.closeMarkerPopup()
            playback.pausePlayback()
            drag = DragSession(clipId = marker.id, mode = DragMode.MARKER, grabOffset = hit.frame - marker.frame.value)
            pendingDragCommand = null
            return
        }
        val clipId = clipKeys.idFor(hit.clipKey) ?: return
        val clip = history.timeline.trackOfClip(clipId)?.clip(clipId) ?: return
        // With several clips selected a drag moves them all, wherever on a clip it starts: trimming is for one clip.
        val several = state.value.selection.size > 1 && clipId in state.value.selection
        val mode = when {
            several -> DragMode.MOVE
            hit.kind == HitKind.CLIP_LEFT_EDGE -> DragMode.TRIM_START
            hit.kind == HitKind.CLIP_RIGHT_EDGE -> DragMode.TRIM_END
            else -> DragMode.MOVE
        }
        val group = state.value.selection.takeIf { mode == DragMode.MOVE && it.size > 1 && clipId in it }?.toList()
        drag = DragSession(clipId, mode, hit.frame - clip.timelineStart.value, group)
        pendingDragCommand = null
    }

    /**
     * The drop target under the finger. [trackIndex] indexes the timeline being shown, which during a
     * drag may contain the provisional new lane; that lane (not in the committed timeline) keeps the
     * target on 'add a lane' so the preview does not flicker as it appears.
     */
    private fun dropTarget(session: DragSession, committed: Timeline, trackIndex: Int, zone: DragZone): DropTarget? {
        val shown = state.value.dragPreview ?: committed
        val canMakeLane = committed.trackOfClip(session.clipId)?.type == TrackType.VIDEO
        val resolved = resolveDropTarget(
            zone, trackIndex, shown.tracks.map { it.id }, committed.tracks.mapTo(HashSet()) { it.id }, session.target, session.latch, canMakeLane,
        )
        session.latch = resolved.latch
        return resolved.target
    }

    fun dragMove(frame: Long, trackIndex: Int, zone: DragZone, reachFrames: Long) {
        drag?.last = LastMove(frame, trackIndex, zone, reachFrames)
        dragStep(frame, trackIndex, zone, reachFrames)
        updateDragOverlay()
    }

    /** The choice for this drag: what the chip or a second finger asked for, else the drop mode set on the toolbar button. */
    fun effectiveDropChoice(): DropChoice = state.value.dropChoice.takeIf { it != DropChoice.AUTO } ?: state.value.dropMode

    /** The chip (or a second finger) asks for the other action: the drop is decided again at once, without the finger moving. */
    fun flipDropChoice() {
        val session = drag ?: return
        val last = session.last ?: return
        if (!state.value.dropChoiceOffered) return
        val effective = state.value.dropHint?.kind ?: return
        reduce { copy(dropChoice = dropChoice.flipped(effective)) }
        dragMove(last.frame, last.trackIndex, last.zone, last.reachFrames)
    }

    /** The clips being dragged or trimmed and where a moved edge snapped, read from the preview the step just produced. */
    private fun updateDragOverlay() {
        val session = drag ?: return
        if (session.mode == DragMode.PLAYHEAD || session.mode == DragMode.MARKER) return
        val ids = session.group ?: listOf(session.clipId)
        val base = history.timeline
        val preview = state.value.dragPreview
        val guide = preview?.let { SnapGuides.guideFrame(base, it, ids, snapWith(base, state.value.playhead)) }
        val overlay = DragOverlay(ids, guide)
        if (state.value.dragOverlay != overlay) reduce { copy(dragOverlay = overlay) }
    }

    /** Snapshot keys of the dragged clips, for the canvas. */
    fun dragOverlayKeys(overlay: DragOverlay): LongArray = LongArray(overlay.clipIds.size) { clipKeys.keyFor(overlay.clipIds[it]) }

    private fun dragStep(frame: Long, trackIndex: Int, zone: DragZone, reachFrames: Long = DropPlan.INSERT_RADIUS_FRAMES) {
        val session = drag ?: return
        if (session.mode == DragMode.PLAYHEAD) {
            playback.setPlayhead(frame)
            return
        }
        if (session.mode == DragMode.MARKER) {
            markerDrag(session, frame)
            return
        }
        val base = history.timeline
        val sourceTrack = base.trackOfClip(session.clipId) ?: return
        val clip = sourceTrack.clip(session.clipId) ?: return
        val playhead = state.value.playhead
        val command = when (session.mode) {
            DragMode.MOVE -> {
                if (session.group != null) {
                    groupDrag(session, base, frame, trackIndex, zone, playhead, reachFrames)
                    return
                }
                val target = dropTarget(session, base, trackIndex, zone)
                session.target = target
                moveDrag(
                    session, base, FrameIndex((frame - session.grabOffset).coerceAtLeast(0)), target ?: DropTarget.Lane(sourceTrack.id), playhead,
                    DropAim(reachFrames, FrameIndex(frame.coerceAtLeast(0)), effectiveDropChoice()),
                )
                return
            }
            DragMode.TRIM_START -> EditCommand.TrimClip(
                clipId = session.clipId,
                edge = TrimEdge.START,
                frame = snapFrame(base, session.clipId, frame, playhead),
            )
            DragMode.TRIM_END -> EditCommand.TrimClip(
                clipId = session.clipId,
                edge = TrimEdge.END,
                frame = snapFrame(base, session.clipId, frame, playhead),
                sourceLength = assetLengthFrames(clip.assetId),
            )
            DragMode.PLAYHEAD, DragMode.MARKER -> return
        }
        // A rejected position (overlap, out of range) keeps the last valid preview on screen.
        val result = command.apply(base).markersFrom(base)
        if (result is EditResult.Success) {
            pendingDragCommand = if (result.value == base) null else command
            reduce { copy(dragPreview = result.value, dropHint = null) }
        }
    }

    /**
     * One step of a clip move: [DropPlan] decides what releasing here would do, the preview shows it
     * and the hint tells the timeline which indicator to draw. The decision's command is what a
     * release applies, so the indicator and the result always agree.
     */
    private fun moveDrag(session: DragSession, base: Timeline, start: FrameIndex, target: DropTarget, playhead: FrameIndex, aim: DropAim) {
        val decision = DropPlan.decide(base, session.clipId, start, target, snapWith(base, playhead), aim) ?: return
        session.insertIds = listOf(session.clipId).takeIf { decision.kind == DropKind.INSERT }
        val command = decision.command
        if (command == null) {
            // Cancel: the clip shows where it started and a release changes nothing.
            pendingDragCommand = null
            reduce { copy(dragPreview = null, dropHint = decision.hint, dropChoiceOffered = false) }
            return
        }
        val result = command.apply(base).markersFrom(base) as? EditResult.Success ?: return
        val preview = result.value
        pendingDragCommand = if (preview == base) null else command
        val hint = when (decision.kind) {
            DropKind.MOVE -> null
            // Reordering inside the base lands at a cut: show it as an insertion marker there.
            DropKind.REORDER -> preview.trackOfClip(session.clipId)?.clip(session.clipId)?.let {
                DropHint(DropKind.INSERT, preview.trackOfClip(session.clipId)?.id, it.timelineStart.value, it.timelineStart.value)
            }
            // The new lane exists only in the preview: point at the lane that is not in the committed timeline.
            DropKind.NEW_LANE -> DropHint(DropKind.NEW_LANE, preview.tracks.firstOrNull { base.track(it.id) == null }?.id, decision.hint.startFrame, decision.hint.endFrame)
            else -> decision.hint
        }
        val offered = decision.kind == DropKind.INSERT || decision.kind == DropKind.OVERWRITE
        reduce { copy(dragPreview = preview, dropHint = hint, dropChoiceOffered = offered) }
    }

    fun dragEnd(commit: Boolean) {
        val command = pendingDragCommand
        val inserted = drag?.insertIds
        drag = null
        pendingDragCommand = null
        reduce { copy(dropChoice = DropChoice.AUTO, dropChoiceOffered = false) }
        if (commit && command != null && execute(command)) {
            // More can follow: the playhead waits where the inserted clips end.
            inserted?.let(::parkPlayheadAfter)
            return
        }
        reduce { copy(dragPreview = null, dropHint = null, dragOverlay = null) }
    }

    /** Moves the playhead to the end of the latest of [clipIds], so the next insert continues from there. */
    fun parkPlayheadAfter(clipIds: Collection<String>) {
        val end = clipIds.mapNotNull { id -> history.timeline.trackOfClip(id)?.clip(id)?.timelineEnd?.value }.maxOrNull() ?: return
        playback.setPlayhead(end)
    }

    private fun snapFrame(timeline: Timeline, movingClipId: String, frame: Long, playhead: FrameIndex): FrameIndex {
        val targets = buildList {
            add(0L)
            add(playhead.value)
            if (state.value.snapToMarkers) timeline.markers.forEach { add(it.frame.value) }
            for (track in timeline.tracks) {
                for (other in track.clips) {
                    if (other.id == movingClipId) continue
                    add(other.timelineStart.value)
                    add(other.timelineEnd.value)
                }
            }
        }
        val nearest = targets.minByOrNull { abs(it - frame) }
        return FrameIndex(if (nearest != null && abs(nearest - frame) <= SNAP_THRESHOLD_FRAMES) nearest else frame.coerceAtLeast(0))
    }

    /** Snapping for a drag: clip edges and the playhead, plus the ruler markers while marker snapping is on. */
    fun snapWith(timeline: Timeline, playhead: FrameIndex): Snap =
        Snap(playhead, SNAP_THRESHOLD_FRAMES, if (state.value.snapToMarkers) timeline.markers.map { it.frame } else emptyList())

    /**
     * One step of dragging a selected clip with several selected: the whole group moves by the same
     * frames (snapped as a block) and, when the finger is over another lane of the same kind, by the same
     * number of lanes. A position that would put a clip on another one keeps the last valid preview.
     */
    private fun groupDrag(
        session: DragSession,
        base: Timeline,
        frame: Long,
        trackIndex: Int,
        zone: DragZone,
        playhead: FrameIndex,
        reachFrames: Long,
    ) {
        val ids = session.group ?: return
        session.insertIds = null
        if (zone == DragZone.OUTSIDE) {
            pendingDragCommand = null
            reduce { copy(dragPreview = null, dropHint = DropHint(DropKind.CANCEL, null, 0, 0)) }
            return
        }
        val anchorTrack = base.trackOfClip(session.clipId) ?: return
        val anchor = anchorTrack.clip(session.clipId) ?: return
        val earliest = ids.mapNotNull { id -> base.trackOfClip(id)?.clip(id)?.timelineStart?.value }.minOrNull() ?: return
        val requested = ((frame - session.grabOffset) - anchor.timelineStart.value).coerceAtLeast(-earliest)
        // The lane under the finger, as the canvas shows it (a lane made by the preview counts as "a new lane").
        val shown = state.value.dragPreview ?: base
        val overId = if (zone == DragZone.LANES) shown.tracks.getOrNull(trackIndex)?.id else null
        val baseTrack = ClipDeletion.baseTrack(base)
        // Over the base the group is dropped into a cut, in order, like a single clip is.
        if (baseTrack != null && overId == baseTrack.id) {
            val aim = DropAim(reachFrames, FrameIndex(frame.coerceAtLeast(0)), effectiveDropChoice())
            val decision = DropPlan.decideGroupDrop(base, ids, FrameIndex((earliest + requested).coerceAtLeast(0)), aim)
            val dropCommand = decision?.command
            val result = dropCommand?.apply(base)?.markersFrom(base) as? EditResult.Success
            if (decision != null && result != null) {
                session.insertIds = ids.takeIf { decision.kind == DropKind.INSERT }
                pendingDragCommand = if (result.value == base) null else dropCommand
                reduce { copy(dragPreview = result.value, dropHint = decision.hint, dropChoiceOffered = true) }
                return
            }
        }
        val delta = GroupOps.snappedDelta(base, ids, requested, snapWith(base, playhead)).coerceAtLeast(-earliest)
        // Video clips go wherever the finger lets go: onto an overlay lane (even from the base), or above the lanes onto new ones.
        val allVideo = ids.all { id -> base.trackOfClip(id)?.type == TrackType.VIDEO }
        val anyOnBase = ids.any { id -> base.trackOfClip(id)?.id == baseTrack?.id }
        val toNewLanes = allVideo && (zone == DragZone.ABOVE_LANES || (overId != null && base.track(overId) == null))
        val toOverlay = allVideo && anyOnBase && overId != null && overId != baseTrack?.id && base.track(overId)?.type == TrackType.VIDEO
        if (toNewLanes || toOverlay) {
            val command = GroupMoveToLanes(ids, delta, session.clipId, overId.takeIf { toOverlay })
            val result = command.apply(base).markersFrom(base) as? EditResult.Success ?: return
            pendingDragCommand = if (result.value == base) null else command
            val at = earliest + delta
            val hint = if (toNewLanes) DropHint(DropKind.NEW_LANE, null, at, at + ids.sumOf { id -> base.trackOfClip(id)?.clip(id)?.durationFrames ?: 0L }) else null
            reduce { copy(dragPreview = result.value, dropHint = hint, dropChoiceOffered = false) }
            return
        }
        val command = GroupMove(ids, delta, laneDeltaFor(base, anchorTrack, trackIndex, zone))
        val result = command.apply(base).markersFrom(base) as? EditResult.Success ?: return
        pendingDragCommand = if (result.value == base) null else command
        reduce { copy(dragPreview = result.value, dropHint = null, dropChoiceOffered = false) }
    }

    /** Lanes of the same kind between the lane of the grabbed clip and the one under the finger (0 on the base or another kind). */
    private fun laneDeltaFor(base: Timeline, source: Track, trackIndex: Int, zone: DragZone): Int {
        if (zone != DragZone.LANES) return 0
        val baseTrack = ClipDeletion.baseTrack(base)
        if (baseTrack != null && source.id == baseTrack.id) return 0
        val under = base.tracks.getOrNull(trackIndex) ?: return 0
        if (under.type != source.type || under.id == baseTrack?.id) return 0
        val lanes = base.tracks.filter { it.type == source.type && it.id != baseTrack?.id }
        return lanes.indexOfFirst { it.id == under.id } - lanes.indexOfFirst { it.id == source.id }
    }

    /** A marker follows the finger in whole frames, snapping to clip edges, the playhead and the other markers. */
    private fun markerDrag(session: DragSession, fingerFrame: Long) {
        val base = history.timeline
        val frame = snapMarkerFrame(base, session.clipId, fingerFrame - session.grabOffset, state.value.playhead)
        val command = MoveMarker(session.clipId, frame)
        val result = command.apply(base).markersFrom(base) as? EditResult.Success ?: return
        pendingDragCommand = if (result.value == base) null else command
        reduce { copy(dragPreview = result.value, dropHint = null) }
    }

    private fun snapMarkerFrame(timeline: Timeline, markerId: String, frame: Long, playhead: FrameIndex): FrameIndex {
        val targets = buildList {
            add(0L)
            add(playhead.value)
            timeline.markers.forEach { if (it.id != markerId) add(it.frame.value) }
            for (track in timeline.tracks) {
                for (clip in track.clips) {
                    add(clip.timelineStart.value)
                    add(clip.timelineEnd.value)
                }
            }
        }
        val nearest = targets.minByOrNull { abs(it - frame) }
        return FrameIndex(if (nearest != null && abs(nearest - frame) <= SNAP_THRESHOLD_FRAMES) nearest else frame.coerceAtLeast(0))
    }
}
