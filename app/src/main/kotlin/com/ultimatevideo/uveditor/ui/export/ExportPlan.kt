package com.ultimatevideo.uveditor.ui.export

import com.ultimatevideo.uveditor.data.animationTiming
import com.ultimatevideo.uveditor.data.model.MediaAssetDto
import com.ultimatevideo.uveditor.domain.AnimationSegment
import com.ultimatevideo.uveditor.domain.AnimationTiming
import com.ultimatevideo.uveditor.domain.ClipTransform
import com.ultimatevideo.uveditor.domain.FrameRate
import com.ultimatevideo.uveditor.domain.Interpolation
import com.ultimatevideo.uveditor.domain.Keyframes
import com.ultimatevideo.uveditor.domain.RenderClip
import com.ultimatevideo.uveditor.domain.RenderKind
import com.ultimatevideo.uveditor.domain.SourceColorSpace
import com.ultimatevideo.uveditor.domain.SourceMix
import com.ultimatevideo.uveditor.domain.StillKind
import com.ultimatevideo.uveditor.engine.still.StillRef
import com.ultimatevideo.uveditor.domain.Timeline
import com.ultimatevideo.uveditor.domain.TitleContent
import com.ultimatevideo.uveditor.domain.TitleLayers
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
    canvasWidth: Int,
    canvasHeight: Int,
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
    // Only a crossfade or light leak fades the incoming picture by opacity; the moving looks put their opacity in the keys.
    crossfadeInFrames = if (transitionIn?.type?.fadesVideo != false) crossfadeIn else 0L,
    lane = lane,
    titleKey = titleKey,
    keyframes = exportKeyframes(canvasWidth, canvasHeight),
    keyframeOriginFrame = keyframeOriginFrame,
    sourceFrames = retime?.let { LongArray(durationFrames.toInt()) { i -> packSource(sourceMixAt(startFrame + i)) } },
    reverse = isReverse,
    fx = fx,
    // Keyframed effect values and the effects or mask a transition adds go over as the effects of every project frame of the spec.
    fxFrames = if (hasAnimatedFx || shapesTransitionFx) List(duration.toInt()) { i -> fxAt(start + i, canvasWidth, canvasHeight) } else null,
)

private fun poseKey(frame: Long, t: ClipTransform, interpolation: Int) = ExportKeyframe(
    frame = frame,
    positionX = t.positionX,
    positionY = t.positionY,
    scaleX = t.scaleX,
    scaleY = t.scaleY,
    rotationDegrees = t.rotationDegrees,
    opacity = t.opacity,
    interpolation = interpolation,
)

/**
 * The pose keys the native evaluator reads. It only knows linear, ease and hold, so a Bezier segment goes over as
 * one linear key per frame. Where a moving transition (slide, push, zoom, spin, glitch, whip pan) shapes the pose,
 * the composite pose of every frame of the transition replaces the clip's own keys, with a key just outside each
 * end holding the clip's plain pose so the picture settles exactly back afterwards. The values come from
 * [RenderClip.appearanceAt], the function the preview calls, so both draw the same picture.
 */
private fun RenderClip.exportKeyframes(canvasWidth: Int, canvasHeight: Int): List<ExportKeyframe> {
    val original = Keyframes.bakedForNative(keyframes, transform).map { poseKey(it.frame, it.transform, it.interpolation.code) }
    val ranges = transitionPoseRanges().filter { !it.isEmpty() }
    if (ranges.isEmpty()) return original
    fun inRegion(frame: Long) = ranges.any { frame in it }
    val kept = original.filter { !inRegion(it.frame + keyframeOriginFrame) }
    val edges = ranges.flatMap { listOf(it.first - 1, it.last + 1) }
        .filter { it >= startFrame && it < endFrame && !inRegion(it) }
        .distinct()
        .filter { f -> kept.none { it.frame + keyframeOriginFrame == f } }
        .map { poseKey(it - keyframeOriginFrame, transformAt(it), Interpolation.LINEAR.code) }
    val dense = ranges.flatMap { range -> range.filter { it >= startFrame && it < endFrame } }
        .map { poseKey(it - keyframeOriginFrame, appearanceAt(it, canvasWidth, canvasHeight), Interpolation.LINEAR.code) }
    return (kept + edges + dense).sortedBy { it.frame }
}

private val SDR = SourceColorSpace.SDR.nativeModeValue

/** Bit where the smooth-slow-motion mix (permille) sits in a packed source-table entry; see `sourceFrameFor` in encode/export_math.h. */
internal const val SOURCE_MIX_SHIFT = 44

