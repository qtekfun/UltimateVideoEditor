package com.qtekfun.ultimatevideoeditor.engine.stabilise

import android.content.ContentResolver
import android.net.Uri
import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto
import com.qtekfun.ultimatevideoeditor.domain.Clip
import com.qtekfun.ultimatevideoeditor.domain.FrameRate
import com.qtekfun.ultimatevideoeditor.domain.StabKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Opens a media file for the native analysis: a detached file descriptor whose ownership passes to the caller, or null. */
fun interface MediaFdOpener {
    fun open(uri: String): Int?
}

/** [MediaFdOpener] on top of the system's content resolver (the files the user picked). */
class ContentResolverFdOpener(private val resolver: ContentResolver) : MediaFdOpener {
    override fun open(uri: String): Int? = try {
        resolver.openFileDescriptor(Uri.parse(uri), "r")?.detachFd()
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }
}

/**
 * [Stabiliser] on the native engine, keeping its analysis files in [cacheDir] (`<assetId>.<hash>`, see
 * [StabCacheFile]); the system may clear that directory, which only costs a re-analysis.
 */
class FileStabiliser internal constructor(
    private val cacheDir: File,
    private val opener: MediaFdOpener,
    private val native: StabNative,
    private val pollMillis: Long = POLL_MILLIS,
) : Stabiliser {
    constructor(cacheDir: File, opener: MediaFdOpener) : this(cacheDir, opener, JniStabNative)

    private class Registered(val path: String, val modified: Long, val length: Long, val strength: Double, val crop: Int, val fps: FrameRate)

    private val registered = HashMap<Int, Registered>()
    private val lock = Any()
    private var runningHandle: Long = 0L

    private fun fileOf(asset: MediaAssetDto) = File(cacheDir, StabCacheFile.nameFor(asset))

    /** The clip's own source range, in microseconds from the media's first frame. */
    private fun clipRange(clip: Clip, fps: FrameRate): Pair<Long, Long> =
        fps.framesToMicros(clip.sourceIn.value).coerceAtLeast(0L) to fps.framesToMicros(clip.sourceOut.value)

    private fun coversClip(header: StabCacheHeader, clip: Clip, fps: FrameRate): Boolean {
        val (start, end) = clipRange(clip, fps)
        val tolerance = fps.framesToMicros(COVER_TOLERANCE_FRAMES)
        return header.analysisVersion == StabCacheFile.ANALYSIS_VERSION &&
            header.rangeStartUs <= start + tolerance && header.rangeEndUs >= end - tolerance
    }

    override fun statusOf(asset: MediaAssetDto, clip: Clip, fps: FrameRate): StabStatus {
        if (clip.stabilise == null || !asset.hasVideo || asset.isImage) return StabStatus.Off
        val header = StabCacheFile.readHeader(fileOf(asset)) ?: return StabStatus.NotAnalysed
        return if (coversClip(header, clip, fps)) StabStatus.Ready else StabStatus.Stale
    }

    override suspend fun analyse(asset: MediaAssetDto, clip: Clip, fps: FrameRate, onProgress: (Float) -> Unit): StabOutcome =
        withContext(Dispatchers.IO) {
            synchronized(lock) { if (runningHandle != 0L) return@withContext StabOutcome.Failed("Another stabilisation analysis is running") }
            if (!cacheDir.isDirectory && !cacheDir.mkdirs()) return@withContext StabOutcome.Failed("Could not create the analysis folder")
            val file = fileOf(asset)
            val (clipStart, clipEnd) = clipRange(clip, fps)
            var start = (clipStart - MARGIN_MICROS).coerceAtLeast(0L)
            var end = clipEnd + MARGIN_MICROS
            // Merge with what was analysed before so extending a clip does not throw the earlier part away.
            StabCacheFile.readHeader(file)?.takeIf { it.analysisVersion == StabCacheFile.ANALYSIS_VERSION }?.let {
                start = minOf(start, it.rangeStartUs)
                end = maxOf(end, it.rangeEndUs)
            }
            val fd = opener.open(asset.uri) ?: return@withContext StabOutcome.Failed("The media file could not be opened")
            val handle = native.create()
            synchronized(lock) { runningHandle = handle }
            try {
                val started = native.start(handle, fd, start, end, file.path)
                if (started != 0) return@withContext StabOutcome.Failed(describe(started))
                pollUntilDone(handle, onProgress)
            } finally {
                withContext(NonCancellable) {
                    synchronized(lock) { runningHandle = 0L }
                    native.destroy(handle)  // joins the worker thread
                }
            }
        }

    /** Polls the native job until it ends, reporting progress; stops the worker if the caller is cancelled meanwhile. */
    private suspend fun pollUntilDone(handle: Long, onProgress: (Float) -> Unit): StabOutcome {
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
                    return StabOutcome.Done
                }
                StabJob.State.CANCELLED -> return StabOutcome.Cancelled
                StabJob.State.FAILED -> return StabOutcome.Failed(describe(job.errorCode))
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

    override fun register(asset: MediaAssetDto, clip: Clip, fps: FrameRate): Boolean {
        val stabilise = clip.stabilise ?: return false
        if (statusOf(asset, clip, fps) != StabStatus.Ready) return false
        val key = StabKey.of(asset.id, stabilise)
        val file = fileOf(asset)
        val wanted = Registered(file.path, file.lastModified(), file.length(), stabilise.strength, stabilise.crop.code, fps)
        synchronized(lock) {
            val have = registered[key]
            if (have != null && have.path == wanted.path && have.modified == wanted.modified && have.length == wanted.length &&
                have.strength == wanted.strength && have.crop == wanted.crop && have.fps == wanted.fps && native.isRegistered(key)
            ) {
                return true
            }
            val status = native.register(key, file.path, fps.num, fps.den, stabilise.strength.toFloat(), stabilise.crop.code)
            if (status != 0) {
                registered.remove(key)
                return false
            }
            registered[key] = wanted
            return true
        }
    }

    override fun releaseAll() {
        synchronized(lock) {
            registered.clear()
            native.releaseAll()
        }
    }

    private fun describe(code: Int): String = when (code) {
        NATIVE_IO_ERROR -> "The media file could not be read"
        NATIVE_UNSUPPORTED -> "This clip has too little picture or movement to analyse"
        NATIVE_CODEC_ERROR -> "The video could not be decoded"
        else -> "The analysis failed (code $code)"
    }

    private companion object {
        const val POLL_MILLIS = 150L
        const val COVER_TOLERANCE_FRAMES = 2L
        /** Analysed beyond each end of the clip, so a small trim outwards does not need a new analysis. */
        const val MARGIN_MICROS = 1_000_000L
        const val NATIVE_IO_ERROR = 3
        const val NATIVE_UNSUPPORTED = 4
        const val NATIVE_CODEC_ERROR = 5
    }
}
