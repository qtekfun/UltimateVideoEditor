package com.qtekfun.ultimatevideoeditor.domain

import kotlin.math.abs

/** A group edit that cannot apply; [reason] is written for the user (the editor shows it as is). */
data class GroupEditUnavailable(val reason: String) : EditError

/** Which edge of the selected clips [GroupOps.align] lines up. */
enum class AlignEdge { START, END }

/** How [GroupOps.applyTransitions] adds transitions to a group. */
enum class GroupTransition {
    /** A crossfade at the cut between each selected clip and the clip that touches its end. */
    BETWEEN,

    /** A fade in at the head and a fade out at the tail of each selected picture clip (a dissolve to and from nothing). */
    HEAD_AND_TAIL,
}

/** The groups of attributes [GroupOps.pasteAttributes] can copy onto other clips. */
enum class AttributeKind { TRANSFORM, EFFECTS, AUDIO, SPEED }

/**
 * What one clip looks and sounds like, taken with [ClipAttributes.of] and applied to other clips with
 * [GroupOps.pasteAttributes]. Keyframes and speed ramps are not copied: they belong to the length of
 * the clip they were made on.
 */
data class ClipAttributes(
    val transform: ClipTransform,
    val fx: ClipFx,
    val gainDb: Double,
    /** Speed as `speedNum/speedDen` times normal; [hasSpeed] is false for titles, stills and freeze frames. */
    val speedNum: Long,
    val speedDen: Long,
    val reverse: Boolean,
    val hasSpeed: Boolean,
) {
    companion object {
        fun of(clip: Clip): ClipAttributes {
            val hasSpeed = clip.hasMedia && clip.sourceSpan > 1
            val span = clip.sourceSpan
            val length = clip.durationFrames
            val divisor = gcd(span, length).coerceAtLeast(1)
            return ClipAttributes(
                transform = clip.transform,
                fx = clip.fx,
                gainDb = clip.gainDb,
                speedNum = if (hasSpeed) span / divisor else 1,
                speedDen = if (hasSpeed) length / divisor else 1,
                reverse = clip.reverse,
                hasSpeed = hasSpeed,
            )
        }

        private tailrec fun gcd(a: Long, b: Long): Long = if (b == 0L) abs(a) else gcd(b, a % b)
    }
}

/**
 * Edits that act on several clips at once. Each function is pure and all-or-nothing: it returns the
 * whole new timeline or a typed failure, never a half-applied edit, so the editor can run one as a
 * single undo step. The base track rules of [MagneticBase] still hold: base clips only move as a run
 * that touches (they reorder), and deleting from the base closes the gap.
 */
object GroupOps {

    private class Member(val track: Track, val clip: Clip)

    private fun unavailable(reason: String): EditResult.Failure = failure(GroupEditUnavailable(reason))

    private fun members(timeline: Timeline, ids: Collection<String>): EditResult<List<Member>> {
        val result = ArrayList<Member>(ids.size)
        for (id in ids.distinct()) {
            val track = timeline.trackOfClip(id) ?: return failure(EditError.ClipNotFound(id))
            result += Member(track, checkNotNull(track.clip(id)))
        }
        return EditResult.Success(result.sortedWith(compareBy({ it.clip.timelineStart.value }, { timeline.tracks.indexOf(it.track) })))
    }

    private inline fun <T, R> EditResult<T>.andThen(next: (T) -> EditResult<R>): EditResult<R> = when (this) {
        is EditResult.Success -> next(value)
        is EditResult.Failure -> this
    }

    // region move and align

