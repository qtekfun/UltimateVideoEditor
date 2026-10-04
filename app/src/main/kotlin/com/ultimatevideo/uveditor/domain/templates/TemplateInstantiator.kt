package com.ultimatevideo.uveditor.domain.templates

import com.ultimatevideo.uveditor.data.model.MediaAssetDto
import com.ultimatevideo.uveditor.domain.ClipDeletion
import com.ultimatevideo.uveditor.domain.EditError
import com.ultimatevideo.uveditor.domain.EditResult
import com.ultimatevideo.uveditor.domain.FrameIndex
import com.ultimatevideo.uveditor.domain.FrameRate
import com.ultimatevideo.uveditor.domain.MagneticBase
import com.ultimatevideo.uveditor.domain.Reframe
import com.ultimatevideo.uveditor.domain.StillKind
import com.ultimatevideo.uveditor.domain.Timeline
import com.ultimatevideo.uveditor.domain.TimelineOps
import com.ultimatevideo.uveditor.domain.TrimEdge
import com.ultimatevideo.uveditor.domain.mulDiv

/** The media a user puts in one placeholder. */
data class SlotFill(
    val asset: MediaAssetDto,
    /** First source frame to use, in project frames (the user's own trim); ignored for photos. */
    val sourceInFrame: Long = 0,
    /** The picture's size in pixels when known, so it can be centre-cropped to the canvas; 0 when unknown. */
    val width: Int = 0,
    val height: Int = 0,
)

/** A timeline made from a template and the media it uses; [warnings] are things the user should know (a short file, a dropped transition). */
data class TemplateResult(val timeline: Timeline, val assets: List<MediaAssetDto>, val warnings: List<String>)

/**
 * Turns a [ProjectTemplate] and the user's media into a timeline (SPECS.md 9.19). Each filled placeholder keeps its
 * place, effects, titles and transitions around it; the clip is fitted to the slot:
 * - a video longer than the slot is trimmed to the slot's length from the chosen start; a shorter one shortens the
 *   slot, and the clips after it (and the titles over them) move up like a base-track trim;
 * - a photo takes the slot's length as it is;
 * - a picture whose shape differs from the canvas is centre-cropped (scaled to cover), through [Reframe];
 * - an empty optional placeholder is removed and the gap closes; an empty required one is an error;
 * - the template's transitions are put back where they fit, shortened to the footage around each cut, and dropped
 *   with a warning where not even the shortest fits.
 */
object TemplateInstantiator {

