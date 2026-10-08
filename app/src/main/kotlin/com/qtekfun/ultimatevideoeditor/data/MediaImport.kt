package com.qtekfun.ultimatevideoeditor.data

import android.content.Context
import android.content.Intent
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto
import com.qtekfun.ultimatevideoeditor.domain.AnimationTiming
import com.qtekfun.ultimatevideoeditor.engine.still.GifDelayScan
import com.qtekfun.ultimatevideoeditor.engine.still.WebpAnimationScan
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.roundToInt

/** What the editor needs to know about a media file before it can be placed on the timeline. */
data class ProbedMedia(
    val durationMicros: Long,
    val fpsNum: Int,
    val fpsDen: Int,
    val colorSpace: String,
    val hasVideo: Boolean,
    val hasAudio: Boolean,
    /** A still picture: no frames, no audio, [durationMicros] is 0 and the editor picks a default length. */
    val isImage: Boolean = false,
    /** The file name as the document provider reports it, when it does. */
    val displayName: String? = null,
    /** An animated GIF or WebP: the normalised delay of each frame in milliseconds; null for other pictures. */
    val animationDelaysMs: List<Int>? = null,
    /** Total passes of that animation the file asks for; 0 loops forever. */
    val animationPlays: Int = 0,
    /** What the export dialog needs to pick its defaults; null when the file does not say (see [VideoFacts]). */
    val videoWidth: Int? = null,
    val videoHeight: Int? = null,
    val videoBitrate: Long? = null,
    val videoCodec: String? = null,
    val tenBit: Boolean? = null,
)

/** Why a media file cannot be used; decides what the editor tells the user and offers. */
enum class MediaProblem {
    /** The file is gone, moved, or its provider is not answering. */
    UNREADABLE,

    /** The file may still exist, but this app no longer holds permission to read it. */
    PERMISSION_LOST,

    /** The file opens but is not something the editor can decode. */
    UNSUPPORTED,
}

/** The media file could not be opened or understood. Always carries a user-presentable message. */
class MediaImportException(
    message: String,
    cause: Throwable? = null,
    val problem: MediaProblem = MediaProblem.UNREADABLE,
) : Exception(message, cause)

interface MediaImporter {
    /** Keeps read access to [uri] across restarts, then probes it. @throws MediaImportException */
    suspend fun import(uri: String): ProbedMedia

    /**
     * Probes an existing library file without requiring it to be re-grantable: a file that is readable
     * now but whose persisted permission cannot be taken (a `file://` URI, the permission limit) is
     * still usable, so it is not reported as missing. Re-takes the permission when it can.
     * @throws MediaImportException if the file cannot be read.
     */
    suspend fun verify(uri: String): ProbedMedia = import(uri)
}

/** Maps a measured (floating point) frame rate onto the exact rational the project model stores. */
object FpsRational {
    private val ntsc = listOf(
        23.976 to (24000 to 1001),
        29.97 to (30000 to 1001),
        47.952 to (48000 to 1001),
        59.94 to (60000 to 1001),
        119.88 to (120000 to 1001),
    )

    fun fromFloat(fps: Double): Pair<Int, Int> {
        if (!fps.isFinite() || fps < 1.0) return DEFAULT_FPS to 1
        ntsc.firstOrNull { abs(it.first - fps) < NTSC_TOLERANCE }?.let { return it.second }
        return fps.roundToInt() to 1
    }

    const val DEFAULT_FPS = 30
    private const val NTSC_TOLERANCE = 0.02
}

/** Colour space names used in `project.json` (SPECS.md section 4). */
internal object ColorSpaceNames {
    const val SDR = "Rec709-SDR"
    const val HLG = "Rec2020-HLG"
    const val PQ = "Rec2020-PQ"

    fun fromTransfer(transfer: Int?): String = detect(transfer, hasHdrStaticInfo = false)

    /**
     * The colour space a video track really is. The transfer characteristic decides (HLG and PQ are
     * explicit); when a file carries no transfer at all but does carry HDR10 static metadata
     * (mastering display / content light level), it is PQ. Everything else, including BT.2020 with an
     * SDR transfer, is treated as SDR.
     */
    fun detect(transfer: Int?, hasHdrStaticInfo: Boolean): String = when {
        transfer == MediaFormat.COLOR_TRANSFER_HLG -> HLG
        transfer == MediaFormat.COLOR_TRANSFER_ST2084 -> PQ
        transfer == null && hasHdrStaticInfo -> PQ
        else -> SDR
    }
}

