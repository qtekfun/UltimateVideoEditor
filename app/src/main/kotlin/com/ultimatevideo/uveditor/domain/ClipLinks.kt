package com.ultimatevideo.uveditor.domain

/**
 * A video clip's own sound and the audio clip detached from it (SPECS.md 5.38).
 *
 * Detaching marks the video clip [Clip.audioDetached] (the render plan then leaves its embedded sound out of the mix) and puts
 * an audio clip with the same source range at the same frames on an audio lane. While the two are linked they share a
 * [Clip.linkId] and an edit of one is applied to the other by [settle]; unlinked, they are independent clips.
 *
 * [settle] works on the outcome of any edit instead of on each operation: it compares the timeline before and after, and a
 * linked clip that did not change while its partner did is moved, trimmed, split, retimed or removed the same way. That is
 * what makes move, trim, split, ripple and delete (and the base track's magnetic reorder) apply to both clips, as one undo step,
 * without a second code path in every operation.
 */
object ClipLinks {

    /** Mixer prefix of the parameter tracks that belong to a clip's sound (volume, pan, EQ, voice). */
    private const val AUDIO_PARAM_PREFIX = "audio."

    private fun unavailable(reason: String): EditResult.Failure = failure(EditError.InvalidAudio(reason))

    private fun linkIdFor(audioClipId: String) = "link-$audioClipId"

    /** The clip that shares [clip]'s link, with the track it is on; null when it is not linked or the partner is missing. */
    fun partnerOf(timeline: Timeline, clip: Clip): Pair<Track, Clip>? {
        val id = clip.linkId ?: return null
        for (track in timeline.tracks) {
            val other = track.clips.firstOrNull { it.linkId == id && it.id != clip.id }
            if (other != null) return track to other
        }
        return null
    }

    /** Broken link invariants: a link joins exactly one video clip and one audio clip of the same source. */
    fun violations(timeline: Timeline): List<String> {
        val linked = timeline.tracks.flatMap { track -> track.clips.filter { it.linkId != null }.map { track to it } }
        if (linked.isEmpty()) return emptyList()
        val out = mutableListOf<String>()
        for ((id, members) in linked.groupBy { it.second.linkId }) {
            if (members.size != 2) {
                out += "link $id joins ${members.size} clips, not 2"
                continue
            }
            val (a, b) = members
            val types = setOf(a.first.type, b.first.type)
            if (types != setOf(TrackType.VIDEO, TrackType.AUDIO)) out += "link $id must join a video clip and an audio clip"
            if (a.second.assetId != b.second.assetId) out += "link $id joins clips of different media"
        }
        return out
    }

    /**
     * How many frames the audio clip plays late (positive) or early (negative) against the video clip: the difference between where
     * the two put the same source frame. Null when the two do not play at the same speed (or are retimed in a way that has no single offset).
     */
    fun syncOffset(video: Clip, audio: Clip): Long? {
        if (video.assetId == null || video.assetId != audio.assetId) return null
        if (!video.isRetimed && !audio.isRetimed) {
            return (audio.timelineStart.value - audio.sourceIn.value) - (video.timelineStart.value - video.sourceIn.value)
        }
        if (video.reverse || audio.reverse || video.speedRamp.isNotEmpty() || audio.speedRamp.isNotEmpty()) return null
        val vSpan = video.sourceSpan
        val aSpan = audio.sourceSpan
        if (vSpan <= 0 || aSpan <= 0 || audio.durationFrames * vSpan != video.durationFrames * aSpan) return null
        val scaled = (audio.sourceIn.value - video.sourceIn.value) * video.durationFrames
        if (scaled % vSpan != 0L) return null
        return (audio.timelineStart.value - video.timelineStart.value) - scaled / vSpan
    }

    /** The first audio lane that is free over [clip]'s frames, else null (the caller then adds a lane). */
    fun audioTrackFor(timeline: Timeline, clip: Clip): String? =
        timeline.tracks.firstOrNull { track ->
            track.type == TrackType.AUDIO && track.clips.none { it.timelineStart < clip.timelineEnd && clip.timelineStart < it.timelineEnd }
        }?.id

