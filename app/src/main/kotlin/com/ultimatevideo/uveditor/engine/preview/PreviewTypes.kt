package com.ultimatevideo.uveditor.engine.preview

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

/** Mirrors `uv::render::ColorMode`. */
enum class ColorMode(val value: Int) {
    /** Source already is SDR Rec.709; sampled as is. */
    Sdr709(0),

    /** HLG / Rec.2020 source tone-mapped and gamut-converted to SDR Rec.709. */
    Hlg2020ToSdr709(1),
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

/** One layer of the preview scene: [frame] of an open asset, in the frame rate the asset was opened with. */
data class PreviewLayer(
    val assetId: Int,
    val frame: Long,
    val placement: LayerPlacement = LayerPlacement.IDENTITY,
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
