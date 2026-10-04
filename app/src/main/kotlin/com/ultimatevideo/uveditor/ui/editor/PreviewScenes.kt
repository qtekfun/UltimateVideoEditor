package com.ultimatevideo.uveditor.ui.editor

import com.ultimatevideo.uveditor.data.model.MediaAssetDto
import com.ultimatevideo.uveditor.domain.FrameIndex
import com.ultimatevideo.uveditor.domain.FrameRate
import com.ultimatevideo.uveditor.domain.RenderKind
import com.ultimatevideo.uveditor.domain.SourceColorSpace
import com.ultimatevideo.uveditor.proxy.ResolvedSource
import com.ultimatevideo.uveditor.domain.StillKind
import com.ultimatevideo.uveditor.engine.still.StillRef
import com.ultimatevideo.uveditor.domain.Timeline
import com.ultimatevideo.uveditor.domain.TitleLayers
import com.ultimatevideo.uveditor.domain.captions.CaptionAnimator
import com.ultimatevideo.uveditor.domain.renderClips
import com.ultimatevideo.uveditor.domain.visualClipsAt

/**
 * Preview decoders are keyed by `assetKey + lane * LANE_STRIDE`: the two sides of a transition
 * between cuts of one file need two decoders on the same media. Asset keys stay far below this.
 */
internal const val LANE_STRIDE = 1 shl 20

/**
 * The layers the preview composites at [playhead], bottom first, from the same render plan the
 * exporter and the mixer use: a transition shows as two layers of one track, the outgoing clip
 * opaque and the incoming one fading in over it; titles are layers without media. Clips whose
 * media is unknown or has no picture are left out. Empty in a gap on every track.
 */
internal fun previewRequestsAt(
    timeline: Timeline,
    assets: List<MediaAssetDto>,
    fps: FrameRate,
    playhead: FrameIndex,
    assetKeyOf: (String) -> Int,
): List<PreviewRequest> = previewRequestsWithSources(timeline, assets, fps, playhead, { ResolvedSource(it.uri) }, assetKeyOf)

/**
 * [previewRequestsAt] with the file to open for each asset chosen by [sourceOf]: its original unless a ready
 * proxy stands in for it while editing. The proxy keeps the source's frame rate and length, so every frame
 * number in the requests is the same for both.
 */
internal fun previewRequestsWithSources(
    timeline: Timeline,
    assets: List<MediaAssetDto>,
    fps: FrameRate,
    playhead: FrameIndex,
    sourceOf: (MediaAssetDto) -> ResolvedSource,
    assetKeyOf: (String) -> Int,
): List<PreviewRequest> {
    val assetsById = assets.associateBy { it.id }
    return visualClipsAt(timeline.renderClips(), playhead.value).mapNotNull { clip ->
        val transform = clip.appearanceAt(playhead.value)
        when (clip.kind) {
            RenderKind.TITLE -> clip.title?.let { content ->
                PreviewRequest(
                    assetKey = 0,
                    uri = "",
                    sourceFrame = 0,
                    fpsNum = fps.num,
                    fpsDen = fps.den,
                    transform = transform,
                    // An animated caption shows the look of this frame; the preview keys its picture by it.
                    // Photo layers of a multilayer title are pointed at their files, which also keys the cached picture.
                    title = TitleLayers.resolved(CaptionAnimator.contentAt(content, playhead.value - clip.keyframeOriginFrame)) { assetsById[it]?.uri },
                    fx = clip.fxAt(playhead.value),
                )
            }
            RenderKind.VIDEO -> if (clip.still != null) {
                clip.assetId?.let { id ->
                    // A photo is drawn from its file, a sticker from its built-in art; neither has a decoder.
                    val ref = StillRef(clip.still, if (clip.still == StillKind.PHOTO) assetsById[id]?.uri ?: return@let null else id)
                    PreviewRequest(
                        assetKey = 0,
                        uri = "",
                        sourceFrame = 0,
                        fpsNum = fps.num,
                        fpsDen = fps.den,
                        transform = transform,
                        fx = clip.fxAt(playhead.value),
                        still = ref,
                    )
                }
            } else {
                val asset = clip.assetId?.let(assetsById::get)
                if (asset == null || !asset.hasVideo) {
                    null
                } else {
                    val source = sourceOf(asset)
                    PreviewRequest(
                        assetKey = assetKeyOf(asset.id) + clip.lane * LANE_STRIDE,
                        uri = source.uri,
                        proxyAssetId = source.proxyAssetId,
                        sourceFrame = clip.sourceFrameAt(playhead.value),
                        fpsNum = fps.num,
                        fpsDen = fps.den,
                        transform = transform,
                        // Playback holds the clip's last frame instead of running into trimmed-away media. A
                        // retimed clip is re-anchored every tick at the frame its mapping gives, so it has no end.
                        endFrame = if (clip.retime == null) clip.sourceInFrame + clip.durationFrames else null,
                        reverse = clip.isReverse,
                        fx = clip.fxAt(playhead.value),
                        // A proxy is an SDR Rec.709 stand-in whatever the original was: read it as that.
                        sourceOverride = if (source.isProxy) SourceColorSpace.SDR.transferIndex else clip.colorOverride?.transferIndex ?: -1,
                    )
                }
            }
            RenderKind.AUDIO -> null
        }
    }
}