    /**
     * What the editor offers for one selected clip (see [infoFor]). [partnerId] is the linked clip; [relinkCandidateId] is the
     * unlinked clip of the same source that [RelinkClips] would join it with. [offsetFrames] is [syncOffset] against whichever of the two
     * exists (positive: the audio plays late); null when there is no such clip or no single offset.
     */
    data class LinkInfo(
        val isVideo: Boolean,
        val canDetach: Boolean,
        val detached: Boolean,
        val partnerId: String?,
        val relinkCandidateId: String?,
        val offsetFrames: Long?,
    ) {
        val linked: Boolean get() = partnerId != null
        val canRestore: Boolean get() = isVideo && detached
        val canRelink: Boolean get() = partnerId == null && relinkCandidateId != null
        val relevant: Boolean get() = canDetach || detached || linked || relinkCandidateId != null
    }

    /**
     * The link state of [clipId] for the inspector. [assetHasAudio] says whether its source has sound at all (a silent video has nothing to
     * detach). Null when the clip is not a video or audio clip of media.
     */
    fun infoFor(timeline: Timeline, clipId: String, assetHasAudio: Boolean): LinkInfo? {
        val track = timeline.trackOfClip(clipId) ?: return null
        val clip = checkNotNull(track.clip(clipId))
        if (track.type == TrackType.TITLE || !clip.hasMedia || clip.assetId == null) return null
        val isVideo = track.type == TrackType.VIDEO
        val partner = partnerOf(timeline, clip)?.second
        val candidate = if (partner != null) null else timeline.tracks
            .filter { it.type == if (isVideo) TrackType.AUDIO else TrackType.VIDEO }
            .flatMap { it.clips }
            .filter { it.assetId == clip.assetId && it.linkId == null && it.hasMedia && it.still == null }
            .minByOrNull { kotlin.math.abs(it.timelineStart.value - clip.timelineStart.value) }
        val other = partner ?: candidate
        val offset = other?.let { if (isVideo) syncOffset(clip, it) else syncOffset(it, clip) }
        return LinkInfo(
            isVideo = isVideo,
            canDetach = isVideo && clip.still == null && assetHasAudio && !clip.audioDetached,
            detached = isVideo && clip.audioDetached,
            partnerId = partner?.id,
            relinkCandidateId = candidate?.id,
            offsetFrames = offset,
        )
    }

    // region commands

    /**
     * Detaches the sound of the video clip [videoClipId]: the clip becomes silent and a new audio clip [audioClipId] with the same
     * source range, speed and sound settings is placed at the same frames on [audioTrackId] (the lane is created at the bottom when
     * it does not exist yet). With [linked] the two share a link.
     */
    fun detachAudio(
        timeline: Timeline,
        videoClipId: String,
        audioClipId: String,
        audioTrackId: String,
        linked: Boolean,
    ): EditResult<Timeline> {
        val videoTrack = timeline.trackOfClip(videoClipId) ?: return failure(EditError.ClipNotFound(videoClipId))
        val video = checkNotNull(videoTrack.clip(videoClipId))
        if (videoTrack.type != TrackType.VIDEO || !video.hasMedia || video.assetId == null) return unavailable("Only a video clip has sound to detach")
        if (video.audioDetached) return unavailable("This clip's sound is already detached")
        if (timeline.trackOfClip(audioClipId) != null) return failure(EditError.DuplicateClipId(audioClipId))
        var current = timeline
        if (current.track(audioTrackId) == null) {
            current = when (val added = TimelineOps.addTrack(current, Track(audioTrackId, TrackType.AUDIO), current.tracks.size)) {
                is EditResult.Success -> added.value
                is EditResult.Failure -> return added
            }
        }
        val audioTrack = checkNotNull(current.track(audioTrackId))
        if (audioTrack.type != TrackType.AUDIO) return failure(EditError.TrackTypeMismatch(audioClipId, audioTrackId))
        val link = if (linked) linkIdFor(audioClipId) else null
        val audio = Clip(
            id = audioClipId,
            assetId = video.assetId,
            timelineStart = video.timelineStart,
            sourceIn = video.sourceIn,
            sourceOut = video.sourceOut,
            gainDb = video.gainDb,
            retimedFrames = video.retimedFrames,
            reverse = video.reverse,
            speedRamp = video.speedRamp,
            audio = video.audio,
            params = video.params.filter { it.paramId.startsWith(AUDIO_PARAM_PREFIX) },
            linkId = link,
        )
        audioTrack.clips.firstOrNull { it.overlaps(audio) }?.let { return failure(EditError.Overlap(it.id)) }
        // A video clip that was linked to another audio clip before leaves that link: one video clip has one partner.
        current = unlinked(current, video.linkId)
        current = current.withTrack(videoTrack.withClips(checkNotNull(current.track(videoTrack.id)).clips.map {
            if (it.id == videoClipId) it.copy(audioDetached = true, linkId = link) else it
        }))
        val lane = checkNotNull(current.track(audioTrackId))
        return success(current.withTrack(lane.withClips(lane.clips + audio)))
    }

