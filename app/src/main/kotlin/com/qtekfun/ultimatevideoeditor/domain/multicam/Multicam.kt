package com.qtekfun.ultimatevideoeditor.domain.multicam

import com.qtekfun.ultimatevideoeditor.domain.Clip
import com.qtekfun.ultimatevideoeditor.domain.ClipDeletion
import com.qtekfun.ultimatevideoeditor.domain.ClipGain
import com.qtekfun.ultimatevideoeditor.domain.EditError
import com.qtekfun.ultimatevideoeditor.domain.EditResult
import com.qtekfun.ultimatevideoeditor.domain.FrameIndex
import com.qtekfun.ultimatevideoeditor.domain.MagneticBase
import com.qtekfun.ultimatevideoeditor.domain.Timeline
import com.qtekfun.ultimatevideoeditor.domain.TrackType

/**
 * One camera (or audio recorder) of a multicam clip. All angles share one time line, "shared time": frame
 * `t` of shared time is frame `t - offsetFrames` of this angle's own media, so [offsetFrames] is where the
 * angle's first frame sits in shared time (0 for the reference angle, negative if it started earlier).
 * [durationFrames] is the length of the media in project frames; the angle can only be cut to while it
 * covers the shared time asked for.
 */
data class MulticamAngle(
    val id: String,
    val name: String,
    val assetId: String,
    val offsetFrames: Long,
    val durationFrames: Long,
) {
    /** Shared-time frames covered: [coverageStart, coverageEnd). */
    val coverageStart: Long get() = offsetFrames
    val coverageEnd: Long get() = offsetFrames + durationFrames
}

/** From [frame] (counted from the start of the multicam clip) the programme shows angle [angle] (an index into [MulticamClip.angles]). */
data class AngleCut(val frame: Long, val angle: Int)

/**
 * Several synchronised angles cut into one programme (SPECS.md 9.9). The group is realised as ordinary
 * clips on [videoTrackId] (one per stretch of one angle, ids `mc-<id>-v<n>`) and, when [audioTrackId] is
 * set, one clip on that audio track from [audioAngle] for the whole length (`mc-<id>-a`); the video
 * clips are then silenced so the sound never jumps with the picture. Because the clips are ordinary,
 * preview, export and every edit see a flattened timeline, and "flatten" only forgets the group.
 *
 * The group covers shared time [inFrame, inFrame + lengthFrames) and sits on the timeline from
 * [startFrame]; [cuts] are sorted, the first is at 0, and neighbours never show the same angle.
 */
data class MulticamClip(
    val id: String,
    val name: String,
    val angles: List<MulticamAngle>,
    val audioAngle: Int,
    val videoTrackId: String,
    val audioTrackId: String?,
    val startFrame: Long,
    val inFrame: Long,
    val lengthFrames: Long,
    val cuts: List<AngleCut>,
) {
    fun videoClipId(segment: Int) = "mc-$id-v$segment"

    val audioClipId: String get() = "mc-$id-a"

    /** Every `[from, to)` stretch of one angle, counted from the start of the clip. */
    fun segments(): List<Segment> = cuts.mapIndexed { i, cut ->
        Segment(i, cut.frame, cuts.getOrNull(i + 1)?.frame ?: lengthFrames, cut.angle)
    }

    class Segment(val index: Int, val from: Long, val to: Long, val angle: Int) {
        val length: Long get() = to - from
    }

    /** The angle on screen [frame] frames into the clip. */
    fun angleAt(frame: Long): Int = cuts.lastOrNull { it.frame <= frame }?.angle ?: cuts.first().angle

    /** Reason this group cannot be realised, or null. */
    fun problem(): String? {
        if (angles.size !in MulticamOps.MIN_ANGLES..MulticamOps.MAX_ANGLES) {
            return "a multicam clip needs ${MulticamOps.MIN_ANGLES} to ${MulticamOps.MAX_ANGLES} angles"
        }
        if (angles.map { it.id }.toSet().size != angles.size) return "angle ids must be unique"
        if (angles.any { it.durationFrames <= 0 }) return "every angle needs some media"
        if (audioAngle !in angles.indices) return "the audio angle does not exist"
        if (lengthFrames <= 0) return "a multicam clip must be longer than 0 frames"
        if (startFrame < 0) return "a multicam clip cannot start before frame 0"
        if (cuts.isEmpty() || cuts.first().frame != 0L) return "the first cut must be at frame 0"
        for ((i, cut) in cuts.withIndex()) {
            if (cut.angle !in angles.indices) return "cut ${i + 1} shows an angle that does not exist"
            if (cut.frame !in 0 until lengthFrames) return "cut ${i + 1} is outside the clip"
            val previous = cuts.getOrNull(i - 1)
            if (previous != null && cut.frame <= previous.frame) return "cuts must be in order, one per frame"
            if (previous != null && previous.angle == cut.angle) return "two neighbouring cuts show the same angle"
        }
        for (s in segments()) {
            val angle = angles[s.angle]
            val from = inFrame + s.from
            val to = inFrame + s.to
            if (from < angle.coverageStart || to > angle.coverageEnd) {
                return "${angle.name} has no picture for part of cut ${s.index + 1}"
            }
        }
        return null
    }
}

