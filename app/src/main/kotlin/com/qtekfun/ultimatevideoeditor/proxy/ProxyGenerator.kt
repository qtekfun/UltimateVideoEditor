package com.qtekfun.ultimatevideoeditor.proxy

import com.qtekfun.ultimatevideoeditor.domain.SourceColorSpace
import com.qtekfun.ultimatevideoeditor.engine.export.ExportCodec
import com.qtekfun.ultimatevideoeditor.engine.export.ExportErrorCode
import com.qtekfun.ultimatevideoeditor.engine.export.ExportException
import com.qtekfun.ultimatevideoeditor.engine.export.ExportHandle
import com.qtekfun.ultimatevideoeditor.engine.export.ExportListener
import com.qtekfun.ultimatevideoeditor.engine.export.ExportRequest
import com.qtekfun.ultimatevideoeditor.engine.export.ExportRunner
import com.qtekfun.ultimatevideoeditor.engine.export.ExportSettings
import com.qtekfun.ultimatevideoeditor.engine.export.VideoClipSpec
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/** Makes the proxy of one job. One at a time; [cancelCurrent] may be called from any thread. */
interface ProxyTranscoder {
    /**
     * Blocks until the proxy of [entry] is written (returns the READY entry) or fails.
     * @throws ProxyException with the reason; the index then holds the outcome (removed or FAILED).
     */
    fun generate(entry: ProxyEntry, onProgress: (permille: Int) -> Unit): ProxyEntry

    fun cancelCurrent()
}

/**
 * Makes proxies with the same offline engine the exporter uses: a one-clip movie at the source's own
 * frame rate and length, scaled down, H.264, no audio (sound always comes from the original). Because the
 * frame rate and the length are the source's, every timeline frame maps to the same frame of the proxy.
 * The encoder inserts a keyframe every second, which is what scrubbing needs.
 *
 * HDR sources are tone-mapped to SDR: the proxy is an 8-bit Rec.709 stand-in and the preview treats it as such.
 */
class ProxyGenerator(
    private val runner: ExportRunner,
    private val media: ProxyMediaAccess,
    private val index: ProxyIndex,
    private val clock: () -> Long = System::currentTimeMillis,
) : ProxyTranscoder {
    private val running = AtomicReference<ExportHandle?>(null)
    @Volatile private var cancelRequested = false

    override fun cancelCurrent() {
        cancelRequested = true
        running.get()?.cancel()
    }

    override fun generate(entry: ProxyEntry, onProgress: (Int) -> Unit): ProxyEntry {
        cancelRequested = false
        val job = entry.job
        val info = try {
            media.probe(job.uri)
        } catch (e: ProxyException) {
            fail(entry, e)
            throw e
        }
        val target = ProxyTargets.plan(info, job.fpsNum, job.fpsDen, entry.targetShortSide)
        if (target == null) {
            index.remove(entry.key)
            throw ProxyException(ProxyErrorCode.NOT_NEEDED, "The video is already small enough to edit without a proxy")
        }
        if (cancelRequested) return cancelled(entry)
        index.put(entry.copy(state = ProxyState.RUNNING, width = target.width, height = target.height, error = null))
        val part = index.partFileFor(entry.key)
        part.delete()

        var sourceFd = -1
        var outputFd = -1
        try {
            sourceFd = media.openSource(job.uri)
            outputFd = media.openOutput(part)
        } catch (e: IOException) {
            if (sourceFd >= 0) media.close(sourceFd)
            if (outputFd >= 0) media.close(outputFd)
            part.delete()
            val error = ProxyException(ProxyErrorCode.IO, "Cannot open a file for the proxy: ${e.message}", e)
            fail(entry, error)
            throw error
        }

        val request = ExportRequest(
            settings = ExportSettings(
                width = target.width,
                height = target.height,
                fpsNum = job.fpsNum,
                fpsDen = job.fpsDen,
                codec = ExportCodec.H264,
                videoBitrate = target.bitrate,
            ),
            projectFpsNum = job.fpsNum,
            projectFpsDen = job.fpsDen,
            canvasWidth = target.width,
            canvasHeight = target.height,
            totalFrames = job.durationFrames,
            assetFds = mapOf(ASSET_KEY to sourceFd),
            videoClips = listOf(
                VideoClipSpec(
                    startFrame = 0,
                    durationFrames = job.durationFrames,
                    sourceInFrame = 0,
                    assetKey = ASSET_KEY,
                    layer = 0,
                    colorMode = SourceColorSpace.fromId(job.colorSpace).nativeModeValue,
                ),
            ),
            audioSnapshot = null,
            outputFd = outputFd,
        )

        val finished = CountDownLatch(1)
        val outcome = AtomicReference<ExportException?>(null)
        val handle = try {
            runner.start(
                request,
                object : ExportListener {
                    override fun onProgress(permille: Int) = onProgress(permille)

                    override fun onFinished(error: ExportException?) {
                        outcome.set(error)
                        finished.countDown()
                    }
                },
            )
        } catch (e: ExportException) {
            // The engine owns the descriptors from the call, even when it throws.
            part.delete()
            val error = ProxyException(codeOf(e), e.message ?: "The proxy could not be started", e)
            fail(entry, error)
            throw error
        }
        running.set(handle)
        if (cancelRequested) handle.cancel()
        try {
            finished.await()
        } finally {
            running.set(null)
            handle.close()
        }

        val error = outcome.get()
        if (error != null) {
            part.delete()
            if (error.code == ExportErrorCode.CANCELLED) return cancelled(entry)
            val failure = ProxyException(codeOf(error), error.message ?: "The proxy failed", error)
            fail(entry, failure)
            throw failure
        }
        val final = index.finalFileFor(entry.key)
        if (!part.isFile || part.length() == 0L || !(final.delete() || !final.exists()) || !part.renameTo(final)) {
            part.delete()
            val failure = ProxyException(ProxyErrorCode.IO, "The proxy file could not be finished")
            fail(entry, failure)
            throw failure
        }
        val now = clock()
        val ready = entry.copy(
            state = ProxyState.READY,
            width = target.width,
            height = target.height,
            fileName = final.name,
            bytes = final.length(),
            sourceBytes = media.sizeOf(job.uri).coerceAtLeast(0L),
            createdAtMs = now,
            lastUsedMs = now,
            error = null,
        )
        index.put(ready)
        return ready
    }

    /** A cancelled job leaves no trace: the user asked for it to stop. */
    private fun cancelled(entry: ProxyEntry): Nothing {
        index.remove(entry.key)
        throw ProxyException(ProxyErrorCode.CANCELLED, "The proxy was cancelled")
    }

    private fun fail(entry: ProxyEntry, error: ProxyException) {
        index.put(entry.copy(state = ProxyState.FAILED, fileName = null, bytes = 0, error = error.message))
    }

    private fun codeOf(error: ExportException): ProxyErrorCode = when (error.code) {
        ExportErrorCode.UNSUPPORTED_FORMAT, ExportErrorCode.CODEC_ERROR -> ProxyErrorCode.ENCODER_UNSUPPORTED
        ExportErrorCode.IO_ERROR -> ProxyErrorCode.IO
        else -> ProxyErrorCode.ENGINE
    }

    internal companion object {
        /** The single source of the one-clip movie. */
        const val ASSET_KEY = 1L
    }
}
