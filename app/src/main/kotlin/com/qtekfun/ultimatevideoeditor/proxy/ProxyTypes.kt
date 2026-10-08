package com.qtekfun.ultimatevideoeditor.proxy

import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto
import kotlinx.serialization.Serializable
import java.security.MessageDigest

/** Where a proxy of one source file stands. Only [READY] proxies are ever used. */
enum class ProxyState { QUEUED, RUNNING, READY, STALE, FAILED }

/**
 * Everything needed to (re)generate the proxy of one source file. It is kept in the index so a process that
 * restarts can resume the queue without the project that asked for the proxy being open.
 */
@Serializable
data class ProxyJob(
    val uri: String,
    val durationFrames: Long,
    val fpsNum: Int,
    val fpsDen: Int,
    val colorSpace: String,
)

/** One proxy in the side index. The project file never mentions proxies. */
@Serializable
data class ProxyEntry(
    val key: String,
    val job: ProxyJob,
    val state: ProxyState,
    /** The short side the proxy was asked for (720 or 1080); part of [key], so changing it makes new proxies. */
    val targetShortSide: Int,
    val width: Int = 0,
    val height: Int = 0,
    /** The finished file inside the proxy directory; null until [ProxyState.READY]. */
    val fileName: String? = null,
    val bytes: Long = 0,
    /** Size of the source when the proxy was made, to notice a source that changed under the same address. */
    val sourceBytes: Long = 0,
    val createdAtMs: Long = 0,
    val lastUsedMs: Long = 0,
    val error: String? = null,
)

object ProxyKeys {
    /** Same file, same length and frame rate, same proxy size: same key. A relinked asset gets a new key. */
    fun of(job: ProxyJob, targetShortSide: Int): String {
        val text = "${job.uri}|${job.durationFrames}|${job.fpsNum}/${job.fpsDen}|$targetShortSide"
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        return digest.take(KEY_BYTES).joinToString("") { "%02x".format(it) }
    }

    private const val KEY_BYTES = 8
}

fun jobOf(asset: MediaAssetDto): ProxyJob =
    ProxyJob(asset.uri, asset.durationFrames, asset.nativeFpsNum, asset.nativeFpsDen, asset.colorSpace)

/** Whether an asset is the kind of media a proxy can stand in for: decoded video, not a photo or audio. */
fun MediaAssetDto.canHaveProxy(): Boolean = hasVideo && !isImage

enum class ProxyErrorCode {
    /** The source is already small enough: a proxy would not help. */
    NOT_NEEDED,
    SOURCE_UNREADABLE,
    ENCODER_UNSUPPORTED,
    CANCELLED,
    IO,
    ENGINE,
}

/** A proxy could not be made. Never swallowed: the code decides what the user is told. */
class ProxyException(val code: ProxyErrorCode, message: String, cause: Throwable? = null) : Exception(message, cause)
