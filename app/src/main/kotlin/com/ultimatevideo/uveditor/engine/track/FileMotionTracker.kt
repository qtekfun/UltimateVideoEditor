package com.ultimatevideo.uveditor.engine.track

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.ultimatevideo.uveditor.data.model.MediaAssetDto
import com.ultimatevideo.uveditor.domain.Clip
import com.ultimatevideo.uveditor.domain.FrameRate
import com.ultimatevideo.uveditor.domain.MotionTrack
import com.ultimatevideo.uveditor.domain.TrackPath
import com.ultimatevideo.uveditor.engine.stabilise.MediaFdOpener
import com.ultimatevideo.uveditor.engine.stabilise.StabJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/** Reads the upright picture's width / height of a media file, or null when it cannot be read. */
fun interface FrameAspectProbe {
    fun aspectOf(uri: String): Double?
}

/** [FrameAspectProbe] on the system's metadata reader (rotation applied). Local; nothing is sent anywhere. */
class MediaMetadataAspectProbe(private val context: Context) : FrameAspectProbe {
    override fun aspectOf(uri: String): Double? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, Uri.parse(uri))
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (w == null || h == null || w <= 0 || h <= 0) null else if (rotation % 180 != 0) h.toDouble() / w else w.toDouble() / h
        } catch (_: RuntimeException) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
}

/**
 * [MotionTracker] on the native engine, keeping its analysis files in [cacheDir] (`<assetId>.<hash>`, see
 * [TrackCacheFile]); the system may clear that directory, which only costs a re-analysis.
 */
