package com.ultimatevideo.uveditor.domain

/** Where the finger is during a clip drag, as far as the drop is concerned. */
sealed interface DropTarget {
    /** Over lane [trackId] (a committed lane of the timeline). */
    data class Lane(val trackId: String) : DropTarget

    /** Above the top lane: the 'add a lane' zone. */
    data object AboveLanes : DropTarget

    /** Far off the lanes: dropping here cancels. */
    data object Outside : DropTarget
}

/** What releasing the clip now would do; also what the timeline indicator shows. */
enum class DropKind {
    /** Plain move in free space (only the ghost is drawn). */
    MOVE,

    /** Reorder inside the base: the clip lands at the nearest cut. */
    REORDER,

    /** Open a gap at a junction and put the clip in it. */
    INSERT,

    /** Replace the footage under the clip. */
    OVERWRITE,

    /** Create a new overlay lane above the others. */
    NEW_LANE,

    /** Put the clip back where it was. */
    CANCEL,
}

/**
 * The indicator for a drop: [kind] on lane [trackId] (null for a new lane or a cancel) over the
 * frames [startFrame, endFrame). For INSERT both are the junction.
 */
data class DropHint(val kind: DropKind, val trackId: String?, val startFrame: Long, val endFrame: Long)

/** [command] is what a release applies (null cancels); [hint] is what the timeline shows meanwhile. */
data class DropDecision(val kind: DropKind, val command: EditCommand?, val hint: DropHint)

/**
 * Single source of truth for dragging a clip, LumaFusion style: the action is chosen by where the clip
 * is dragged, never asked. The same decision draws the indicator during the drag and runs on release.
 *
 * On the base, if the clip's START edge is within [INSERT_RADIUS_FRAMES] of a junction (a cut between
 * two clips, or the lane's start / end) the action is an INSERT there (a ripple); otherwise, over the
 * body of a clip, it is an OVERWRITE of the frames the clip covers. The base has no free space: past
 * its end the clip is appended, a base clip dragged within the base only reorders, and dragged onto an overlay lane (or above the lanes) it is lifted off the base, which closes its gap.
 *
 * On every other lane (overlay, audio, title) there is no insert: landing on existing clips is always
 * an OVERWRITE and free space is a plain MOVE. Inserting on those lanes is deferred.
 */
object DropPlan {
    const val INSERT_RADIUS_FRAMES = 10L

    fun decide(
        timeline: Timeline,
        clipId: String,
        requestedStart: FrameIndex,
        target: DropTarget,
        snap: Snap? = null,
    ): DropDecision? {
        val source = timeline.trackOfClip(clipId) ?: return null
        val clip = checkNotNull(source.clip(clipId))
        val length = clip.durationFrames
        val start = maxOf(requestedStart, FrameIndex.ZERO)
        val base = ClipDeletion.baseTrack(timeline)
        val fromBase = base != null && source.id == base.id
        val canMakeLane = source.type == TrackType.VIDEO

        return when {
            target == DropTarget.Outside -> DropDecision(DropKind.CANCEL, null, DropHint(DropKind.CANCEL, null, 0, 0))
            target == DropTarget.AboveLanes && canMakeLane -> DropDecision(
                DropKind.NEW_LANE,
                if (fromBase) EditCommand.LiftFromBase(clipId, null, start) else EditCommand.MoveToNewLane(clipId, start, snap),
                DropHint(DropKind.NEW_LANE, null, start.value, start.value + length),
            )
            else -> {
                val wanted = (target as? DropTarget.Lane)?.trackId?.let { timeline.track(it) }
                val lane = wanted?.takeIf { it.type == source.type } ?: source
                onLane(timeline, clipId, source, lane, base, start, length, snap)
            }
        }
    }

    private fun onLane(
        timeline: Timeline,
        clipId: String,
        source: Track,
        lane: Track,
        base: Track?,
        start: FrameIndex,
        length: Long,
        snap: Snap?,
    ): DropDecision {
        val onBase = base != null && lane.id == base.id
        if (base != null && source.id == base.id && lane.id != base.id) {
            // Lifting a base clip onto an overlay: the base closes, the clip replaces what it covers up there.
            val covers = lane.clips.any { it.timelineStart < start + length && it.timelineEnd > start }
            val kind = if (covers) DropKind.OVERWRITE else DropKind.MOVE
            return DropDecision(
                kind,
                EditCommand.LiftFromBase(clipId, lane.id, start),
                DropHint(kind, lane.id, start.value, start.value + length),
            )
        }
        if (onBase && source.id == base.id) {
            return DropDecision(
                DropKind.REORDER,
                EditCommand.MoveClip(clipId, start, null, null),
                DropHint(DropKind.REORDER, lane.id, start.value, start.value),
            )
        }
        val others = lane.clips.filter { it.id != clipId }
        val laneEnd = others.lastOrNull()?.timelineEnd?.value ?: 0L
        val insert = { at: Long ->
            DropDecision(
                DropKind.INSERT,
                EditCommand.MoveClip(clipId, FrameIndex(at), lane.id, null),
                DropHint(DropKind.INSERT, lane.id, at, at),
            )
        }
        val overwrite = {
            DropDecision(
                DropKind.OVERWRITE,
                EditCommand.OverwriteMove(clipId, lane.id, start),
                DropHint(DropKind.OVERWRITE, lane.id, start.value, start.value + length),
            )
        }
        return when {
            onBase && others.isEmpty() -> insert(0L)
            onBase && start.value >= laneEnd -> insert(laneEnd)
            onBase -> junctionNear(others, start.value)?.let(insert) ?: overwrite()
            others.any { it.timelineStart < start + length && it.timelineEnd > start } -> overwrite()
            else -> DropDecision(
                DropKind.MOVE,
                EditCommand.MoveClip(clipId, start, lane.id.takeIf { it != source.id }, snap),
                DropHint(DropKind.MOVE, lane.id, start.value, start.value + length),
            )
        }
    }

    /** The junction of [clips] (sorted) nearest to [frame] within the radius, or null. */
    internal fun junctionNear(clips: List<Clip>, frame: Long): Long? {
        if (clips.isEmpty()) return null
        val points = buildList {
            add(clips.first().timelineStart.value)
            for ((left, right) in clips.zipWithNext()) if (left.timelineEnd == right.timelineStart) add(left.timelineEnd.value)
            add(clips.last().timelineEnd.value)
        }
        return points.filter { kotlin.math.abs(it - frame) <= INSERT_RADIUS_FRAMES }.minByOrNull { kotlin.math.abs(it - frame) }
    }
}