    /**
     * Moves the clips by [deltaFrames] (and [laneDelta] lanes down, negative up) keeping their relative
     * offsets and lanes. Overlay, audio and title clips move freely; a clip may not land on another
     * clip that stays, nor on one that moves with it. Base clips can only move together as a run that
     * touches: the run is reordered to the slot its centre lands in. Mixing base and other clips, or
     * changing the lane of base clips, is refused.
     */
    fun move(timeline: Timeline, ids: Collection<String>, deltaFrames: Long, laneDelta: Int = 0): EditResult<Timeline> =
        members(timeline, ids).andThen { sel ->
            if (sel.isEmpty() || (deltaFrames == 0L && laneDelta == 0)) return@andThen EditResult.Success(timeline)
            val base = ClipDeletion.baseTrack(timeline)
            val onBase = sel.filter { base != null && it.track.id == base.id }
            if (onBase.isNotEmpty()) return@andThen moveBase(timeline, sel, onBase, base!!, deltaFrames, laneDelta)
            val lanes = laneMapping(timeline, sel, laneDelta) ?: return@andThen unavailable("There is no lane there for all the selected clips")
            relocate(timeline, sel) { member ->
                lanes.getValue(member.track.id) to (member.clip.timelineStart + deltaFrames)
            }
        }

    private fun moveBase(
        timeline: Timeline,
        sel: List<Member>,
        onBase: List<Member>,
        base: Track,
        deltaFrames: Long,
        laneDelta: Int,
    ): EditResult<Timeline> {
        if (onBase.size != sel.size) {
            return unavailable("To move clips together, select only base-track clips or only clips on the other lanes")
        }
        if (laneDelta != 0) return unavailable("Base-track clips can only be reordered along the base")
        val order = base.clips.map { it.id }
        val indices = sel.map { order.indexOf(it.clip.id) }.sorted()
        if (indices.zipWithNext().any { (a, b) -> b != a + 1 }) {
            return unavailable("Select base clips that touch each other to move them together")
        }
        val first = sel.minOf { it.clip.timelineStart.value }
        return MagneticBase.reorderBlock(timeline, indices.map { order[it] }, FrameIndex(maxOf(0L, first + deltaFrames)))
    }

    /** For every source lane of [sel], the lane it moves to when shifted by [laneDelta] lanes of its kind; null if one is missing. */
    private fun laneMapping(timeline: Timeline, sel: List<Member>, laneDelta: Int): Map<String, Track>? {
        val result = LinkedHashMap<String, Track>()
        if (laneDelta == 0) {
            for (member in sel) result[member.track.id] = member.track
            return result
        }
        val type = sel.first().track.type
        if (sel.any { it.track.type != type }) return null
        val base = ClipDeletion.baseTrack(timeline)
        val lanes = timeline.tracks.filter { it.type == type && it.id != base?.id }
        for (member in sel) {
            val at = lanes.indexOfFirst { it.id == member.track.id }
            val dest = lanes.getOrNull(at + laneDelta) ?: return null
            result[member.track.id] = dest
        }
        return result
    }

    /**
     * Puts each selected clip where [placement] says (a lane and a start), checking that nothing ends
     * up on top of anything else. The selected clips are lifted out first, so they never block each other's old places.
     */
    private fun relocate(timeline: Timeline, sel: List<Member>, placement: (Member) -> Pair<Track, FrameIndex>): EditResult<Timeline> {
        val ids = sel.map { it.clip.id }.toSet()
        val placed = HashMap<String, MutableList<Clip>>()
        for (member in sel) {
            val (dest, start) = placement(member)
            if (start < FrameIndex.ZERO) return failure(EditError.NegativeStart)
            placed.getOrPut(dest.id) { mutableListOf() } += member.clip.copy(timelineStart = start)
        }
        var current = timeline
        for (track in timeline.tracks) {
            val arriving = placed[track.id].orEmpty()
            val leaving = track.clips.any { it.id in ids }
            if (arriving.isEmpty() && !leaving) continue
            val merged = (track.clips.filter { it.id !in ids } + arriving).sortedBy { it.timelineStart }
            for ((a, b) in merged.zipWithNext()) {
                if (a.timelineEnd > b.timelineStart) return failure(EditError.Overlap(if (a.id in ids) b.id else a.id))
            }
            current = current.withTrack(track.withClips(merged))
        }
        return EditResult.Success(current.pruned())
    }