    /** Ends the link of [clipId] and its partner; both clips stay where they are. */
    fun unlink(timeline: Timeline, clipId: String): EditResult<Timeline> {
        val track = timeline.trackOfClip(clipId) ?: return failure(EditError.ClipNotFound(clipId))
        val clip = checkNotNull(track.clip(clipId))
        val link = clip.linkId ?: return unavailable("This clip is not linked")
        return success(unlinked(timeline, link))
    }

    /**
     * Links the video clip and an audio clip of the same source. The video clip then plays no sound of its own (it would double the
     * audio clip's). With [realign] the audio clip is first moved to where it plays in sync with the video clip (see [syncOffset]).
     */
    fun relink(timeline: Timeline, videoClipId: String, audioClipId: String, realign: Boolean): EditResult<Timeline> {
        val videoTrack = timeline.trackOfClip(videoClipId) ?: return failure(EditError.ClipNotFound(videoClipId))
        val audioTrack = timeline.trackOfClip(audioClipId) ?: return failure(EditError.ClipNotFound(audioClipId))
        val video = checkNotNull(videoTrack.clip(videoClipId))
        var audio = checkNotNull(audioTrack.clip(audioClipId))
        if (videoTrack.type != TrackType.VIDEO || !video.hasMedia) return unavailable("Pick a video clip and an audio clip")
        if (audioTrack.type != TrackType.AUDIO) return unavailable("Pick a video clip and an audio clip")
        if (video.assetId != audio.assetId) return unavailable("These clips come from different media, so they cannot be linked")
        var current = unlinked(unlinked(timeline, video.linkId), audio.linkId)
        if (realign) {
            val offset = syncOffset(video, audio) ?: return unavailable("These clips play at different speeds, so they cannot be realigned automatically")
            if (offset != 0L) {
                val start = audio.timelineStart.value - offset
                if (start < 0) return failure(EditError.NegativeStart)
                val moved = audio.copy(timelineStart = FrameIndex(start))
                audioTrack.clips.firstOrNull { it.id != audio.id && it.overlaps(moved) }?.let { return failure(EditError.Overlap(it.id)) }
                audio = moved
            }
        }
        val link = linkIdFor(audioClipId)
        current = current.withTrack(checkNotNull(current.track(audioTrack.id)).let { track ->
            track.withClips(track.clips.map { if (it.id == audioClipId) audio.copy(linkId = link) else it })
        })
        current = current.withTrack(checkNotNull(current.track(videoTrack.id)).let { track ->
            track.withClips(track.clips.map { if (it.id == videoClipId) it.copy(audioDetached = true, linkId = link) else it })
        })
        return success(current.pruned())
    }