/**
 * One entry of a clip's source table: the source frame, with the mix towards the neighbouring frame (permille) in the
 * bits above [SOURCE_MIX_SHIFT] when the frame is interpolated. Frames of interpolated entries are never negative.
 */
internal fun packSource(shown: SourceMix): Long =
    if (shown.blended && shown.frame >= 0) shown.frame or (shown.mixPermille.toLong() shl SOURCE_MIX_SHIFT) else shown.frame

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

/** One stretch of an animated picture clip that shows a single animation frame, in project frames. */
internal data class AnimationPart(val start: Long, val duration: Long, val crossfadeIn: Long, val index: Int)

/**
 * The stretches of animated picture clip [this] that show one animation frame, from the clip's first drawn frame
 * (a transition can start it earlier than its own start) to its end. Only the first part fades in with the clip's
 * transition and it is stretched over the whole fade, as for animated captions, so the fade is not restarted.
 */
internal fun RenderClip.animationParts(timing: AnimationTiming, fps: FrameRate): List<AnimationPart> {
    val origin = keyframeOriginFrame
    var segments = timing.segments(startFrame - origin, endFrame - origin, fps)
    if (crossfadeInFrames > 0 && segments.size > 1) {
        val rampEnd = startFrame - origin + crossfadeInFrames
        val inRamp = segments.takeWhile { it.startFrame < rampEnd }
        segments = listOf(AnimationSegment(inRamp.first().startFrame, inRamp.last().endFrame, inRamp.first().index)) + segments.drop(inRamp.size)
    }
    return segments.mapIndexed { i, segment ->
        AnimationPart(
            start = origin + segment.startFrame,
            duration = segment.endFrame - segment.startFrame,
            crossfadeIn = if (i == 0) crossfadeInFrames else 0L,
            index = segment.index,
        )
    }
}

/**
 * Plans an export of [timeline]. Returns null when it is empty. The first visual track (video or
 * title) is the topmost layer, matching the preview; clip transforms and opacity are carried over,
 * and transitions are folded in by [renderClips] (extended clips with a fade), the same list the
 * preview and the audio mixer use. Source ranges are in project frames, as everywhere in the editor.
 */
internal fun buildExportPlan(
    timeline: Timeline,
    assets: List<MediaAssetDto>,
    fps: FrameRate,
    canvasWidth: Int = 1920,
    canvasHeight: Int = 1080,
): ExportPlan? {
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
                val timing = if (clip.still == StillKind.PHOTO) assetsById[id]?.animationTiming() else null
                if (timing == null) {
                    val pictureKey = stills.getOrPut(ref) { nextPictureKey++ }
                    videoClips += clip.toSpec(canvasWidth, canvasHeight, assetKey = 0L, colorMode = SDR, titleKey = pictureKey)
                } else {
                    // An animated GIF or WebP is one spec per stretch over which its picture is the same, like an
                    // animated caption; the preview picks the same animation frame at every project frame.
                    for (part in clip.animationParts(timing, fps)) {
                        val pictureKey = stills.getOrPut(ref.copy(frame = part.index)) { nextPictureKey++ }
                        videoClips += clip.toSpec(
                            canvasWidth,
                            canvasHeight,
                            assetKey = 0L,
                            colorMode = SDR,
                            titleKey = pictureKey,
                            start = part.start,
                            duration = part.duration,
                            crossfadeIn = part.crossfadeIn,
                        )
                    }
                }
            } else {
                val asset = clip.assetId?.let(assetsById::get) ?: continue
                if (!asset.hasVideo) continue
                val key = used.getOrPut(asset.id) { assetKeys.keyFor(asset.id) }
                videoClips += clip.toSpec(
                    canvasWidth,
                    canvasHeight,
                    assetKey = key,
                    // What the source is; the engine converts it to the colour space the export renders in
                    // (HLG sources are tone-mapped for an SDR export, kept for an HLG one).
                    colorMode = (clip.colorOverride ?: SourceColorSpace.fromId(asset.colorSpace)).nativeModeValue,
                )
            }
            RenderKind.TITLE -> {
                // Photo layers of a multilayer title are pointed at their files, as in the preview.
                val content = TitleLayers.resolved(clip.title ?: continue) { assetsById[it]?.uri }
                // An animated caption is one spec per stretch over which its picture is the same.
                for (part in clip.titleParts(content)) {
                    val titleKey = titles.getOrPut(part.content.withoutTiming()) { nextPictureKey++ }
                    videoClips += clip.toSpec(
                        canvasWidth,
                        canvasHeight,
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