    /**
     * Lines the selected clips up on their first start ([AlignEdge.START]) or last end ([AlignEdge.END]).
     * Clips on the same lane would end up on top of each other and are refused; the base track has no
     * free placement, so it cannot be aligned.
     */
    fun align(timeline: Timeline, ids: Collection<String>, edge: AlignEdge): EditResult<Timeline> =
        members(timeline, ids).andThen { sel ->
            if (sel.size < 2) return@andThen unavailable("Select at least two clips to align")
            val base = ClipDeletion.baseTrack(timeline)
            if (sel.any { base != null && it.track.id == base.id }) {
                return@andThen unavailable("The base track has no gaps, so its clips cannot be aligned")
            }
            val target = when (edge) {
                AlignEdge.START -> sel.minOf { it.clip.timelineStart.value }
                AlignEdge.END -> sel.maxOf { it.clip.timelineEnd.value }
            }
            relocate(timeline, sel) { member ->
                member.track to FrameIndex(if (edge == AlignEdge.START) target else target - member.clip.durationFrames)
            }
        }

    /**
     * The delta nearest [requested] that snaps an edge of the group to the playhead, markers, frame 0
     * or an edge of a clip outside the group, within the threshold; [requested] when nothing is close.
     */
    fun snappedDelta(timeline: Timeline, ids: Collection<String>, requested: Long, snap: Snap?): Long {
        if (snap == null) return requested
        val sel = ids.toSet()
        val targets = buildList {
            add(0L)
            snap.playhead?.let { add(it.value) }
            snap.extraTargets.forEach { add(it.value) }
            for (track in timeline.tracks) for (clip in track.clips) {
                if (clip.id in sel) continue
                add(clip.timelineStart.value)
                add(clip.timelineEnd.value)
            }
        }
        var best: Long? = null
        for (track in timeline.tracks) for (clip in track.clips) {
            if (clip.id !in sel) continue
            for (edge in longArrayOf(clip.timelineStart.value + requested, clip.timelineEnd.value + requested)) {
                for (target in targets) {
                    val delta = target - edge
                    if (abs(delta) <= snap.thresholdFrames && (best == null || abs(delta) < abs(best))) best = delta
                }
            }
        }
        val adjusted = requested + (best ?: 0L)
        // A snap must not push the group before frame 0.
        val earliest = ids.mapNotNull { id -> timeline.trackOfClip(id)?.clip(id)?.timelineStart?.value }.minOrNull() ?: return requested
        return if (earliest + adjusted < 0) requested else adjusted
    }

    // endregion

    // region delete, copy, paste, duplicate

    /**
     * Deletes the clips. Clips on other lanes go first (a gap is left), then base clips from the last
     * to the first, each closing its gap with the overlays following, exactly like deleting one by one.
     */
    fun delete(timeline: Timeline, ids: Collection<String>): EditResult<Timeline> =
        members(timeline, ids).andThen { sel ->
            if (sel.isEmpty()) return@andThen EditResult.Success(timeline)
            val base = ClipDeletion.baseTrack(timeline)
            val isBase = { m: Member -> base != null && m.track.id == base.id }
            val order = sel.filterNot(isBase).map { it.clip.id } +
                sel.filter(isBase).sortedByDescending { it.clip.timelineStart.value }.map { it.clip.id }
            var current = timeline
            for (id in order) {
                // A base deletion can already have removed an overlay that was selected too.
                if (current.trackOfClip(id) == null) continue
                current = when (val next = ClipDeletion.delete(current, id)) {
                    is EditResult.Success -> next.value
                    is EditResult.Failure -> return@andThen next
                }
            }
            EditResult.Success(current)
        }

    /** Pastes a copy of the clips right after the last one ends, on the same lanes (a base run is inserted at that cut). */
    fun duplicate(timeline: Timeline, ids: Collection<String>): EditResult<Timeline> {
        val clipboard = Clipboard.capture(timeline, ids) ?: return unavailable("Select clips to duplicate")
        val end = ids.mapNotNull { timeline.trackOfClip(it)?.clip(it)?.timelineEnd }.maxOrNull() ?: return unavailable("Select clips to duplicate")
        return paste(timeline, clipboard, end)
    }