    /**
     * Gives the video clip its own sound back. The audio clip that was linked to it is removed (its sound is the clip's again);
     * an audio clip that is not linked stays, and then plays together with the restored sound.
     */
    fun restoreEmbeddedAudio(timeline: Timeline, videoClipId: String): EditResult<Timeline> {
        val track = timeline.trackOfClip(videoClipId) ?: return failure(EditError.ClipNotFound(videoClipId))
        val video = checkNotNull(track.clip(videoClipId))
        if (!video.audioDetached) return unavailable("This clip still has its own sound")
        var current = timeline
        val partner = partnerOf(timeline, video)
        if (partner != null) {
            val (partnerTrack, audio) = partner
            current = current.withTrack(partnerTrack.withClips(partnerTrack.clips - audio))
        }
        val updated = checkNotNull(current.track(track.id))
        return success(current.withTrack(updated.withClips(updated.clips.map { if (it.id == videoClipId) it.copy(audioDetached = false, linkId = null) else it })).pruned())
    }

    private fun unlinked(timeline: Timeline, linkId: String?): Timeline {
        if (linkId == null) return timeline
        return timeline.copy(tracks = timeline.tracks.map { track ->
            if (track.clips.none { it.linkId == linkId }) track else track.withClips(track.clips.map { if (it.linkId == linkId) it.copy(linkId = null) else it })
        })
    }

    private fun success(timeline: Timeline): EditResult<Timeline> = EditResult.Success(timeline)

    // endregion

    // region settling

    private class Placed(val trackId: String, val type: TrackType, val clip: Clip)

    private fun Timeline.placed(): List<Placed> = tracks.flatMap { track -> track.clips.map { Placed(track.id, track.type, it) } }

    private fun hasLinks(timeline: Timeline) = timeline.tracks.any { track -> track.clips.any { it.linkId != null } }

    /** What the clip does on the timeline; two clips with the same shape are the same edit-wise. */
    private data class Shape(
        val start: Long,
        val end: Long,
        val sourceIn: Long,
        val sourceOut: Long,
        val retimedFrames: Long?,
        val reverse: Boolean,
        val speedRamp: List<SpeedKey>,
    )

    private fun Clip.shape() = Shape(timelineStart.value, timelineEnd.value, sourceIn.value, sourceOut.value, retimedFrames, reverse, speedRamp)

    /**
     * Applies to the linked partners of the clips an edit changed the same change, so the pair stays together. [before] is the
     * timeline the edit started from and [after] what it produced. Returns [after] untouched when no link is involved or both
     * partners were edited alike; fails (nothing applies) when a partner cannot follow because it would overlap another clip.
     */
    fun settle(before: Timeline, after: Timeline): EditResult<Timeline> {
        if (!hasLinks(before) && !hasLinks(after)) return EditResult.Success(after)
        val beforeIds = before.placed().map { it.clip.id }.toHashSet()
        var current = after
        val handled = HashSet<String>()

        // Phase A: a link shared by more than two clips means an edit copied a linked clip (a split, a paste, a duplicate).
        val groups = after.placed().filter { it.clip.linkId != null }.groupBy { checkNotNull(it.clip.linkId) }
        for ((link, members) in groups) {
            if (members.size <= 2) continue
            handled += link
            val fresh = members.filter { it.clip.id !in beforeIds }
            val old = members - fresh.toSet()
            current = when {
                fresh.size == 2 && old.size == 2 && fresh.map { it.type }.toSet() == setOf(TrackType.VIDEO, TrackType.AUDIO) ->
                    relinked(current, fresh.map { it.clip.id }, freshLinkId(current, "$link~${fresh.first().clip.id}"))
                fresh.size == 1 && old.size == 2 -> when (val result = splitPartner(before, current, link, fresh.single(), old)) {
                    is EditResult.Success -> result.value
                    is EditResult.Failure -> return result
                }
                else -> relinked(current, fresh.map { it.clip.id }, null)
            }
        }

        // Phase B: clips that were a pair before the edit; the one that changed leads, the unchanged partner follows.
        val replaced = LinkedHashMap<String, Clip?>() // null removes the clip
        val beforeGroups = before.placed().filter { it.clip.linkId != null }.groupBy { checkNotNull(it.clip.linkId) }
        val afterById = current.placed().associateBy { it.clip.id }
        for ((link, members) in beforeGroups) {
            if (link in handled || members.size != 2) continue
            val (a, b) = members
            val a1 = afterById[a.clip.id]
            val b1 = afterById[b.clip.id]
            val aChanged = a1 == null || a1.clip.shape() != a.clip.shape()
            val bChanged = b1 == null || b1.clip.shape() != b.clip.shape()
            if (aChanged == bChanged) continue
            val (lead, leadAfter, follow, followAfter) = if (aChanged) Quad(a, a1, b, b1) else Quad(b, b1, a, a1)
            if (followAfter == null) continue
            if (leadAfter == null) {
                // The video clip is gone: its audio goes too. The audio clip is gone: the video clip stays, silent and on its own.
                if (lead.type == TrackType.VIDEO) replaced[follow.clip.id] = null else replaced[follow.clip.id] = followAfter.clip.copy(linkId = null)
            } else {
                replaced[follow.clip.id] = follower(lead.clip, leadAfter.clip, followAfter.clip)
            }
        }
        if (replaced.isNotEmpty()) current = when (val applied = apply(current, replaced)) {
            is EditResult.Success -> applied.value
            is EditResult.Failure -> return applied
        }
        return EditResult.Success(withoutBrokenLinks(current).let { if (it === after) after else it.pruned() })
    }

