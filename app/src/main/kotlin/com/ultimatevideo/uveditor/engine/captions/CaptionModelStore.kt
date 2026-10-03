package com.ultimatevideo.uveditor.engine.captions

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** An open download: the bytes and, when the server said so, how many there will be. */
class ModelDownload(val stream: InputStream, val length: Long?)

/** Where model files come from. [HttpModelSource] on the device; a fake in tests. */
fun interface ModelSource {
    /** @throws IOException when the file cannot be reached. */
    fun open(url: String): ModelDownload
}

/** Plain HTTPS GET; redirects (the model host forwards to a CDN) are followed by the platform. */
class HttpModelSource : ModelSource {
    override fun open(url: String): ModelDownload {
        require(url.startsWith("https://")) { "models are only fetched over HTTPS" }
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MILLIS
        connection.readTimeout = TIMEOUT_MILLIS
        connection.instanceFollowRedirects = true
        val code = connection.responseCode
        if (code != HttpURLConnection.HTTP_OK) {
            connection.disconnect()
            throw IOException("server answered HTTP $code")
        }
        val length = connection.contentLengthLong.takeIf { it > 0 }
        return ModelDownload(connection.inputStream, length)
    }

    private companion object {
        const val TIMEOUT_MILLIS = 20_000
    }
}

/** Bytes fetched so far of a download that will have [totalBytes]. */
data class DownloadProgress(val downloadedBytes: Long, val totalBytes: Long) {
    val percent: Int get() = if (totalBytes <= 0) 0 else (downloadedBytes * 100 / totalBytes).toInt().coerceIn(0, 100)
}

/** What the captions screen needs from model storage; [CaptionModelStore] on the device, a fake in tests. */
interface CaptionModelProvider {
    fun isInstalled(model: CaptionModel): Boolean

    /** Emits progress and completes once the model is installed; fails with [CaptionException]. */
    fun download(model: CaptionModel): Flow<DownloadProgress>

    fun delete(model: CaptionModel)
}

/**
 * The downloaded speech models in app-private storage. A model is only ever visible as its final
 * file once its size and SHA-256 matched, because the download goes to a `.part` file that is
 * verified and then renamed.
 */
class CaptionModelStore(
    private val directory: File,
    private val source: ModelSource,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val freeSpace: () -> Long = { directory.usableSpace },
) : CaptionModelProvider {
    /** The model file when it is installed (and still the size it was verified at), else null. */
    fun fileOf(model: CaptionModel): File? =
        File(directory, model.fileName).takeIf { it.isFile && it.length() == model.sizeBytes }

    override fun isInstalled(model: CaptionModel): Boolean = fileOf(model) != null

    override fun delete(model: CaptionModel) {
        File(directory, model.fileName).delete()
        partFile(model).delete()
    }

    /**
     * Downloads [model], emitting progress about once per percent. Completes when the verified file
     * is in place; fails with [CaptionException] on network trouble, lack of space or a mismatch.
     * Cancelling the collector stops the transfer and removes the partial file.
     */
    override fun download(model: CaptionModel): Flow<DownloadProgress> = flow {
        if (isInstalled(model)) {
            emit(DownloadProgress(model.sizeBytes, model.sizeBytes))
            return@flow
        }
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw CaptionException(CaptionErrorCode.IoError, "Could not create the model folder")
        }
        if (freeSpace() < model.sizeBytes + SPACE_MARGIN_BYTES) {
            throw CaptionException(CaptionErrorCode.NoSpace, "Not enough free space for the ${model.label} model (${megabytes(model.sizeBytes)} MB)")
        }

        val part = partFile(model)
        part.delete()
        var finished = false
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val open = try {
                source.open(model.url)
            } catch (e: IOException) {
                throw CaptionException(CaptionErrorCode.Download, "Could not download the model: ${e.message}", e)
            }
            var copied = 0L
            var lastPercent = -1
            try {
                open.stream.use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            copied += read
                            val progress = DownloadProgress(copied, model.sizeBytes)
                            if (progress.percent != lastPercent) {
                                lastPercent = progress.percent
                                emit(progress)
                            }
                        }
                    }
                }
            } catch (e: IOException) {
                throw CaptionException(CaptionErrorCode.Download, "The model download was interrupted: ${e.message}", e)
            }

            if (copied != model.sizeBytes || digest.digest().toHex() != model.sha256) {
                throw CaptionException(CaptionErrorCode.ModelCorrupt, "The downloaded ${model.label} model is damaged; try again")
            }
            val target = File(directory, model.fileName)
            target.delete()
            if (!part.renameTo(target)) throw CaptionException(CaptionErrorCode.IoError, "Could not save the model")
            finished = true
            emit(DownloadProgress(model.sizeBytes, model.sizeBytes))
        } finally {
            if (!finished) part.delete()
        }
    }.flowOn(io).buffer(Channel.RENDEZVOUS) // no running ahead of the collector, so cancelling stops the transfer promptly

    private fun partFile(model: CaptionModel) = File(directory, model.fileName + ".part")

    private fun megabytes(bytes: Long) = (bytes + MEGABYTE - 1) / MEGABYTE

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
        const val MEGABYTE = 1024L * 1024L
        const val SPACE_MARGIN_BYTES = 50L * MEGABYTE
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
