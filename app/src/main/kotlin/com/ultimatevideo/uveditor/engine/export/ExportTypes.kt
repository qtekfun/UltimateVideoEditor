package com.ultimatevideo.uveditor.engine.export

import java.nio.ByteBuffer

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
) {
    init {
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
)

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