    private data class Quad(val lead: Placed, val leadAfter: Placed?, val follow: Placed, val followAfter: Placed?)

    /** [follower] after the same change that turned [lead] into [leadAfter]. */
    private fun follower(lead: Clip, leadAfter: Clip, follower: Clip): Clip {
        val ds = leadAfter.timelineStart.value - lead.timelineStart.value
        val de = leadAfter.timelineEnd.value - lead.timelineEnd.value
        val retimeChanged = leadAfter.retimedFrames != lead.retimedFrames || leadAfter.reverse != lead.reverse || leadAfter.speedRamp != lead.speedRamp
        if (retimeChanged) {
            return follower.copy(
                timelineStart = FrameIndex(follower.timelineStart.value + ds),
                sourceIn = FrameIndex(follower.sourceIn.value + (leadAfter.sourceIn.value - lead.sourceIn.value)),
                sourceOut = FrameIndex(follower.sourceOut.value + (leadAfter.sourceOut.value - lead.sourceOut.value)),
                retimedFrames = leadAfter.retimedFrames,
                reverse = leadAfter.reverse,
                speedRamp = leadAfter.speedRamp,
            )
        }
        val moved = follower.copy(timelineStart = FrameIndex(follower.timelineStart.value + ds))
        if (ds == de) return moved
        // The length changed: the leader was cropped (a trim, or an extension) and possibly moved as well (the base track slides a trimmed
        // clip back to its neighbour). The follower gets the same cut of the same media, then the same move.
        val head = headCrop(lead, leadAfter) ?: return moved
        val newLength = leadAfter.durationFrames
        if (newLength <= 0) return follower
        return follower.cropped(head, head + newLength).copy(timelineStart = moved.timelineStart)
    }

    /** How many frames [leadAfter] lost (or, negative, gained) at its head compared with [lead]; null when no crop explains the change. */
    private fun headCrop(lead: Clip, leadAfter: Clip): Long? {
        if (!lead.isRetimed) return leadAfter.sourceIn.value - lead.sourceIn.value
        val length = leadAfter.durationFrames
        for (c in -lead.durationFrames..lead.durationFrames) {
            val candidate = lead.cropped(c, c + length)
            if (candidate.sourceIn == leadAfter.sourceIn && candidate.sourceOut == leadAfter.sourceOut) return c
        }
        return null
    }

    /** Applies [changes] (null removes) and checks that no lane now has overlapping clips or a clip before frame 0. */
    private fun apply(timeline: Timeline, changes: Map<String, Clip?>): EditResult<Timeline> {
        var current = timeline
        for (track in timeline.tracks) {
            if (track.clips.none { it.id in changes }) continue
            val clips = track.clips.mapNotNull { if (it.id in changes) changes[it.id] else it }.sortedBy { it.timelineStart }
            clips.firstOrNull { it.timelineStart < FrameIndex.ZERO }?.let { return failure(EditError.NegativeStart) }
            for ((previous, next) in clips.zipWithNext()) {
                if (next.timelineStart < previous.timelineEnd) {
                    // The clip that moved is the one that cannot take its place; name the other so the message points at what is in the way.
                    val moved = if (previous.id in changes) next else previous
                    return failure(EditError.Overlap(moved.id))
                }
            }
            current = current.withTrack(track.copy(clips = clips))
        }
        return EditResult.Success(current)
    }