    /**
     * Pastes the [clipboard] with its earliest clip at [at]: overlay, audio and title clips keep their
     * offsets on their lanes (the same lane when it still exists, else the first lane of their kind);
     * base clips are inserted as a run at the cut nearest [at]. Nothing may land on an existing clip.
     * Every pasted clip gets a fresh id; transitions between pasted clips come along.
     */
    fun paste(timeline: Timeline, clipboard: Clipboard, at: FrameIndex): EditResult<Timeline> {
        if (clipboard.entries.isEmpty()) return unavailable("The clipboard is empty")
        val base = ClipDeletion.baseTrack(timeline)
        var current = timeline
        val newIds = HashMap<String, String>()
        val taken = HashSet<String>()
        fun freshId(original: String): String {
            var n = 1
            var candidate = "$original~c$n"
            while (current.trackOfClip(candidate) != null || candidate in taken) candidate = "$original~c${++n}"
            taken += candidate
            return candidate
        }

        var cursor: FrameIndex? = null
        for (entry in clipboard.entries.filter { it.onBase }) {
            if (base == null) return failure(EditError.NoBaseTrack)
            val id = freshId(entry.clip.id)
            newIds[entry.clip.id] = id
            val where = cursor ?: at
            current = when (val inserted = MagneticBase.insert(current, entry.clip.copy(id = id, timelineStart = where), where)) {
                is EditResult.Success -> inserted.value
                is EditResult.Failure -> return inserted
            }
            val placed = checkNotNull(current.trackOfClip(id)?.clip(id))
            cursor = placed.timelineEnd
        }
        for (entry in clipboard.entries.filter { !it.onBase }) {
            val lane = destinationLane(current, entry) ?: return unavailable("There is no ${entry.trackType.name.lowercase()} lane to paste onto")
            val id = freshId(entry.clip.id)
            newIds[entry.clip.id] = id
            val clip = entry.clip.copy(id = id, timelineStart = at + entry.relativeStart)
            val track = checkNotNull(current.track(lane.id))
            track.clips.firstOrNull { it.overlaps(clip) }?.let { return failure(EditError.Overlap(it.id)) }
            current = current.withTrack(track.withClips(track.clips + clip))
        }
        val transitions = clipboard.transitions.mapNotNull { t ->
            val from = newIds[t.fromClipId] ?: return@mapNotNull null
            val to = newIds[t.toClipId] ?: return@mapNotNull null
            t.copy(id = freshTransitionId(current, t.id), fromClipId = from, toClipId = to)
        }
        return EditResult.Success(current.copy(transitions = current.transitions + transitions).pruned())
    }

    private fun destinationLane(timeline: Timeline, entry: ClipboardEntry): Track? {
        val base = ClipDeletion.baseTrack(timeline)
        val same = timeline.track(entry.trackId)
        if (same != null && same.type == entry.trackType && same.id != base?.id) return same
        return timeline.tracks.firstOrNull { it.type == entry.trackType && it.id != base?.id }
    }

    private fun freshTransitionId(timeline: Timeline, original: String): String {
        var n = 1
        var candidate = "$original~c$n"
        while (timeline.transition(candidate) != null) candidate = "$original~c${++n}"
        return candidate
    }

    // endregion

    // region attributes

