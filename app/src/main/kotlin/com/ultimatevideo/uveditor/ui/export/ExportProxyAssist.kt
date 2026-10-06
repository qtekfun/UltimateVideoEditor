package com.ultimatevideo.uveditor.ui.export

import com.ultimatevideo.uveditor.domain.RenderClip
import com.ultimatevideo.uveditor.domain.SourceColorSpace
import com.ultimatevideo.uveditor.engine.export.ExportKeyframe
import kotlin.math.max
import kotlin.math.min

/**
 * A ready stand-in file for one video asset, as the export sees it: a small, constant-frame-rate, SDR Rec.709 copy with the
 * source's own frame numbering ([width] x [height] is its size as displayed). It is plain data so the choice below stays
 * pure; the editor fills it from the store of ready proxies when the export dialog opens.
 */
data class ExportProxy(val uri: String, val width: Int, val height: Int)

/**
 * The "Faster export" option: which layers may be decoded from their proxy instead of the 4K original, because the proxy
 * has at least as many pixels as the layer covers in the output. Pure, no Android types.
 *
 * A proxy is used for a clip only when every one of these holds (anything else decodes the original, exactly as before):
 *  - the clip is decoded video, not a title, photo, sticker or animation;
 *  - a ready proxy of its asset exists in [proxies];
 *  - the clip has no effect and no stabilisation (those read the full-resolution source) and no smooth slow motion
 *    (it blends neighbouring frames of the original);
 *  - its colour reading is the asset's own (an override would make the proxy's SDR picture wrong) and, when the movie is
 *    exported as HDR, the asset is not HDR (a proxy holds the tone-mapped SDR picture, so it would lose the highlights);
 *  - at its largest scale over the whole clip (keyframes and transition moves included) the layer covers no more output
 *    pixels than the proxy has, on both axes ([proxyCoversLayer]); a proxy is never enlarged.
 */
internal data class ExportProxyAssist(
    val proxies: Map<String, ExportProxy>,
    val canvasWidth: Int,
    val canvasHeight: Int,
    val outputWidth: Int,
    val outputHeight: Int,
    val hdrOutput: Boolean,
)

/** Slack for double rounding at the exact-equal boundary (a layer scaled to 1/3 of a canvas three times the proxy's size). */
private const val EQUAL_SIZE_SLACK = 1e-6

/**
 * True when a proxy of [proxyWidth] x [proxyHeight] (displayed) has at least as many pixels as a layer with scale up to
 * ([maxScaleX], [maxScaleY]) covers in the output. The picture is fitted "contain" into the canvas first (see
 * `layout_math.h`), so a layer's size in output pixels is `proxy * fit * scale * outputWidth / canvasWidth` on each axis.
 * Rotation does not change how many source pixels map to one output pixel, so it plays no part.
 */
internal fun proxyCoversLayer(
    canvasWidth: Int,
    canvasHeight: Int,
    outputWidth: Int,
    outputHeight: Int,
    proxyWidth: Int,
    proxyHeight: Int,
    maxScaleX: Double,
    maxScaleY: Double,
): Boolean {
    if (canvasWidth <= 0 || canvasHeight <= 0 || outputWidth <= 0 || outputHeight <= 0 || proxyWidth <= 0 || proxyHeight <= 0) return false
    if (!(maxScaleX.isFinite() && maxScaleY.isFinite()) || maxScaleX <= 0.0 || maxScaleY <= 0.0) return false
    val fit = min(canvasWidth.toDouble() / proxyWidth, canvasHeight.toDouble() / proxyHeight)
    val coveredX = proxyWidth * fit * maxScaleX * outputWidth / canvasWidth
    val coveredY = proxyHeight * fit * maxScaleY * outputHeight / canvasHeight
    return coveredX <= proxyWidth * (1.0 + EQUAL_SIZE_SLACK) && coveredY <= proxyHeight * (1.0 + EQUAL_SIZE_SLACK)
}

/** The largest scale on each axis the clip's pose reaches: its keyframes (which already hold transition moves) or its fixed scale. */
internal fun largestScale(fixedScaleX: Double, fixedScaleY: Double, keys: List<ExportKeyframe>): Pair<Double, Double> {
    if (keys.isEmpty()) return fixedScaleX to fixedScaleY
    var x = 0.0
    var y = 0.0
    for (key in keys) {
        x = max(x, key.scaleX)
        y = max(y, key.scaleY)
    }
    return x to y
}

/** The proxy [clip] may use, or null to decode the original. [keys] are the pose keys the export gives the clip. */
internal fun ExportProxyAssist.proxyFor(
    clip: RenderClip,
    assetColorSpace: SourceColorSpace,
    keys: List<ExportKeyframe>,
): ExportProxy? {
    val assetId = clip.assetId ?: return null
    val proxy = proxies[assetId] ?: return null
    if (clip.still != null || clip.smooth) return null
    if (clip.fx.effects.isNotEmpty() || clip.fx.stabKey != null) return null
    if (clip.colorOverride != null && clip.colorOverride != assetColorSpace) return null
    if (hdrOutput && assetColorSpace != SourceColorSpace.SDR) return null
    val (scaleX, scaleY) = largestScale(clip.transform.scaleX, clip.transform.scaleY, keys)
    return proxy.takeIf { proxyCoversLayer(canvasWidth, canvasHeight, outputWidth, outputHeight, it.width, it.height, scaleX, scaleY) }
}
