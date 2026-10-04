package com.ultimatevideo.uveditor.engine.preview

import com.ultimatevideo.uveditor.domain.ClipFx

/** Mirrors `uv::decode::Status` in the native engine. Keep values in sync. */
enum class PreviewErrorCode(val value: Int) {
    InvalidArgument(1),
    IoError(2),
    UnsupportedFormat(3),
    CodecError(4),
    EglError(5),
    GlError(6),
    OutOfMemory(7),
    NotFound(8),
    InvalidState(9),
    Unknown(-1),
    ;

    companion object {
        fun fromValue(value: Int): PreviewErrorCode = entries.firstOrNull { it.value == value } ?: Unknown
    }
}

/**
 * Failure reported by the native preview pipeline. Thrown synchronously from engine calls and
 * delivered to the error callback for failures on decode/render threads.
 * The JNI layer constructs this class by its (Int, String) constructor.
 */
class PreviewException(val code: Int, message: String) : Exception(message) {
    val errorCode: PreviewErrorCode get() = PreviewErrorCode.fromValue(code)
}

/**
 * Mirrors `uv::render::ColorMode`: how one source is converted into the colour space the target is
 * rendered in. The native side derives it from the decoded stream and the [OutputSpace]; only the
 * source class matters when an explicit value is given ([sourceClass]).
 */
enum class ColorMode(val value: Int) {
    /** SDR Rec.709 source into an SDR target: sampled as is. */
    Sdr709(0),

    /** HLG / Rec.2020 source tone-mapped and gamut-converted to SDR Rec.709. */
    Hlg2020ToSdr709(1),

    /** SDR Rec.709 source placed in an HLG target, SDR white at 203 nit. */
    Sdr709ToHlg2020(2),

    /** HLG / Rec.2020 source into an HLG target: sampled as is. */
    Hlg2020(3),

    /** PQ / Rec.2020 source tone-mapped to SDR Rec.709. */
    Pq2020ToSdr709(4),

    /** PQ / Rec.2020 source re-encoded as HLG for an HLG target. */
    Pq2020ToHlg2020(5),
    ;

    /** What the source is, independent of the target. */
    val sourceClass: SourceColor
        get() = when (this) {
            Sdr709, Sdr709ToHlg2020 -> SourceColor.SDR
            Hlg2020ToSdr709, Hlg2020 -> SourceColor.HLG
            Pq2020ToSdr709, Pq2020ToHlg2020 -> SourceColor.PQ
        }
}

/** Transfer characteristic of a source, as far as colour conversion cares. */
enum class SourceColor(val mode: ColorMode) {
    SDR(ColorMode.Sdr709),
    HLG(ColorMode.Hlg2020ToSdr709),
    PQ(ColorMode.Pq2020ToSdr709),
}

/** Mirrors `uv::render::OutputSpace`: the colour space a render target holds. */
enum class OutputSpace(val value: Int) {
    SDR_709(0),
    HLG_2020(1),
    ;

    companion object {
        fun fromValue(value: Int): OutputSpace = if (value == HLG_2020.value) HLG_2020 else SDR_709
    }
}

/**
 * Where a layer sits on the project canvas; mirrors `uv::render::LayerTransform`. The frame is first
 * fitted into the canvas, then scaled and rotated (clockwise) about its centre and moved by
 * ([positionX], [positionY]) canvas pixels (+x right, +y down). [opacity] is 0..1.
 */
data class LayerPlacement(
    val positionX: Float = 0f,
    val positionY: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val rotationDegrees: Float = 0f,
    val opacity: Float = 1f,
) {
    companion object {
        val IDENTITY = LayerPlacement()
    }
}

/**
 * One layer of the preview scene: [frame] of an open asset, in the frame rate the asset was opened
 * with, or - when [titleKey] is not 0 - a title uploaded with `PreviewEngine.uploadTitle` (then
 * [assetId] and [frame] are unused). [endFrame] only matters to playback: the layer holds its last
 * frame instead of advancing to it (exclusive, the clip's out point). Null means the end of the asset.
 */
data class PreviewLayer(
    val assetId: Int,
    val frame: Long,
    val placement: LayerPlacement = LayerPlacement.IDENTITY,
    val endFrame: Long? = null,
    val titleKey: Int = 0,
    /** The layer plays backwards: its decoder keeps the frames behind [frame] decoded rather than those ahead. */
    val reverse: Boolean = false,
    /** Effects, blend mode and mask; neutral draws the layer as is. */
    val fx: ClipFx = ClipFx.NONE,
    /** Reads this layer's source as SDR (0), HLG (1) or PQ (2) whatever the file says; -1 uses the file's own. */
    val sourceOverride: Int = -1,
    /**
     * Smooth slow motion: how far the moment shown is from [frame] towards its neighbour in the direction of
     * play, 0..1 (permille / 1000); the engine blends [frame] with the frame after it, or before it when
     * [mixTowardsPrevious] (a reversed clip). 0 shows [frame] alone.
     */
    val mix: Float = 0f,
    val mixTowardsPrevious: Boolean = false,
)

data class AssetInfo(
    val width: Int,
    val height: Int,
    val durationFrames: Long,
    val fpsNum: Long,
    val fpsDen: Long,
    /** `MediaFormat.COLOR_TRANSFER_*` from the container, 0 when unknown. */
    val colorTransfer: Int,
    /** Clockwise rotation (0/90/180/270) the container asks players to apply. */
    val rotationDegrees: Int = 0,
) {
    val isHlg: Boolean get() = colorTransfer == COLOR_TRANSFER_HLG

    private companion object {
        const val COLOR_TRANSFER_HLG = 7
    }
}

data class PreviewStats(
    val cacheUsedBytes: Long,
    val cacheBudgetBytes: Long,
    val cacheEntries: Long,
    val framesDrawn: Long,
    val stalls: Long,
    val framesDecoded: Long,
)
