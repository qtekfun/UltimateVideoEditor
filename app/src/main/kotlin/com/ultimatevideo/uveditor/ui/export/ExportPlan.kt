package com.ultimatevideo.uveditor.ui.export

import com.ultimatevideo.uveditor.data.model.MediaAssetDto
import com.ultimatevideo.uveditor.domain.FrameRate
import com.ultimatevideo.uveditor.domain.Keyframes
import com.ultimatevideo.uveditor.domain.RenderClip
import com.ultimatevideo.uveditor.domain.RenderKind
import com.ultimatevideo.uveditor.domain.SourceColorSpace
import com.ultimatevideo.uveditor.domain.StillKind
import com.ultimatevideo.uveditor.engine.still.StillRef
import com.ultimatevideo.uveditor.domain.Timeline
import com.ultimatevideo.uveditor.domain.TitleContent
import com.ultimatevideo.uveditor.domain.captions.CaptionAnimator
import com.ultimatevideo.uveditor.domain.captions.LookSegment
import com.ultimatevideo.uveditor.domain.renderClips
import com.ultimatevideo.uveditor.engine.audio.AudioSnapshot
import com.ultimatevideo.uveditor.engine.export.ExportKeyframe
import com.ultimatevideo.uveditor.engine.export.VideoClipSpec
import com.ultimatevideo.uveditor.ui.editor.KeyRegistry
import com.ultimatevideo.uveditor.ui.editor.audioSnapshotOf
import java.math.BigInteger

/** What to render, derived from the committed timeline. Pure data: no Android types. */
internal data class ExportPlan(
    /** Length of the movie in project frames. */
    val projectFrames: Long,
    val videoClips: List<VideoClipSpec>,
    /** Null when nothing audible is on the timeline: the file then has no audio track. */
    val audio: AudioSnapshot?,
    /** Assets the render needs, by native key. */
    val assetKeys: Map<String, Long>,
    /** Distinct titles to rasterise, by the key [VideoClipSpec.titleKey] refers to. */
    val titles: Map<Int, TitleContent> = emptyMap(),
    /** Distinct photos and stickers to draw, by the key [VideoClipSpec.titleKey] refers to (keys never collide with [titles]'). */
    val stills: Map<Int, StillRef> = emptyMap(),
)

private fun RenderClip.toSpec(
    assetKey: Long,
    colorMode: Int,
    titleKey: Int = 0,
    start: Long = startFrame,
    duration: Long = durationFrames,
    crossfadeIn: Long = crossfadeInFrames,
) = VideoClipSpec(
    startFrame = start,
    durationFrames = duration,
    sourceInFrame = sourceInFrame,
    assetKey = assetKey,
    layer = layer,
    colorMode = colorMode,
    positionX = transform.positionX,
    positionY = transform.positionY,
    scaleX = transform.scaleX,
    scaleY = transform.scaleY,
    rotationDegrees = transform.rotationDegrees,
    opacity = transform.opacity,
    crossfadeInFrames = crossfadeIn,
    lane = lane,
    titleKey = titleKey,
    // The native evaluator only knows linear, ease and hold: a Bezier segment goes over as one linear key per frame.
    keyframes = Keyframes.bakedForNative(keyframes, transform).map {
        ExportKeyframe(
            frame = it.frame,
            positionX = it.transform.positionX,
            positionY = it.transform.positionY,
            scaleX = it.transform.scaleX,
            scaleY = it.transform.scaleY,
            rotationDegrees = it.transform.rotationDegrees,
            opacity = it.transform.opacity,
            interpolation = it.interpolation.code,
        )
    },
    keyframeOriginFrame = keyframeOriginFrame,
    sourceFrames = retime?.let { LongArray(durationFrames.toInt()) { i -> sourceFrameAt(startFrame + i) } },
    reverse = isReverse,
    fx = fx,
    // Keyframed effect values go over as the effects of every project frame of the spec.
    fxFrames = if (hasAnimatedFx) List(duration.toInt()) { i -> fxAt(start + i) } else null,
)

private val SDR = SourceColorSpace.SDR.nativeModeValue

/** One stretch of a title clip that is drawn from a single picture, in project frames. */
internal data class TitlePart(val start: Long, val duration: Long, val crossfadeIn: Long, val content: TitleContent)

/**
 * The stretches of title clip [this] that share one picture: one part for a plain title, one per
 * change of look for an animated caption. Only the first part fades in with the clip's transition,
 * and it is stretched to cover the whole fade so the fade is not restarted halfway.
 */