/**
 * Edits of multicam clips. Pure; [com.qtekfun.ultimatevideoeditor.domain.EditHistory] makes them undoable.
 * Every edit that changes the programme rewrites the realised clips in place over the same stretch of
 * the timeline, so the track keeps its length and (on the base) stays free of gaps.
 */
object MulticamOps {
    const val MIN_ANGLES = 2
    const val MAX_ANGLES = 6

    private fun failure(reason: String): EditResult<Timeline> = EditResult.Failure(EditError.InvalidClip(reason))

    /** Ids of the clips realising [group]. */
    private fun realisedIds(group: MulticamClip): Set<String> =
        group.segments().map { group.videoClipId(it.index) }.toSet() + group.audioClipId

    private fun isRealised(clipId: String, groupId: String) = clipId.startsWith("mc-$groupId-")

    /**
     * Adds [group] (with one cut at its start) to the timeline and realises it: on the base the picture is
     * inserted (a ripple, at the nearest cut) and the group's start follows where it landed; on another
     * video lane the stretch must be free. The audio clip, when asked for, needs a free stretch of its lane.
     */
    fun create(timeline: Timeline, group: MulticamClip): EditResult<Timeline> {
        group.problem()?.let { return failure(it) }
        if (group.cuts.size != 1) return failure("a new multicam clip starts with a single cut")
        if (timeline.multicams.any { it.id == group.id }) return failure("a multicam clip with this id already exists")
        val video = timeline.track(group.videoTrackId) ?: return EditResult.Failure(EditError.TrackNotFound(group.videoTrackId))
        if (video.type != TrackType.VIDEO) return failure("a multicam picture goes on a video track")
        val audio = group.audioTrackId?.let { id ->
            timeline.track(id)?.also { if (it.type != TrackType.AUDIO) return failure("the multicam sound goes on an audio track") }
                ?: return EditResult.Failure(EditError.TrackNotFound(id))
        }
        val segment = group.segments().single()
        val videoClip = clipOf(group, segment)
        var current = timeline
        var start = group.startFrame
        val base = ClipDeletion.baseTrack(timeline)
        if (base != null && base.id == video.id) {
            current = when (val inserted = MagneticBase.insert(current, videoClip, FrameIndex(start))) {
                is EditResult.Success -> inserted.value
                is EditResult.Failure -> return inserted
            }
            start = checkNotNull(current.trackOfClip(videoClip.id)?.clip(videoClip.id)).timelineStart.value
        } else {
            video.clips.firstOrNull { it.timelineStart.value < start + group.lengthFrames && start < it.timelineEnd.value }
                ?.let { return EditResult.Failure(EditError.Overlap(it.id)) }
            current = current.withTrackClips(video.id, video.clips + videoClip.copy(timelineStart = FrameIndex(start)))
        }
        val placed = group.copy(startFrame = start)
        if (audio != null) {
            val existing = checkNotNull(current.track(audio.id))
            existing.clips.firstOrNull { it.timelineStart.value < start + group.lengthFrames && start < it.timelineEnd.value }
                ?.let { return EditResult.Failure(EditError.Overlap(it.id)) }
            current = current.withTrackClips(audio.id, existing.clips + audioClipOf(placed))
        }
        return EditResult.Success(current.copy(multicams = current.multicams + placed).pruned())
    }

    /** Shows [angle] from [frame] (counted from the start of the clip) until the next cut. */
    fun cutAt(timeline: Timeline, groupId: String, frame: Long, angle: Int): EditResult<Timeline> =
        record(timeline, groupId, listOf(AngleCut(frame, angle)))

