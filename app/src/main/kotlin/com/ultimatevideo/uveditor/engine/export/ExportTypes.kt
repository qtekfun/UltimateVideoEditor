package com.ultimatevideo.uveditor.engine.export

import java.nio.ByteBuffer
import com.ultimatevideo.uveditor.domain.ClipFx

/** Mirrors `uv::core::Status` in the native engine. Keep values in sync. */
enum class ExportErrorCode(val value: Int) {
    INVALID_ARGUMENT(1),
    BAD_SNAPSHOT(2),
    IO_ERROR(3),
    UNSUPPORTED_FORMAT(4),
    CODEC_ERROR(5),
    GL_ERROR(6),
    NOT_INITIALIZED(7),
    CANCELLED(8),
    UNKNOWN(-1),
    ;

    companion object {
        fun fromValue(value: Int): ExportErrorCode = entries.firstOrNull { it.value == value } ?: UNKNOWN
    }
}

/** Thrown by native code for synchronous failures; constructed from JNI as `(int, String)`. */
class ExportException(val code: ExportErrorCode, message: String) : Exception(message) {
    @Suppress("unused") // called from JNI
    constructor(code: Int, message: String) : this(ExportErrorCode.fromValue(code), message)
}

enum class ExportCodec(val value: Int, val label: String) {
    H264(0, "H.264"),
    HEVC(1, "HEVC"),
}

data class ExportSettings(
    val width: Int,
    val height: Int,
    val fpsNum: Int,
    val fpsDen: Int,
    val codec: ExportCodec,
    val videoBitrate: Int,
    val audioBitrate: Int = DEFAULT_AUDIO_BITRATE,
    /** HDR (HLG, BT.2020) HEVC Main10 output; false is SDR Rec.709. Requires [ExportCodec.HEVC]. */
    val hdr: Boolean = false,
) {
    init {
        require(!hdr || codec == ExportCodec.HEVC) { "HDR export needs HEVC" }
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0) { "size must be positive and even: ${width}x$height" }
        require(fpsNum > 0 && fpsDen > 0) { "fps must be positive: $fpsNum/$fpsDen" }
        require(videoBitrate > 0 && audioBitrate > 0) { "bitrates must be positive" }
    }

    companion object {
        const val DEFAULT_AUDIO_BITRATE = 192_000
    }
}

/**
 * One video clip to render. Frames are project frames; a lower [layer] is drawn on top. The
 * transform mirrors `ClipTransform`: canvas pixels (+x right, +y down), clockwise degrees, opacity 0..1.
 */
data class VideoClipSpec(
    val startFrame: Long,
    val durationFrames: Long,
    val sourceInFrame: Long,
    val assetKey: Long,
    val layer: Int,
    val colorMode: Int,
    val positionX: Double = 0.0,
    val positionY: Double = 0.0,
    val scaleX: Double = 1.0,
    val scaleY: Double = 1.0,
    val rotationDegrees: Double = 0.0,
    val opacity: Double = 1.0,
    /** Frames over which the clip fades in (the incoming side of a transition); 0 = no fade. */
    val crossfadeInFrames: Long = 0,
    /** Decoder slot within [layer]: 1 for the incoming clip of a transition between cuts of one media. */
    val lane: Int = 0,
    /** Non-zero for a title: the key of its image in [ExportRequest.titles] (then [assetKey] is unused). */
    val titleKey: Int = 0,
    /** Animated pose; when non-empty it replaces the fixed transform above (opacity still gets the crossfade). */
    val keyframes: List<ExportKeyframe> = emptyList(),
    /** Project frame that [ExportKeyframe.frame] counts from: the clip's own first frame. */
    val keyframeOriginFrame: Long = 0,
    /**
     * For a retimed clip (speed, ramp, reverse, freeze), the source frame of each of its project frames
     * (index = frame - [startFrame], size [durationFrames]); null for a plain 1x clip, which shows
     * `sourceInFrame + (frame - startFrame)`. Built from `domain/Retime.kt`, as the preview does.
     */
    val sourceFrames: LongArray? = null,
    /** The clip plays backwards: the decoder keeps decoded frames behind the one being drawn. */
    val reverse: Boolean = false,
    /** Effects, blend mode and mask, applied exactly as in the preview. */
    val fx: ClipFx = ClipFx.NONE,
)

/**
 * A pose at [frame] frames after [VideoClipSpec.keyframeOriginFrame]. [interpolation] is the code of
 * `domain.Interpolation` (0 linear, 1 ease, 2 hold); the native evaluator mirrors `Keyframes.evaluate`.
 */
data class ExportKeyframe(
    val frame: Long,
    val positionX: Double,
    val positionY: Double,
    val scaleX: Double,
    val scaleY: Double,
    val rotationDegrees: Double,
    val opacity: Double,
    val interpolation: Int,
)

/** A rasterised title for the export: premultiplied RGBA in canvas pixels, see `TitleBitmap`. */
class ExportTitle(val key: Int, val width: Int, val height: Int, val pixels: ByteBuffer)

/** A 3D LUT for the export: [size]^3 RGB floats (red varying fastest) in a direct, native-order buffer. */
class ExportLut(val key: Int, val size: Int, val rgb: ByteBuffer)

/**
 * Everything the native exporter needs. Descriptors are raw (already detached) and are owned by
 * the exporter from the moment [ExportRunner.start] is called, even if it throws.
 *
 * [totalFrames] counts output frames at [ExportSettings.fpsNum]/[ExportSettings.fpsDen];
 * [projectFpsNum]/[projectFpsDen] is the rate the clips' frames are expressed in, and
 * [canvasWidth] x [canvasHeight] the project resolution that clip positions are measured in.
 */
class ExportRequest(
    val settings: ExportSettings,
    val projectFpsNum: Int,
    val projectFpsDen: Int,
    val canvasWidth: Int,
    val canvasHeight: Int,
    val totalFrames: Long,
    val assetFds: Map<Long, Int>,
    val videoClips: List<VideoClipSpec>,
    val audioSnapshot: ByteBuffer?,
    val outputFd: Int,
    val titles: List<ExportTitle> = emptyList(),
    /** The LUTs the clips' LUT effects refer to; a LUT that is absent leaves its clip ungraded. */
    val luts: List<ExportLut> = emptyList(),
)

interface ExportListener {
    /** Progress in 0..1000. Called on a native thread. */
    fun onProgress(permille: Int)

    /** Called once, on a native thread: [error] is null on success. */
    fun onFinished(error: ExportException?)
}

/** A running export. [close] blocks until the native thread has stopped: call it off the main thread. */
interface ExportHandle : AutoCloseable {
    fun cancel()
}

interface ExportRunner {
    /** @throws ExportException if the export could not even start. */
    fun start(request: ExportRequest, listener: ExportListener): ExportHandle
}
