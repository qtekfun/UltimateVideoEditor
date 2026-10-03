package com.ultimatevideo.uveditor.data

import android.content.Context
import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaFormat
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
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
)

/** The media file could not be opened or understood. Always carries a user-presentable message. */
class MediaImportException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface MediaImporter {
    /** Keeps read access to [uri] across restarts, then probes it. @throws MediaImportException */
    suspend fun import(uri: String): ProbedMedia
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

    fun fromTransfer(transfer: Int?): String = when (transfer) {
        MediaFormat.COLOR_TRANSFER_HLG -> HLG
        MediaFormat.COLOR_TRANSFER_ST2084 -> PQ
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
            throw MediaImportException("Cannot keep access to the selected file", e)
        }
        probe(parsed)
    }

    internal fun probe(uri: Uri): ProbedMedia {
        if (context.contentResolver.getType(uri)?.startsWith("image/") == true) return probeImage(uri)
        val extractor = MediaExtractor()
        try {
            try {
                extractor.setDataSource(context, uri, null)
            } catch (e: IOException) {
                throw MediaImportException("Cannot open the selected file", e)
            } catch (e: IllegalArgumentException) {
                throw MediaImportException("Unsupported file", e)
            } catch (e: SecurityException) {
                throw MediaImportException("No permission to read the selected file", e)
            }
            return probeTracks(extractor, uri)
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
            throw MediaImportException("Cannot open the selected picture", e)
        } catch (e: SecurityException) {
            throw MediaImportException("No permission to read the selected picture", e)
        }
        if (options.outWidth <= 0 || options.outHeight <= 0) throw MediaImportException("This picture format is not supported")
        return ProbedMedia(
            durationMicros = 0,
            fpsNum = FpsRational.DEFAULT_FPS,
            fpsDen = 1,
            colorSpace = ColorSpaceNames.SDR,
            hasVideo = false,
            hasAudio = false,
            isImage = true,
        )
    }

    private fun probeTracks(extractor: MediaExtractor, uri: Uri): ProbedMedia {
        var hasVideo = false
        var hasAudio = false
        var durationMicros = 0L
        var fps: Double? = null
        var transfer: Int? = null
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
                }
                mime.startsWith("audio/") -> hasAudio = true
            }
        }
        if (!hasVideo && !hasAudio) throw MediaImportException("The file has no audio or video track")
        if (durationMicros <= 0) throw MediaImportException("The file has no readable duration")
        val (num, den) = FpsRational.fromFloat(fps ?: captureFrameRate(uri) ?: FpsRational.DEFAULT_FPS.toDouble())
        return ProbedMedia(durationMicros, num, den, ColorSpaceNames.fromTransfer(transfer), hasVideo, hasAudio)
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