    fun instantiate(template: ProjectTemplate, fills: Map<String, SlotFill>): EditResult<TemplateResult> {
        template.problem()?.let { return fail(it) }
        val fps = FrameRate(template.fpsNum, template.fpsDen)
        val transitions = template.timeline.transitions
        var current = template.timeline.copy(transitions = emptyList())
        val warnings = ArrayList<String>()
        val used = LinkedHashMap<String, MediaAssetDto>()

        val slots = current.tracks.flatMap { track -> track.clips.mapNotNull { clip -> Placeholder.idOfSlotAsset(clip.assetId)?.let { Triple(it, clip.id, clip.timelineStart) } } }
            .sortedBy { it.third }
        for ((placeholderId, clipId, _) in slots) {
            val placeholder = checkNotNull(template.placeholder(placeholderId))
            val fill = fills[placeholderId]
            if (fill == null) {
                if (!placeholder.optional) return fail("choose media for \"${placeholder.name}\"")
                current = when (val removed = ClipDeletion.delete(current, clipId)) {
                    is EditResult.Success -> removed.value
                    is EditResult.Failure -> return removed
                }
                continue
            }
            kindProblem(placeholder, fill.asset)?.let { return fail(it) }
            val track = checkNotNull(current.trackOfClip(clipId))
            val clip = checkNotNull(track.clip(clipId))
            val slotFrames = clip.durationFrames
            val isPhoto = fill.asset.isImage
            val pose = if (placeholder.kind != PlaceholderKind.AUDIO && fill.width > 0 && fill.height > 0) {
                Reframe.poseFor(0.5, 0.5, fill.width.toDouble() / fill.height, template.width, template.height, 1.0, clip.transform)
            } else {
                clip.transform
            }
            var length = slotFrames
            val fitted = if (isPhoto) {
                clip.copy(assetId = fill.asset.id, still = StillKind.PHOTO, sourceIn = FrameIndex.ZERO, sourceOut = FrameIndex(slotFrames), transform = pose)
            } else {
                val total = framesOf(fill.asset, fps)
                // A transition into this clip needs footage before its in point: start a few frames in when the file can afford it.
                val lead = transitions.firstOrNull { it.toClipId == clipId }?.preFrames ?: 0L
                val start = if (fill.sourceInFrame < lead && total - lead >= slotFrames) lead else fill.sourceInFrame
                val available = total - start
                if (available < 1) return fail("the file for \"${placeholder.name}\" has no footage after the chosen start")
                length = minOf(slotFrames, available)
                if (length < placeholder.minFrames) warnings += "\"${placeholder.name}\" is shorter than its slot (${seconds(length, fps)} of ${seconds(slotFrames, fps)})"
                // The clip first keeps the slot's length (the footage it names is only trimmed below), so that
                // shortening it is an ordinary base-track trim that moves what follows.
                clip.copy(
                    assetId = fill.asset.id,
                    still = null,
                    sourceIn = FrameIndex(start),
                    sourceOut = FrameIndex(start + slotFrames),
                    retimedFrames = null,
                    reverse = false,
                    speedRamp = emptyList(),
                    transform = pose,
                )
            }
            used[fill.asset.id] = fill.asset
            current = current.withTrack(track.withClips(track.clips.map { if (it.id == clipId) fitted else it }))
            if (length < slotFrames) {
                // A shorter file shortens the slot; the base track closes the gap and the overlays follow.
                current = when (val trimmed = MagneticBase.trim(current, clipId, TrimEdge.END, fitted.timelineStart + length)) {
                    is EditResult.Success -> trimmed.value
                    is EditResult.Failure -> return trimmed
                }
            }
        }

        for (transition in transitions) {
            val from = current.trackOfClip(transition.fromClipId)?.clip(transition.fromClipId)
            val to = current.trackOfClip(transition.toClipId)?.clip(transition.toClipId)
            if (from == null || to == null) continue // one side was an empty optional slot
            val sourceLength = from.assetId?.let { used[it] }?.takeUnless { it.isImage }?.let { framesOf(it, fps) }
            val room = TimelineOps.maxTransitionFrames(current, from.id, to.id, sourceLength)
            val length = minOf(transition.durationFrames, room)
            if (length < com.ultimatevideo.uveditor.domain.Transition.MIN_DURATION_FRAMES) {
                warnings += "a ${transition.type.label.lowercase()} transition was dropped: not enough footage around the cut"
                continue
            }
            current = when (val added = TimelineOps.addTransition(current, transition.copy(durationFrames = length), sourceLength)) {
                is EditResult.Success -> added.value
                is EditResult.Failure -> {
                    warnings += "a ${transition.type.label.lowercase()} transition was dropped: not enough footage around the cut"
                    current
                }
            }
        }
        return EditResult.Success(TemplateResult(current, used.values.toList(), warnings))
    }

    private fun kindProblem(placeholder: Placeholder, asset: MediaAssetDto): String? {
        val ok = when (placeholder.kind) {
            PlaceholderKind.VIDEO -> asset.hasVideo && !asset.isImage
            PlaceholderKind.VIDEO_OR_PHOTO -> asset.hasVideo || asset.isImage
            PlaceholderKind.PHOTO -> asset.isImage
            PlaceholderKind.AUDIO -> asset.hasAudio && !asset.isImage
        }
        return if (ok) null else "\"${placeholder.name}\" needs ${placeholder.kind.label.lowercase()}"
    }

    /** The asset's length in project frames. */
    internal fun framesOf(asset: MediaAssetDto, fps: FrameRate): Long =
        if (asset.nativeFpsNum == fps.num && asset.nativeFpsDen == fps.den) {
            asset.durationFrames
        } else {
            mulDiv(asset.durationFrames, fps.num.toLong() * asset.nativeFpsDen, fps.den.toLong() * asset.nativeFpsNum, roundHalfUp = false)
        }

    private fun seconds(frames: Long, fps: FrameRate): String = "%.1f s".format(frames * fps.den.toDouble() / fps.num)

    private fun fail(reason: String): EditResult<TemplateResult> = EditResult.Failure(EditError.InvalidClip(reason))
}