    private fun relinked(timeline: Timeline, clipIds: List<String>, link: String?): Timeline =
        timeline.copy(tracks = timeline.tracks.map { track ->
            if (track.clips.none { it.id in clipIds }) track else track.withClips(track.clips.map { if (it.id in clipIds) it.copy(linkId = link) else it })
        })

    private fun freshLinkId(timeline: Timeline, base: String): String {
        val used = timeline.placed().mapNotNull { it.clip.linkId }.toHashSet()
        var candidate = base
        var n = 1
        while (candidate in used) candidate = "$base.${n++}"
        return candidate
    }

    /**
     * A linked clip was cut in two: [fresh] is the new right half (it carries the copied link) and [old] are the original pair.
     * The partner is cut at the same frame (allowing for any sync offset) and the two right halves get a link of their own.
     * When [fresh] is only a copy, not the continuation of its original, it just loses the link.
     */
    private fun splitPartner(before: Timeline, timeline: Timeline, link: String, fresh: Placed, old: List<Placed>): EditResult<Timeline> {
        val n = fresh.clip
        val origin = old.firstOrNull { it.trackId == fresh.trackId }
        val partner = old.firstOrNull { it.trackId != fresh.trackId }
        val dropped = relinked(timeline, listOf(n.id), null)
        if (origin == null || partner == null) return EditResult.Success(dropped)
        val continues = origin.clip.timelineEnd == n.timelineStart && (
            n.sourceIn == origin.clip.sourceOut || (origin.clip.reverse && n.sourceOut == origin.clip.sourceIn) || !n.hasMedia
            )
        val originBefore = before.trackOfClip(origin.clip.id)?.clip(origin.clip.id)
        val partnerBefore = before.trackOfClip(partner.clip.id)?.clip(partner.clip.id)
        if (!continues || originBefore == null || partnerBefore == null || partnerBefore.shape() != partner.clip.shape()) return EditResult.Success(dropped)
        val at = n.timelineStart.value + (partnerBefore.timelineStart.value - originBefore.timelineStart.value)
        if (at <= partnerBefore.timelineStart.value || at >= partnerBefore.timelineEnd.value) return EditResult.Success(dropped)
        val suffix = n.id.substringAfterLast('~')
        var newId = "${partner.clip.id}~$suffix"
        var k = 1
        while (timeline.trackOfClip(newId) != null) newId = "${partner.clip.id}~$suffix.${k++}"
        val split = when (val result = TimelineOps.split(timeline, partner.trackId, FrameIndex(at), newId)) {
            is EditResult.Success -> result.value
            is EditResult.Failure -> return result
        }
        return EditResult.Success(relinked(split, listOf(n.id, newId), freshLinkId(split, "$link~$suffix")))
    }

    /** Forgets the link of clips whose partner is gone or that no longer pair a video clip with an audio clip of one source. */
    fun withoutBrokenLinks(timeline: Timeline): Timeline {
        if (violations(timeline).isEmpty()) return timeline
        val groups = timeline.placed().filter { it.clip.linkId != null }.groupBy { checkNotNull(it.clip.linkId) }
        val broken = groups.filter { (_, members) ->
            members.size != 2 ||
                members.map { it.type }.toSet() != setOf(TrackType.VIDEO, TrackType.AUDIO) ||
                members[0].clip.assetId != members[1].clip.assetId
        }.keys
        return timeline.copy(tracks = timeline.tracks.map { track ->
            if (track.clips.none { it.linkId in broken }) track else track.withClips(track.clips.map { if (it.linkId in broken) it.copy(linkId = null) else it })
        })
    }

    // endregion
}
