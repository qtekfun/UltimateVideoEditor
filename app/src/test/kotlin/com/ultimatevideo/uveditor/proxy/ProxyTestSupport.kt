package com.ultimatevideo.uveditor.proxy

import com.ultimatevideo.uveditor.data.model.MediaAssetDto
import com.ultimatevideo.uveditor.engine.export.ExportErrorCode
import com.ultimatevideo.uveditor.engine.export.ExportException
import com.ultimatevideo.uveditor.engine.export.ExportHandle
import com.ultimatevideo.uveditor.engine.export.ExportListener
import com.ultimatevideo.uveditor.engine.export.ExportRequest
import com.ultimatevideo.uveditor.engine.export.ExportRunner
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal fun testAsset(
    id: String = "a1",
    uri: String = "content://media/$id",
    frames: Long = 300,
    fpsNum: Int = 30,
    fpsDen: Int = 1,
    colorSpace: String = "Rec709-SDR",
    hasVideo: Boolean = true,
    hasAudio: Boolean = true,
    isImage: Boolean = false,
) = MediaAssetDto(id, uri, frames, fpsNum, fpsDen, colorSpace, hasVideo = hasVideo, hasAudio = hasAudio, isImage = isImage)

internal val UHD = SourceInfo(width = 3840, height = 2160, bitrateBps = 80_000_000, sizeBytes = 1_000_000)

/** A fake media layer: probes come from [infos], descriptors are just numbers, outputs are real files. */
internal class FakeMedia(val infos: MutableMap<String, SourceInfo> = mutableMapOf(), val sizes: MutableMap<String, Long> = mutableMapOf()) : ProxyMediaAccess {
    val outputs = HashMap<Int, File>()
    val closed = ArrayList<Int>()
    var failOpenSource = false
    private var nextFd = 100

    override fun openSource(uri: String): Int {
        if (failOpenSource) throw IOException("denied")
        return nextFd++
    }

    override fun openOutput(file: File): Int {
        file.writeBytes(ByteArray(0))
        val fd = nextFd++
        outputs[fd] = file
        return fd
    }

    override fun close(fd: Int) {
        closed += fd
    }

    override fun sizeOf(uri: String): Long = sizes[uri] ?: -1L

    override fun probe(uri: String): SourceInfo = infos[uri] ?: throw ProxyException(ProxyErrorCode.SOURCE_UNREADABLE, "no such file")
}

/** A fake exporter that records requests and, by default, writes 1000 bytes and finishes at once. */
internal class FakeRunner(private val media: FakeMedia) : ExportRunner {
    val requests = ArrayList<ExportRequest>()
    var startError: ExportException? = null
    var cancelCalls = 0
    var captured: ExportListener? = null
    var behavior: (ExportRequest, ExportListener) -> Unit = { request, listener ->
        media.outputs.getValue(request.outputFd).writeBytes(ByteArray(1000))
        listener.onProgress(500)
        listener.onFinished(null)
    }

    override fun start(request: ExportRequest, listener: ExportListener): ExportHandle {
        startError?.let { throw it }
        requests += request
        captured = listener
        behavior(request, listener)
        return object : ExportHandle {
            override fun cancel() {
                cancelCalls++
                captured?.onFinished(ExportException(ExportErrorCode.CANCELLED, "cancelled"))
            }

            override fun close() = Unit
        }
    }
}

/** A transcoder that records the order of jobs and can be held back until released. */
internal class FakeTranscoder(private val index: ProxyIndex) : ProxyTranscoder {
    val order = ArrayList<String>()
    @Volatile var hold: CountDownLatch? = null
    @Volatile var cancelled = false
    @Volatile var failWith: ProxyException? = null
    val started = CountDownLatch(1)

    override fun generate(entry: ProxyEntry, onProgress: (Int) -> Unit): ProxyEntry {
        order += entry.key
        started.countDown()
        index.put(entry.copy(state = ProxyState.RUNNING))
        onProgress(500)
        hold?.await(5, TimeUnit.SECONDS)
        if (cancelled) {
            cancelled = false
            index.remove(entry.key)
            throw ProxyException(ProxyErrorCode.CANCELLED, "cancelled")
        }
        failWith?.let {
            index.put(entry.copy(state = ProxyState.FAILED, error = it.message))
            throw it
        }
        val file = index.finalFileFor(entry.key)
        file.writeBytes(ByteArray(100))
        val ready = entry.copy(state = ProxyState.READY, fileName = file.name, bytes = 100, width = 1280, height = 720, lastUsedMs = order.size.toLong())
        index.put(ready)
        return ready
    }

    override fun cancelCurrent() {
        cancelled = true
        hold?.countDown()
    }
}

internal fun waitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
    val end = System.nanoTime() + timeoutMs * 1_000_000
    while (!condition()) {
        check(System.nanoTime() < end) { "condition not met in ${timeoutMs}ms" }
        Thread.sleep(5)
    }
}

/** A READY entry with a real file of [bytes] bytes under [index]. */
internal fun readyEntry(index: ProxyIndex, key: String, bytes: Int = 100, lastUsed: Long = 1): ProxyEntry {
    val file = index.finalFileFor(key)
    file.writeBytes(ByteArray(bytes))
    val job = ProxyJob("content://$key", 300, 30, 1, "Rec709-SDR")
    return ProxyEntry(key, job, ProxyState.READY, 720, 1280, 720, file.name, bytes.toLong(), 0, 1, lastUsed)
}