    /**
     * Copies [attributes] (the kinds in [kinds]) onto the target clips that can take them: transform and
     * effects on picture clips, gain on clips with sound, speed and reverse on clips with media of
     * their own. Speed rippling follows the base rules. Fails if no target could take anything.
     */
    fun pasteAttributes(
        timeline: Timeline,
        attributes: ClipAttributes,
        targets: Collection<String>,
        kinds: Set<AttributeKind> = AttributeKind.entries.toSet(),
    ): EditResult<Timeline> =
        members(timeline, targets).andThen { sel ->
            var current = timeline
            var applied = 0
            for (member in sel.sortedByDescending { it.clip.timelineStart.value }) {
                val id = member.clip.id
                val clip = member.clip
                val isPicture = member.track.type != TrackType.AUDIO
                if (AttributeKind.TRANSFORM in kinds && isPicture) {
                    current = when (val r = TimelineOps.setTransform(current, id, attributes.transform)) {
                        is EditResult.Success -> r.value
                        is EditResult.Failure -> return@andThen r
                    }
                    applied++
                }
                if (AttributeKind.EFFECTS in kinds && isPicture) {
                    current = when (val r = TimelineOps.setFx(current, id, attributes.fx)) {
                        is EditResult.Success -> r.value
                        is EditResult.Failure -> return@andThen r
                    }
                    applied++
                }
                if (AttributeKind.AUDIO in kinds && hasSound(clip, member.track)) {
                    current = when (val r = TimelineOps.setGain(current, id, attributes.gainDb)) {
                        is EditResult.Success -> r.value
                        is EditResult.Failure -> return@andThen r
                    }
                    applied++
                }
                if (AttributeKind.SPEED in kinds && attributes.hasSpeed && clip.hasMedia && clip.sourceSpan > 1) {
                    current = when (val r = MagneticBase.setSpeed(current, id, attributes.speedNum, attributes.speedDen)) {
                        is EditResult.Success -> r.value
                        is EditResult.Failure -> return@andThen r
                    }
                    current = when (val r = TimelineOps.setReverse(current, id, attributes.reverse)) {
                        is EditResult.Success -> r.value
                        is EditResult.Failure -> return@andThen r
                    }
                    applied++
                }
            }
            if (applied == 0) unavailable("None of the selected clips can take those attributes") else EditResult.Success(current)
        }

    private fun hasSound(clip: Clip, track: Track): Boolean = clip.hasMedia && track.type != TrackType.TITLE

    /** Sets the speed of every selected clip that has media: `num/den` times normal, ripple per the base rules. */
    fun setSpeed(timeline: Timeline, ids: Collection<String>, num: Long, den: Long): EditResult<Timeline> =
        members(timeline, ids).andThen { sel ->
            val targets = sel.filter { it.clip.hasMedia && it.clip.sourceSpan > 1 }.sortedByDescending { it.clip.timelineStart.value }
            if (targets.isEmpty()) return@andThen unavailable("Titles, photos and stickers have no speed to change")
            var current = timeline
            for (member in targets) {
                current = when (val r = MagneticBase.setSpeed(current, member.clip.id, num, den)) {
                    is EditResult.Success -> r.value
                    is EditResult.Failure -> return@andThen r
                }
            }
            EditResult.Success(current)
        }

    /** Sets the audio gain in dB of every selected clip that has sound. */
    fun setGain(timeline: Timeline, ids: Collection<String>, gainDb: Double): EditResult<Timeline> =
        members(timeline, ids).andThen { sel ->
            val targets = sel.filter { hasSound(it.clip, it.track) }
            if (targets.isEmpty()) return@andThen unavailable("None of the selected clips has sound")
            var current = timeline
            for (member in targets) {
                current = when (val r = TimelineOps.setGain(current, member.clip.id, gainDb)) {
                    is EditResult.Success -> r.value
                    is EditResult.Failure -> return@andThen r
                }
            }
            EditResult.Success(current)
        }

    /** Sets the opacity (0 to 1) of every selected picture clip, including its keyframes, so an animated clip follows. */
    fun setOpacity(timeline: Timeline, ids: Collection<String>, opacity: Double): EditResult<Timeline> =
        members(timeline, ids).andThen { sel ->
            val targets = sel.filter { it.track.type != TrackType.AUDIO }
            if (targets.isEmpty()) return@andThen unavailable("None of the selected clips has a picture")
            ClipTransform(opacity = opacity).problem()?.let { return@andThen failure(EditError.InvalidAppearance(it)) }
            var current = timeline
            for (member in targets) {
                val clip = checkNotNull(current.trackOfClip(member.clip.id)?.clip(member.clip.id))
                val updated = clip.copy(
                    transform = clip.transform.copy(opacity = opacity),
                    keyframes = clip.keyframes.map { it.copy(transform = it.transform.copy(opacity = opacity)) },
                )
                val track = checkNotNull(current.trackOfClip(clip.id))
                current = current.withTrack(track.withClips(track.clips.map { if (it.id == clip.id) updated else it }))
            }
            EditResult.Success(current)
        }

