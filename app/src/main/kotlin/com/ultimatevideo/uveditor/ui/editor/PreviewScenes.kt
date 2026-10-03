package com.ultimatevideo.uveditor.ui.editor

import com.ultimatevideo.uveditor.data.model.MediaAssetDto
import com.ultimatevideo.uveditor.domain.FrameIndex
import com.ultimatevideo.uveditor.domain.FrameRate
import com.ultimatevideo.uveditor.domain.RenderKind
import com.ultimatevideo.uveditor.domain.Timeline
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
): List<PreviewRequest> {
    val assetsById = assets.associateBy { it.id }
    return visualClipsAt(timeline.renderClips(), playhead.value).mapNotNull { clip ->
        val transform = clip.transform.copy(opacity = clip.opacityAt(playhead.value))
        when (clip.kind) {
            RenderKind.TITLE -> clip.title?.let { content ->
                PreviewRequest(
                    assetKey = 0,
                    uri = "",
                    sourceFrame = 0,
                    fpsNum = fps.num,
                    fpsDen = fps.den,
                    transform = transform,
                    title = content,
                )
            }
            RenderKind.VIDEO -> {
                val asset = clip.assetId?.let(assetsById::get)
                if (asset == null || !asset.hasVideo) {
                    null
                } else {
                    PreviewRequest(
                        assetKey = assetKeyOf(asset.id) + clip.lane * LANE_STRIDE,
                        uri = asset.uri,
                        sourceFrame = clip.sourceFrameAt(playhead.value),
                        fpsNum = fps.num,
                        fpsDen = fps.den,
                        transform = transform,
                    )
                }
            }
            RenderKind.AUDIO -> null
        }
    }
}
