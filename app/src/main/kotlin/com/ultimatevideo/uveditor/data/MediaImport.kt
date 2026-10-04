package com.ultimatevideo.uveditor.data

import android.content.Context
import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaFormat
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.ultimatevideo.uveditor.domain.AnimationTiming
import com.ultimatevideo.uveditor.engine.still.GifDelayScan
import com.ultimatevideo.uveditor.engine.still.WebpAnimationScan
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
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
        if (context.contentResolver.getType(uri)?.startsWith("image/") == true) return probeImage(uri)
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
        return ProbedMedia(
            durationMicros = 0,
            fpsNum = FpsRational.DEFAULT_FPS,
            fpsDen = 1,
            colorSpace = ColorSpaceNames.SDR,
            hasVideo = false,
            hasAudio = false,
            isImage = true,
            displayName = displayNameOf(uri),
            animationDelaysMs = animationDelays(uri),
        )
    }

    /** Frame delays of an animated GIF or WebP, or null for any other picture (read from the headers, no pixels). */
    private fun animationDelays(uri: Uri): List<Int>? {
        val type = context.contentResolver.getType(uri) ?: return null
        if (type != "image/gif" && type != "image/webp") return null
        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use { it.readAtMost(MAX_ANIMATION_BYTES) } ?: return null
        } catch (e: IOException) {
            return null
        } catch (e: SecurityException) {
            return null
        }
        val raw = if (type == "image/gif") GifDelayScan.delaysMs(bytes) else WebpAnimationScan.delaysMs(bytes)
        return AnimationTiming.ofRaw(raw)?.delaysMs
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
                }
                mime.startsWith("audio/") -> hasAudio = true
            }
        }
        if (!hasVideo && !hasAudio) throw MediaImportException("The file has no audio or video track", problem = MediaProblem.UNSUPPORTED)
        if (durationMicros <= 0) throw MediaImportException("The file has no readable duration", problem = MediaProblem.UNSUPPORTED)
        val (num, den) = FpsRational.fromFloat(fps ?: captureFrameRate(uri) ?: FpsRational.DEFAULT_FPS.toDouble())
        return ProbedMedia(durationMicros, num, den, ColorSpaceNames.detect(transfer, hdrStaticInfo), hasVideo, hasAudio)
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

private const val TAG = "MediaImport"

/** Largest animated picture read to find its frame delays; a bigger file is imported as a still photo. */
private const val MAX_ANIMATION_BYTES = 48 * 1024 * 1024