    /**
     * Applies several cuts as one edit (a live recording): each one overrides what the earlier programme
     * showed from its frame until the next cut; a cut that repeats the angle already on screen changes nothing.
     */
    fun record(timeline: Timeline, groupId: String, newCuts: List<AngleCut>): EditResult<Timeline> {
        val group = timeline.multicam(groupId) ?: return failure("multicam clip $groupId not found")
        if (newCuts.isEmpty()) return failure("there is nothing to record")
        var cuts = group.cuts
        for (cut in newCuts.sortedBy { it.frame }) {
            if (cut.frame !in 0 until group.lengthFrames) return failure("a cut is outside the multicam clip")
            if (cut.angle !in group.angles.indices) return failure("a cut shows an angle that does not exist")
            val shown = cuts.lastOrNull { it.frame <= cut.frame }?.angle
            val without = cuts.filter { it.frame != cut.frame }
            cuts = if (shown == cut.angle && without.size == cuts.size) cuts else (without + cut).sortedBy { it.frame }
        }
        return replace(timeline, group.copy(cuts = normalised(cuts)))
    }

    /** Removes the cut at [frame] (never the first): the angle before it carries on. */
    fun removeCut(timeline: Timeline, groupId: String, frame: Long): EditResult<Timeline> {
        val group = timeline.multicam(groupId) ?: return failure("multicam clip $groupId not found")
        if (frame == 0L) return failure("the first cut cannot be removed")
        if (group.cuts.none { it.frame == frame }) return failure("there is no cut at that frame")
        return replace(timeline, group.copy(cuts = normalised(group.cuts.filter { it.frame != frame })))
    }

    /** Moves angle [angleIndex] by [deltaFrames] in shared time (the manual nudge after a sync). */
    fun nudge(timeline: Timeline, groupId: String, angleIndex: Int, deltaFrames: Long): EditResult<Timeline> {
        val group = timeline.multicam(groupId) ?: return failure("multicam clip $groupId not found")
        if (angleIndex !in group.angles.indices) return failure("that angle does not exist")
        val angles = group.angles.mapIndexed { i, a -> if (i == angleIndex) a.copy(offsetFrames = a.offsetFrames + deltaFrames) else a }
        return replace(timeline, group.copy(angles = angles))
    }

    /** Sets the offsets of every angle at once (the result of a sync); [offsets] are in angle order. */
    fun setOffsets(timeline: Timeline, groupId: String, offsets: List<Long>): EditResult<Timeline> {
        val group = timeline.multicam(groupId) ?: return failure("multicam clip $groupId not found")
        if (offsets.size != group.angles.size) return failure("one offset per angle is needed")
        return replace(timeline, group.copy(angles = group.angles.mapIndexed { i, a -> a.copy(offsetFrames = offsets[i]) }))
    }

    /** Chooses which angle's sound plays; needs the group to have an audio lane. */
    fun setAudioAngle(timeline: Timeline, groupId: String, angle: Int): EditResult<Timeline> {
        val group = timeline.multicam(groupId) ?: return failure("multicam clip $groupId not found")
        if (group.audioTrackId == null) return failure("this multicam clip has no audio lane")
        return replace(timeline, group.copy(audioAngle = angle))
    }

    /** Forgets the group and keeps its clips as ordinary clips (export always works on those). */
    fun flatten(timeline: Timeline, groupId: String): EditResult<Timeline> {
        if (timeline.multicam(groupId) == null) return failure("multicam clip $groupId not found")
        return EditResult.Success(timeline.copy(multicams = timeline.multicams.filter { it.id != groupId }))
    }

    /** Rewrites the realised clips of [group] over the same stretch and stores the new group. */
    private fun replace(timeline: Timeline, group: MulticamClip): EditResult<Timeline> {
        group.problem()?.let { return failure(it) }
        val old = timeline.multicam(group.id) ?: return failure("multicam clip ${group.id} not found")
        var current = timeline
        val video = checkNotNull(current.track(group.videoTrackId))
        val keptVideo = video.clips.filterNot { isRealised(it.id, group.id) }
        current = current.withTrackClips(video.id, keptVideo + group.segments().map { clipOf(group, it) })
        old.audioTrackId?.let { id ->
            val audio = checkNotNull(current.track(id))
            val kept = audio.clips.filterNot { isRealised(it.id, group.id) }
            current = current.withTrackClips(id, if (group.audioTrackId == id) kept + audioClipOf(group) else kept)
        }
        return EditResult.Success(current.copy(multicams = current.multicams.map { if (it.id == group.id) group else it }).pruned())
    }

    /** Sorted cuts with neighbours showing the same angle merged. */
    private fun normalised(cuts: List<AngleCut>): List<AngleCut> {
        val out = mutableListOf<AngleCut>()
        for (cut in cuts.sortedBy { it.frame }) if (out.lastOrNull()?.angle != cut.angle) out += cut
        return out
    }