class FileMotionTracker internal constructor(
    private val cacheDir: File,
    private val opener: MediaFdOpener,
    private val aspectProbe: FrameAspectProbe,
    private val native: TrackNative,
    private val pollMillis: Long = POLL_MILLIS,
) : MotionTracker {
    constructor(cacheDir: File, opener: MediaFdOpener, aspectProbe: FrameAspectProbe) : this(cacheDir, opener, aspectProbe, JniTrackNative)

    private val lock = Any()
    private var runningHandle: Long = 0L

    private fun fileOf(asset: MediaAssetDto, track: MotionTrack) = File(cacheDir, TrackCacheFile.nameFor(asset, track.seed))

    /** The clip's source range widened to hold the seed, in microseconds from the media's first frame. */
    private fun clipRange(clip: Clip, track: MotionTrack, fps: FrameRate): Pair<Long, Long> {
        val start = minOf(clip.sourceIn.value, track.seed.sourceFrame).coerceAtLeast(0L)
        val end = maxOf(clip.sourceOut.value, track.seed.sourceFrame)
        return fps.framesToMicros(start) to fps.framesToMicros(end)
    }

    override suspend fun frameAspect(asset: MediaAssetDto): Double? = withContext(Dispatchers.IO) { aspectProbe.aspectOf(asset.uri) }

    override fun statusOf(asset: MediaAssetDto, clip: Clip, track: MotionTrack, fps: FrameRate): TrackStatus {
        val file = fileOf(asset, track)
        val header = TrackCacheFile.readHeader(file) ?: return TrackStatus.NotAnalysed
        if (header.analysisVersion != TrackCacheFile.ANALYSIS_VERSION) return TrackStatus.NotAnalysed
        val (start, end) = clipRange(clip, track, fps)
        val tolerance = fps.framesToMicros(COVER_TOLERANCE_FRAMES)
        if (header.rangeStartUs > start + tolerance || header.rangeEndUs < end - tolerance) return TrackStatus.Stale
        val path = TrackCacheFile.readPath(file, fps) ?: return TrackStatus.NotAnalysed
        return TrackStatus.Ready(lost = path.lostCount, frames = path.frames.size)
    }

    override suspend fun analyse(asset: MediaAssetDto, clip: Clip, track: MotionTrack, fps: FrameRate, onProgress: (Float) -> Unit): TrackOutcome =
        withContext(Dispatchers.IO) {
            synchronized(lock) { if (runningHandle != 0L) return@withContext TrackOutcome.Failed("Another tracking analysis is running") }
            if (!cacheDir.isDirectory && !cacheDir.mkdirs()) return@withContext TrackOutcome.Failed("Could not create the tracking folder")
            val file = fileOf(asset, track)
            val (clipStart, clipEnd) = clipRange(clip, track, fps)
            val start = (clipStart - MARGIN_MICROS).coerceAtLeast(0L)
            val end = clipEnd + MARGIN_MICROS
            val seedUs = fps.framesToMicros(track.seed.sourceFrame)
            val halfFrameUs = fps.framesToMicros(1) / 2
            val fd = opener.open(asset.uri) ?: return@withContext TrackOutcome.Failed("The media file could not be opened")
            val handle = native.create()
            synchronized(lock) { runningHandle = handle }
            try {
                val seed = track.seed
                val started = native.start(
                    handle, fd, start, end, seedUs, halfFrameUs,
                    seed.cx.toFloat(), seed.cy.toFloat(), seed.w.toFloat(), seed.h.toFloat(), file.path,
                )
                if (started != 0) return@withContext TrackOutcome.Failed(describe(started))
                pollUntilDone(handle, onProgress)
            } finally {
                withContext(NonCancellable) {
                    synchronized(lock) { runningHandle = 0L }
                    native.destroy(handle)  // joins the worker thread
                }
            }
        }

    /** Polls the native job until it ends, reporting progress; stops the worker if the caller is cancelled meanwhile. */
    private suspend fun pollUntilDone(handle: Long, onProgress: (Float) -> Unit): TrackOutcome {
        var last = -1f
        while (true) {
            val job = StabJob.unpack(native.poll(handle))
            when (job.state) {
                StabJob.State.RUNNING, StabJob.State.IDLE -> {
                    val progress = job.permille / 1000f
                    if (progress - last >= 0.01f) {
                        last = progress
                        onProgress(progress)
                    }
                }
                StabJob.State.DONE -> {
                    onProgress(1f)
                    return TrackOutcome.Done
                }
                StabJob.State.CANCELLED -> return TrackOutcome.Cancelled
                StabJob.State.FAILED -> return TrackOutcome.Failed(describe(job.errorCode))
            }
            try {
                delay(pollMillis)
            } catch (e: CancellationException) {
                native.cancel(handle)  // the screen went away: stop the worker rather than leave it running
                throw e
            }
        }
    }

    override fun cancel() {
        // Under the lock: the analysis clears `runningHandle` under the same lock before it destroys the native
        // service, so the handle cannot be freed while this call is using it.
        synchronized(lock) { if (runningHandle != 0L) native.cancel(runningHandle) }
    }

    override fun load(asset: MediaAssetDto, track: MotionTrack, fps: FrameRate): TrackPath? {
        val file = fileOf(asset, track)
        val header = TrackCacheFile.readHeader(file) ?: return null
        if (header.analysisVersion != TrackCacheFile.ANALYSIS_VERSION) return null
        return TrackCacheFile.readPath(file, fps)
    }

    override fun forget(asset: MediaAssetDto, track: MotionTrack) {
        fileOf(asset, track).delete()
    }

    private fun describe(code: Int): String = when (code) {
        NATIVE_INVALID -> "The target is outside the part of the video that was analysed"
        NATIVE_IO_ERROR -> "The media file could not be read"
        NATIVE_UNSUPPORTED -> "The target could not be followed in this clip"
        NATIVE_CODEC_ERROR -> "The video could not be decoded"
        else -> "The tracking failed (code $code)"
    }

    private companion object {
        const val POLL_MILLIS = 150L
        const val COVER_TOLERANCE_FRAMES = 2L
        /** Analysed beyond each end of the clip, so a small trim outwards does not need a new analysis. */
        const val MARGIN_MICROS = 500_000L
        const val NATIVE_INVALID = 1
        const val NATIVE_IO_ERROR = 3
        const val NATIVE_UNSUPPORTED = 4
        const val NATIVE_CODEC_ERROR = 5
    }
}