    // endregion

    // region transitions

    /**
     * Adds the same transition to many clips. [GroupTransition.BETWEEN] puts a crossfade of up to
     * [durationFrames] at the cut after each selected clip that touches the next one (shorter when the
     * clips or their media cannot give that much; an existing transition there is resized);
     * [GroupTransition.HEAD_AND_TAIL] fades each selected picture clip in and out over up to
     * [durationFrames] at each end. [sourceLengthOf] gives a clip's media length in frames (null for none).
     */
    fun applyTransitions(
        timeline: Timeline,
        ids: Collection<String>,
        durationFrames: Long,
        mode: GroupTransition,
        sourceLengthOf: (Clip) -> Long? = { null },
    ): EditResult<Timeline> {
        if (durationFrames < Transition.MIN_DURATION_FRAMES) {
            return failure(EditError.InvalidTransition("a transition needs at least ${Transition.MIN_DURATION_FRAMES} frames"))
        }
        return members(timeline, ids).andThen { sel ->
            val result = when (mode) {
                GroupTransition.BETWEEN -> crossfades(timeline, sel, durationFrames, sourceLengthOf)
                GroupTransition.HEAD_AND_TAIL -> fades(timeline, sel, durationFrames)
            }
            result ?: unavailable(
                if (mode == GroupTransition.BETWEEN) "None of the selected clips touches a next clip with room for a transition"
                else "None of the selected clips can fade (audio clips, or too short)",
            )
        }
    }

    private fun crossfades(timeline: Timeline, sel: List<Member>, requested: Long, sourceLengthOf: (Clip) -> Long?): EditResult<Timeline>? {
        var current = timeline
        var added = 0
        for (member in sel) {
            val track = checkNotNull(current.trackOfClip(member.clip.id))
            val clip = checkNotNull(track.clip(member.clip.id))
            val next = track.clips.firstOrNull { it.timelineStart == clip.timelineEnd } ?: continue
            val length = sourceLengthOf(clip)
            val room = TimelineOps.maxTransitionFrames(current, clip.id, next.id, length)
            val duration = minOf(requested, room)
            if (duration < Transition.MIN_DURATION_FRAMES) continue
            val existing = current.transitionBetween(clip.id, next.id)
            val step = if (existing != null) {
                TimelineOps.setTransitionDuration(current, existing.id, duration, length)
            } else {
                TimelineOps.addTransition(current, Transition(freshTransitionId(current, "tr-${clip.id}"), clip.id, next.id, duration), length)
            }
            current = when (step) {
                is EditResult.Success -> step.value
                is EditResult.Failure -> return step
            }
            added++
        }
        return if (added == 0) null else EditResult.Success(current)
    }

    private fun fades(timeline: Timeline, sel: List<Member>, requested: Long): EditResult<Timeline>? {
        var current = timeline
        var faded = 0
        for (member in sel) {
            if (member.track.type == TrackType.AUDIO) continue
            val clip = checkNotNull(current.trackOfClip(member.clip.id)?.clip(member.clip.id))
            val length = clip.durationFrames
            if (length < 4) continue
            val d = minOf(requested, length / 2 - 1).coerceAtLeast(1)
            val last = length - 1
            var keys = clip.keyframes
            val inAt = clip.transformAt(d)
            val outAt = clip.transformAt(last - d)
            keys = keys.filter { it.frame in (d + 1) until (last - d) }
            keys = Keyframes.set(keys, Keyframe(0, clip.transformAt(0).copy(opacity = 0.0)))
            keys = Keyframes.set(keys, Keyframe(d, inAt))
            keys = Keyframes.set(keys, Keyframe(last - d, outAt))
            keys = Keyframes.set(keys, Keyframe(last, clip.transformAt(last).copy(opacity = 0.0)))
            val track = checkNotNull(current.trackOfClip(clip.id))
            current = current.withTrack(track.withClips(track.clips.map { if (it.id == clip.id) it.copy(keyframes = keys) else it }))
            faded++
        }
        return if (faded == 0) null else EditResult.Success(current)
    }

    // endregion
}