    private fun clipOf(group: MulticamClip, s: MulticamClip.Segment): Clip {
        val angle = group.angles[s.angle]
        val source = group.inFrame + s.from - angle.offsetFrames
        return Clip(
            id = group.videoClipId(s.index),
            assetId = angle.assetId,
            timelineStart = FrameIndex(group.startFrame + s.from),
            sourceIn = FrameIndex(source),
            sourceOut = FrameIndex(source + s.length),
            // With a dedicated audio clip the pictures are silent so the sound does not follow the cuts.
            gainDb = if (group.audioTrackId != null) ClipGain.MIN_DB else 0.0,
        )
    }

    private fun audioClipOf(group: MulticamClip): Clip {
        val angle = group.angles[group.audioAngle]
        val source = group.inFrame - angle.offsetFrames
        return Clip(
            id = group.audioClipId,
            assetId = angle.assetId,
            timelineStart = FrameIndex(group.startFrame),
            sourceIn = FrameIndex(source),
            sourceOut = FrameIndex(source + group.lengthFrames),
        )
    }

    private fun Timeline.withTrackClips(trackId: String, clips: List<Clip>): Timeline =
        copy(tracks = tracks.map { if (it.id == trackId) it.copy(clips = clips.sortedBy { c -> c.timelineStart }) else it })

    /**
     * Keeps the groups whose realised clips are still exactly what [clipOf] would write, following them if
     * the whole group moved (a ripple, a drag of the block); a group someone edited clip by clip (a split,
     * a trim, a deleted piece) is forgotten, and its clips carry on as ordinary clips.
     */
    internal fun settle(timeline: Timeline): Timeline {
        if (timeline.multicams.isEmpty()) return timeline
        val alive = timeline.multicams.mapNotNull { group -> followed(timeline, group) }
        return if (alive == timeline.multicams) timeline else timeline.copy(multicams = alive)
    }

    private fun followed(timeline: Timeline, group: MulticamClip): MulticamClip? {
        val video = timeline.track(group.videoTrackId) ?: return null
        val first = video.clip(group.videoClipId(0)) ?: return null
        val moved = group.copy(startFrame = first.timelineStart.value)
        if (moved.problem() != null) return null
        for (s in moved.segments()) {
            // The user may have styled the clips (transform, effects): only their place and source matter here.
            val actual = video.clip(moved.videoClipId(s.index)) ?: return null
            if (!sameSpan(actual, clipOf(moved, s))) return null
        }
        if (group.audioTrackId != null) {
            val audio = timeline.track(group.audioTrackId)?.clip(group.audioClipId) ?: return null
            if (!sameSpan(audio, audioClipOf(moved))) return null
        }
        return moved
    }

    private fun sameSpan(actual: Clip, expected: Clip): Boolean =
        actual.timelineStart == expected.timelineStart && actual.sourceIn == expected.sourceIn &&
            actual.sourceOut == expected.sourceOut && actual.assetId == expected.assetId && actual.retimedFrames == null

    /** Broken invariants of the groups of [timeline]; empty when fine. */
    fun violations(timeline: Timeline): List<String> {
        val out = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for (group in timeline.multicams) {
            if (!seen.add(group.id)) out += "duplicate multicam clip id ${group.id}"
            group.problem()?.let { out += "multicam clip ${group.id}: $it" }
            for (id in realisedIds(group)) {
                if (id == group.audioClipId && group.audioTrackId == null) continue
                if (timeline.trackOfClip(id) == null) out += "multicam clip ${group.id} lost its clip $id"
            }
        }
        return out
    }
}

/** Where each angle of a multicam viewer gets its picture from, within the decoder budget. */
enum class AngleFeed {
    /** The one angle on screen: decoded at full quality. */
    FULL,

    /** Another angle played from its small proxy copy. */
    PROXY,

    /** Another angle shown as a low-rate still, refreshed a few times a second. */
    STILL,
}

/**
 * The decoder budget of the multicam viewer: only the active angle is decoded at full quality (that is
 * the one the preview already decodes); each other angle gets a proxy decoder if it has a proxy ready and a
 * decoder is left, otherwise a low-rate still. [maxDecoders] is the device's hardware decoder limit.
 */
object MulticamPlanner {
    fun plan(angleCount: Int, active: Int, proxyReady: Set<Int>, maxDecoders: Int): List<AngleFeed> {
        require(angleCount in 1..MulticamOps.MAX_ANGLES) { "angleCount out of range" }
        require(active in 0 until angleCount) { "active angle out of range" }
        var spare = (maxDecoders - 1).coerceAtLeast(0)
        return List(angleCount) { i ->
            when {
                i == active -> AngleFeed.FULL
                i in proxyReady && spare > 0 -> AngleFeed.PROXY.also { spare-- }
                else -> AngleFeed.STILL
            }
        }
    }
}
