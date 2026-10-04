package com.ultimatevideo.uveditor.domain

/**
 * The line the timeline draws at the frame an edge snapped to while clips are dragged or trimmed. The snapping itself is done
 * by the edits ([TimelineOps.move], [GroupOps.snappedDelta], the trim frame); this only reads the outcome: an edge of a moving
 * clip that has moved since the drag began and now sits exactly on a snap target (frame 0, the playhead, the marker frames in
 * [Snap.extraTargets], an edge of a clip that is not moving) is where the line goes. An edge that did not move (the far end of
 * a trimmed clip, a clip still where it started) never gets a line, even if it touches a neighbour.
 */
object SnapGuides {
    /** The frames a moving edge can snap to, given the clips that are moving. */
    fun targets(timeline: Timeline, moving: Set<String>, snap: Snap): Set<Long> = buildSet {
        add(0L)
        snap.playhead?.let { add(it.value) }
        snap.extraTargets.forEach { add(it.value) }
        for (track in timeline.tracks) {
            for (clip in track.clips) {
                if (clip.id in moving) continue
                add(clip.timelineStart.value)
                add(clip.timelineEnd.value)
            }
        }
    }

    /**
     * The frame of the snap line for [preview] (the timeline shown during the drag) against [base] (the timeline before it
     * began), or null when no moved edge of [moving] sits on a target. The start edge wins when both do.
     */
    fun guideFrame(base: Timeline, preview: Timeline, moving: Collection<String>, snap: Snap): Long? {
        if (moving.isEmpty()) return null
        val ids = moving.toSet()
        val targets = targets(preview, ids, snap)
        for (id in moving) {
            val after = preview.trackOfClip(id)?.clip(id) ?: continue
            val before = base.trackOfClip(id)?.clip(id)
            val start = after.timelineStart.value
            val end = after.timelineEnd.value
            if (start != before?.timelineStart?.value && start in targets) return start
            if (end != before?.timelineEnd?.value && end in targets) return end
        }
        return null
    }
}
