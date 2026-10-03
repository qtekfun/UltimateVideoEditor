package com.ultimatevideo.uveditor.domain

import kotlin.math.abs

/**
 * Makes the selected base-track clips end on beats, as one edit (so one Undo restores the lot).
 *
 * Algorithm, in selection order along the base: for each clip, aim for its current end, pick the
 * nearest beat after the clip's start (a tie goes to the earlier beat) and trim the clip's end there.
 * Shortening is always possible; lengthening only as far as the clip's media (or, for a title,
 * photo or sticker, freely): when the nearest beat is out of reach the latest reachable beat after the
 * start is used, and a clip with none is left as it is. Each trim is a base-track trim, so later
 * base clips ripple and overlays follow. The first clip keeps its start; every later clip starts where the
 * previous one now ends, so the cuts between the selected clips land on beats.
 *
 * [sourceLengths] maps clip id to its media length in project frames (null/absent: unknown, so a
 * clip cannot be lengthened). [beatFrames] are timeline frames, any order.
 */
data class CutToBeat(
    val clipIds: List<String>,
    val beatFrames: List<Long>,
    val sourceLengths: Map<String, Long?> = emptyMap(),
) : EditCommand {

    override fun apply(timeline: Timeline): EditResult<Timeline> {
        val base = ClipDeletion.baseTrack(timeline)
            ?: return EditResult.Failure(EditError.CutToBeatUnavailable("there is no base track"))
        val beats = beatFrames.distinct().sorted()
        if (beats.isEmpty()) return EditResult.Failure(EditError.CutToBeatUnavailable("there are no beat markers to cut to"))
        val ordered = clipIds.distinct().mapNotNull { id -> base.clip(id) }.sortedBy { it.timelineStart }
        if (ordered.isEmpty()) return EditResult.Failure(EditError.CutToBeatUnavailable("select clips on the base track first"))

        var current = timeline
        for (selected in ordered) {
            val clip = current.track(base.id)?.clip(selected.id) ?: continue
            val target = targetEnd(clip, beats, sourceLengths[clip.id]) ?: continue
            if (target == clip.timelineEnd.value) continue
            val trimmed = MagneticBase.trim(current, clip.id, TrimEdge.END, FrameIndex(target), sourceLengths[clip.id])
            if (trimmed is EditResult.Success) current = trimmed.value
        }
        return EditResult.Success(current)
    }

    /** The beat frame to end [clip] on, or null when no beat after its start is reachable. */
    private fun targetEnd(clip: Clip, beats: List<Long>, sourceLength: Long?): Long? {
        val start = clip.timelineStart.value
        val aim = clip.timelineEnd.value
        val longest = maxLength(clip, sourceLength)
        val reachable = beats.filter { it > start && (longest == null || it - start <= longest) }
        if (reachable.isEmpty()) return null
        return reachable.minWithOrNull(compareBy({ abs(it - aim) }, { it }))
    }

    /** The longest the clip may be, or null when it has no limit (title, photo, sticker). */
    private fun maxLength(clip: Clip, sourceLength: Long?): Long? {
        if (!clip.hasMedia) return null
        if (sourceLength == null) return clip.durationFrames
        if (clip.isRetimed || clip.reverse) return clip.durationFrames
        return sourceLength - clip.sourceIn.value
    }
}