class AndroidMediaImporter(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : MediaImporter {

    override suspend fun import(uri: String): ProbedMedia = withContext(ioDispatcher) {
        val parsed = Uri.parse(uri)
        try {
            context.contentResolver.takePersistableUriPermission(parsed, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            throw MediaImportException(
                "Cannot keep access to the selected file. Android limits how many files an app can keep; " +
                    "remove unused projects or pick the file again",
                e,
                MediaProblem.PERMISSION_LOST,
            )
        }
        warnIfNearPermissionLimit()
        probe(parsed)
    }

    override suspend fun verify(uri: String): ProbedMedia = withContext(ioDispatcher) {
        val parsed = Uri.parse(uri)
        val media = probe(parsed)
        // Readable now: make sure it stays readable after a restart. Not being able to is worth a log, not an error.
        try {
            context.contentResolver.takePersistableUriPermission(parsed, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            warnIfNearPermissionLimit()
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot persist read access to $uri: ${e.message}")
        }
        media
    }

    private fun warnIfNearPermissionLimit() {
        val held = context.contentResolver.persistedUriPermissions.size
        if (PermissionTrim.nearLimit(held, AndroidPersistedUris.LIMIT)) {
            Log.w(TAG, "$held of ${AndroidPersistedUris.LIMIT} persisted URI permissions are in use")
        }
    }

    internal fun probe(uri: Uri): ProbedMedia {
        if (isImageMime(context.contentResolver.getType(uri))) return probeImage(uri)
        val extractor = MediaExtractor()
        try {
            try {
                extractor.setDataSource(context, uri, null)
            } catch (e: IOException) {
                throw MediaImportException("Cannot open the selected file", e, MediaProblem.UNREADABLE)
            } catch (e: IllegalArgumentException) {
                throw MediaImportException("Unsupported file", e, MediaProblem.UNSUPPORTED)
            } catch (e: SecurityException) {
                throw MediaImportException("No permission to read the selected file", e, MediaProblem.PERMISSION_LOST)
            }
            return probeTracks(extractor, uri).copy(displayName = displayNameOf(uri))
        } finally {
            extractor.release()
        }
    }

    /** Reads only the header: enough to know the platform can decode it. EXIF orientation is applied when it is drawn. */
    private fun probeImage(uri: Uri): ProbedMedia {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            context.contentResolver.openInputStream(uri).use { stream ->
                if (stream == null) throw MediaImportException("Cannot open the selected picture")
                BitmapFactory.decodeStream(stream, null, options)
            }
        } catch (e: IOException) {
            throw MediaImportException("Cannot open the selected picture", e, MediaProblem.UNREADABLE)
        } catch (e: SecurityException) {
            throw MediaImportException("No permission to read the selected picture", e, MediaProblem.PERMISSION_LOST)
        }
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            throw MediaImportException("This picture format is not supported", problem = MediaProblem.UNSUPPORTED)
        }
        val animation = animationTiming(uri)
        return ProbedMedia(
            durationMicros = 0,
            fpsNum = FpsRational.DEFAULT_FPS,
            fpsDen = 1,
            colorSpace = ColorSpaceNames.SDR,
            hasVideo = false,
            hasAudio = false,
            isImage = true,
            displayName = displayNameOf(uri),
            animationDelaysMs = animation?.delaysMs,
            animationPlays = animation?.plays ?: 0,
        )
    }

    /** Frame delays and passes of an animated GIF or WebP, or null for any other picture (read from the headers, no pixels). */
    private fun animationTiming(uri: Uri): AnimationTiming? {
        val type = context.contentResolver.getType(uri) ?: return null
        if (type != "image/gif" && type != "image/webp") return null
        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use { it.readAtMost(MAX_ANIMATION_BYTES) } ?: return null
        } catch (e: IOException) {
            return null
        } catch (e: SecurityException) {
            return null
        }
        return if (type == "image/gif") {
            AnimationTiming.ofRaw(GifDelayScan.delaysMs(bytes), GifDelayScan.plays(bytes))
        } else {
            AnimationTiming.ofRaw(WebpAnimationScan.delaysMs(bytes), WebpAnimationScan.plays(bytes))
        }
    }

    private fun displayNameOf(uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() } else null
        }
    } catch (e: SecurityException) {
        // The name is only a label; the caller falls back to the URI's last segment.
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun probeTracks(extractor: MediaExtractor, uri: Uri): ProbedMedia {
        var hasVideo = false
        var hasAudio = false
        var durationMicros = 0L
        var fps: Double? = null
        var transfer: Int? = null
        var hdrStaticInfo = false
        var video: VideoFacts? = null
        var audioBitrate = 0L
        for (index in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(index)
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            if (format.containsKey(MediaFormat.KEY_DURATION)) {
                durationMicros = maxOf(durationMicros, format.getLong(MediaFormat.KEY_DURATION))
            }
            when {
                mime.startsWith("video/") && !hasVideo -> {
                    hasVideo = true
                    fps = format.getNumber(MediaFormat.KEY_FRAME_RATE)?.toDouble()
                    if (format.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
                        transfer = format.getInteger(MediaFormat.KEY_COLOR_TRANSFER)
                    }
                    hdrStaticInfo = format.containsKey(MediaFormat.KEY_HDR_STATIC_INFO)
                    video = VideoFacts.of(format, mime)
                }
                mime.startsWith("audio/") -> {
                    hasAudio = true
                    if (format.containsKey(MediaFormat.KEY_BIT_RATE)) audioBitrate += format.getInteger(MediaFormat.KEY_BIT_RATE)
                }
            }
        }
        if (!hasAudio) {
            // The platform's extractor does not list uncompressed audio in a QuickTime file (iPhone 'lpcm'); the engine decodes it itself.
            pcmSoundTrack(uri)?.let { hasAudio = true; durationMicros = maxOf(durationMicros, it.durationMicros) }
        }
        if (!hasVideo && !hasAudio) throw MediaImportException("The file has no audio or video track", problem = MediaProblem.UNSUPPORTED)
        if (durationMicros <= 0) throw MediaImportException("The file has no readable duration", problem = MediaProblem.UNSUPPORTED)
        val (num, den) = FpsRational.fromFloat(fps ?: captureFrameRate(uri) ?: FpsRational.DEFAULT_FPS.toDouble())
        val bitrate = video?.let { VideoFacts.bitrate(it.streamBitrate, audioBitrate, fileSize(uri), durationMicros) }
        return ProbedMedia(
            durationMicros, num, den, ColorSpaceNames.detect(transfer, hdrStaticInfo), hasVideo, hasAudio,
            videoWidth = video?.width, videoHeight = video?.height, videoBitrate = bitrate,
            videoCodec = video?.codec, tenBit = video?.tenBit,
        )
    }

    /** The file's size in bytes, or null when the provider does not say; only used to estimate a bit rate. */
    private fun fileSize(uri: Uri): Long? = try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize.takeIf { size -> size > 0 } }
    } catch (e: IOException) {
        null
    } catch (e: SecurityException) {
        null
    }

    private fun pcmSoundTrack(uri: Uri): PcmSoundTrack? = try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            FileInputStream(pfd.fileDescriptor).use { stream ->
                val channel = stream.channel
                MovAudioScan.find(object : ByteSource {
                    override val size: Long = channel.size()
                    override fun read(offset: Long, length: Int): ByteArray? {
                        val buffer = ByteBuffer.allocate(length)
                        while (buffer.hasRemaining()) {
                            if (channel.read(buffer, offset + buffer.position()) <= 0) return null
                        }
                        return buffer.array()
                    }
                })
            }
        }
    } catch (e: IOException) {
        // Only a hint that a soundtrack exists: when the file cannot be read this way, the file is reported as it was.
        Log.w(TAG, "Cannot scan $uri for uncompressed audio: ${e.message}")
        null
    } catch (e: SecurityException) {
        Log.w(TAG, "Cannot scan $uri for uncompressed audio: ${e.message}")
        null
    }

    private fun captureFrameRate(uri: Uri): Double? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toDoubleOrNull()
        } catch (e: RuntimeException) {
            // The retriever is only a fallback for a missing frame rate; the caller defaults to 30.
            null
        } finally {
            retriever.release()
        }
    }
}

