package com.qtekfun.ultimatevideoeditor.ui.editor.tray

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

private const val TAG = "UVPreview"

/**
 * The system's [MediaPlayer] behind the source preview. It plays any file the person can reach, in the project or not, with its sound,
 * and keeps its own decoder: it does not touch the timeline's. A photo is not played (the sheet draws it), so it only reports that it is ready.
 */
internal class AndroidAuditionPlayer(
    private val context: Context,
    private val listener: AuditionPlayer.Listener,
) : AuditionPlayer {
    private var media: MediaPlayer? = null
    private var ready = false
    private var released = false

    override fun open(item: SourceItem) {
        if (item.kind == AssetKind.PHOTO) {
            listener.onPrepared(0, 0, 0)
            return
        }
        val player = MediaPlayer()
        media = player
        try {
            player.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
            player.setDataSource(context, Uri.parse(item.uri))
            player.setOnPreparedListener {
                ready = true
                // Showing the first picture needs a seek: a prepared player draws nothing until it is told where to be.
                it.seekTo(0, MediaPlayer.SEEK_CLOSEST)
                listener.onPrepared(it.duration.toLong().coerceAtLeast(0), it.videoWidth, it.videoHeight)
            }
            player.setOnCompletionListener { listener.onCompleted() }
            player.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "Player error $what/$extra for ${item.uri.substringAfterLast('/')}")
                listener.onFailed(if (extra == MediaPlayer.MEDIA_ERROR_UNSUPPORTED || extra == MediaPlayer.MEDIA_ERROR_MALFORMED) SourceProblem.UNSUPPORTED else SourceProblem.UNREADABLE)
                true
            }
            player.prepareAsync()
        } catch (e: IOException) {
            Log.w(TAG, "Cannot open ${item.uri.substringAfterLast('/')}: ${e.message}")
            listener.onFailed(SourceProblem.UNREADABLE)
        } catch (e: SecurityException) {
            Log.w(TAG, "No access to ${item.uri.substringAfterLast('/')}: ${e.message}")
            listener.onFailed(SourceProblem.UNREADABLE)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Not a file I can open: ${e.message}")
            listener.onFailed(SourceProblem.UNREADABLE)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Player in the wrong state: ${e.message}")
            listener.onFailed(SourceProblem.UNSUPPORTED)
        }
    }

    override fun setSurface(surface: Surface?) {
        if (released) return
        try {
            media?.setSurface(surface)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Surface not set: ${e.message}")
        }
    }

    override fun play() = guard { if (ready) it.start() }

    override fun pause() = guard { if (ready && it.isPlaying) it.pause() }

    override fun seekTo(ms: Long) = guard { if (ready) it.seekTo(ms, MediaPlayer.SEEK_CLOSEST) }

    override val positionMs: Long
        get() = try {
            if (ready && !released) media?.currentPosition?.toLong() ?: 0 else 0
        } catch (e: IllegalStateException) {
            0
        }

    override fun setMuted(muted: Boolean) = guard { it.setVolume(if (muted) 0f else 1f, if (muted) 0f else 1f) }

    override fun setLooping(looping: Boolean) = guard { it.isLooping = looping }

    override fun release() {
        released = true
        ready = false
        try {
            media?.release()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Release: ${e.message}")
        }
        media = null
    }

    private inline fun guard(block: (MediaPlayer) -> Unit) {
        if (released) return
        val player = media ?: return
        try {
            block(player)
        } catch (e: IllegalStateException) {
            // A player that went into an error state refuses everything; the error listener already told the sheet.
            Log.w(TAG, "Player refused: ${e.message}")
        }
    }
}

/** Reads length, picture size, frame rate, format and bit rate with the system's retriever; nothing is played or stored. */
internal class AndroidSourceInfoReader(private val context: Context) : SourceInfoReader {
    override suspend fun read(item: SourceItem): SourceInfo? = withContext(Dispatchers.IO) {
        try {
            if (item.kind == AssetKind.PHOTO) return@withContext photoInfo(item)
            MediaMetadataRetriever().use { r ->
                r.setDataSource(context, Uri.parse(item.uri))
                val rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                val turned = rotation == 90 || rotation == 270
                SourceInfo(
                    durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull(),
                    width = if (turned) h else w,
                    height = if (turned) w else h,
                    framesPerSecond = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull(),
                    mime = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE),
                    bitrate = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull(),
                )
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "No facts for ${item.uri.substringAfterLast('/')}: ${e.message}")
            null
        } catch (e: IOException) {
            Log.w(TAG, "No facts for ${item.uri.substringAfterLast('/')}: ${e.message}")
            null
        }
    }

    private fun photoInfo(item: SourceItem): SourceInfo? {
        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(Uri.parse(item.uri))?.use { android.graphics.BitmapFactory.decodeStream(it, null, options) }
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        return SourceInfo(null, options.outWidth, options.outHeight, null, options.outMimeType, null)
    }
}