internal fun RenderClip.titleParts(content: TitleContent): List<TitlePart> {
    val origin = keyframeOriginFrame
    var segments = CaptionAnimator.segments(content, startFrame - origin, endFrame - origin)
    if (crossfadeInFrames > 0 && segments.size > 1) {
        val rampEnd = startFrame - origin + crossfadeInFrames
        val inRamp = segments.takeWhile { it.startFrame < rampEnd }
        segments = listOf(LookSegment(inRamp.first().startFrame, inRamp.last().endFrame, inRamp.first().look)) + segments.drop(inRamp.size)
    }
    return segments.mapIndexed { i, segment ->
        TitlePart(
            start = origin + segment.startFrame,
            duration = segment.endFrame - segment.startFrame,
            crossfadeIn = if (i == 0) crossfadeInFrames else 0L,
            content = content.copy(look = segment.look),
        )
    }
}

/**
 * Plans an export of [timeline]. Returns null when it is empty. The first visual track (video or
 * title) is the topmost layer, matching the preview; clip transforms and opacity are carried over,
 * and transitions are folded in by [renderClips] (extended clips with a fade), the same list the
 * preview and the audio mixer use. Source ranges are in project frames, as everywhere in the editor.
 */
internal fun buildExportPlan(timeline: Timeline, assets: List<MediaAssetDto>, fps: FrameRate): ExportPlan? {
    val assetsById = assets.associateBy { it.id }
    val assetKeys = KeyRegistry()
    val clipKeys = KeyRegistry()
    val used = LinkedHashMap<String, Long>()

    val videoClips = ArrayList<VideoClipSpec>()
    val titles = LinkedHashMap<TitleContent, Int>()
    val stills = LinkedHashMap<StillRef, Int>()
    // Titles and stills share one key space: both reach the engine as uploaded pictures.
    var nextPictureKey = 1
    for (clip in timeline.renderClips()) {
        when (clip.kind) {
            RenderKind.AUDIO -> continue
            RenderKind.VIDEO -> if (clip.still != null) {
                val id = clip.assetId ?: continue
                val ref = StillRef(clip.still, if (clip.still == StillKind.PHOTO) assetsById[id]?.uri ?: continue else id)
                val pictureKey = stills.getOrPut(ref) { nextPictureKey++ }
                videoClips += clip.toSpec(assetKey = 0L, colorMode = SDR, titleKey = pictureKey)
            } else {
                val asset = clip.assetId?.let(assetsById::get) ?: continue
                if (!asset.hasVideo) continue
                val key = used.getOrPut(asset.id) { assetKeys.keyFor(asset.id) }
                videoClips += clip.toSpec(
                    assetKey = key,
                    // What the source is; the engine converts it to the colour space the export renders in
                    // (HLG sources are tone-mapped for an SDR export, kept for an HLG one).
                    colorMode = (clip.colorOverride ?: SourceColorSpace.fromId(asset.colorSpace)).nativeModeValue,
                )
            }
            RenderKind.TITLE -> {
                val content = clip.title ?: continue
                // An animated caption is one spec per stretch over which its picture is the same.
                for (part in clip.titleParts(content)) {
                    val titleKey = titles.getOrPut(part.content.withoutTiming()) { nextPictureKey++ }
                    videoClips += clip.toSpec(
                        assetKey = 0L,
                        colorMode = SDR,
                        titleKey = titleKey,
                        start = part.start,
                        duration = part.duration,
                        crossfadeIn = part.crossfadeIn,
                    )
                }
            }
        }
    }

    val snapshot = audioSnapshotOf(timeline, assets, fps, clipKeys::keyFor) { id -> used.getOrPut(id) { assetKeys.keyFor(id) } }
    val audio = snapshot.takeIf { it.clips.isNotEmpty() }

    val end = timeline.tracks.flatMap { it.clips }.maxOfOrNull { it.timelineEnd.value } ?: 0L
    if (end <= 0L || (videoClips.isEmpty() && audio == null)) return null
    return ExportPlan(end, videoClips, audio, used, titles.entries.associate { it.value to it.key }, stills.entries.associate { it.value to it.key })
}

/** Output frames needed to cover [projectFrames] project frames, rounded up so no audio is cut off. */
internal fun outputFrameCount(projectFrames: Long, project: FrameRate, output: FrameRate): Long {
    val numerator = BigInteger.valueOf(projectFrames) * BigInteger.valueOf(output.num.toLong()) * BigInteger.valueOf(project.den.toLong())
    val denominator = BigInteger.valueOf(output.den.toLong()) * BigInteger.valueOf(project.num.toLong())
    return (numerator + denominator - BigInteger.ONE).divide(denominator).toLong()
}