/** The video track's own facts, read from its format (pure given the numbers; the Android reads are in [of]). */
internal data class VideoFacts(
    val width: Int,
    val height: Int,
    val codec: String,
    val tenBit: Boolean,
    /** The format's KEY_BIT_RATE when the container states one, else null. */
    val streamBitrate: Long?,
) {
    companion object {
        fun of(format: MediaFormat, mime: String): VideoFacts? {
            if (!format.containsKey(MediaFormat.KEY_WIDTH) || !format.containsKey(MediaFormat.KEY_HEIGHT)) return null
            var w = format.getInteger(MediaFormat.KEY_WIDTH)
            var h = format.getInteger(MediaFormat.KEY_HEIGHT)
            val rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0
            if (rotation == 90 || rotation == 270) w = h.also { h = w }
            val profile = if (format.containsKey(MediaFormat.KEY_PROFILE)) format.getInteger(MediaFormat.KEY_PROFILE) else -1
            val tenBit = profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ||
                profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 ||
                profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus ||
                profile == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10 ||
                profile == MediaCodecInfo.CodecProfileLevel.VP9Profile2
            val stream = if (format.containsKey(MediaFormat.KEY_BIT_RATE)) format.getInteger(MediaFormat.KEY_BIT_RATE).toLong() else null
            return VideoFacts(w, h, codecName(mime), tenBit, stream?.takeIf { it > 0 })
        }

        fun codecName(mime: String): String = when (mime) {
            MediaFormat.MIMETYPE_VIDEO_AVC -> "avc"
            MediaFormat.MIMETYPE_VIDEO_HEVC -> "hevc"
            MediaFormat.MIMETYPE_VIDEO_AV1 -> "av1"
            MediaFormat.MIMETYPE_VIDEO_VP9 -> "vp9"
            else -> "other"
        }

        /**
         * The video bit rate in bits per second: the stream's own figure when the container states one, else the file's average
         * (bytes over duration) less the audio track's rate. The average covers the whole file, not only the part a clip uses: a
         * VBR file can differ in the used range, which the dialog accepts (it recommends, the user decides). Null when neither is known.
         */
        fun bitrate(streamBitrate: Long?, audioBitrate: Long, fileBytes: Long?, durationMicros: Long): Long? {
            if (streamBitrate != null && streamBitrate > 0) return streamBitrate
            if (fileBytes == null || fileBytes <= 0 || durationMicros <= 0) return null
            val total = fileBytes * 8L * 1_000_000L / durationMicros
            return (total - audioBitrate.coerceAtLeast(0)).takeIf { it > 0 }
        }
    }
}

/** Copies what a probe learned about the video track onto an asset; values the probe did not find keep the asset's own. */
fun MediaAssetDto.withVideoFacts(media: ProbedMedia): MediaAssetDto = copy(
    videoWidth = media.videoWidth ?: videoWidth,
    videoHeight = media.videoHeight ?: videoHeight,
    videoBitrate = media.videoBitrate ?: videoBitrate,
    videoCodec = media.videoCodec ?: videoCodec,
    tenBit = media.tenBit ?: tenBit,
)

/** True when probing [media] would add something to this video asset's export facts (an older project, or a first probe). */
fun MediaAssetDto.lacksVideoFacts(media: ProbedMedia): Boolean =
    hasVideo && !isImage && withVideoFacts(media) != this

/**
 * Whether a provider-reported media type is a still picture. Pictures take the image path (header probe, `isImage` asset, the native
 * image decoder for thumbnails); sending one down the video path made 43 photos fail with IO_ERROR on every editor open.
 */
internal fun isImageMime(type: String?): Boolean = type != null && type.startsWith("image/")

private const val TAG = "MediaImport"

/** Largest animated picture read to find its frame delays; a bigger file is imported as a still photo. */
private const val MAX_ANIMATION_BYTES = 48 * 1024 * 1024
