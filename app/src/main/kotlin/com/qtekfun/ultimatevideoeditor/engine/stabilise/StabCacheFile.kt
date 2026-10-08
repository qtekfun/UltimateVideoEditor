package com.qtekfun.ultimatevideoeditor.engine.stabilise

import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * What the analysis cache file says about itself, read from its fixed 48-byte header (layout in
 * `stabilise/stab_cache.h`; keep the offsets in sync). [rangeStartUs] and [rangeEndUs] are measured from the first
 * frame of the media, the origin the preview decoder numbers frames from.
 */
data class StabCacheHeader(
    val analysisVersion: Int,
    val aspect: Float,
    val rangeStartUs: Long,
    val rangeEndUs: Long,
    val samples: Long,
)

object StabCacheFile {
    /** Bumped together with `kAnalysisVersion` in the native code when the tracker changes results. */
    const val ANALYSIS_VERSION = 1

    const val HEADER_BYTES = 48
    private const val SAMPLE_BYTES = 28
    private const val FORMAT_VERSION = 1
    private val MAGIC = byteArrayOf('U'.code.toByte(), 'V'.code.toByte(), 'S'.code.toByte(), 'T'.code.toByte())

    /**
     * File name `<assetId>.<hash>`: the hash covers what identifies the media (where it is, how long, at what rate)
     * and the analysis version, so relinking to another file or a tracker change finds no cache and re-analyses.
     */
    fun nameFor(asset: MediaAssetDto): String {
        var hash = 0x811C9DC5.toInt()
        for (ch in "${asset.uri}|${asset.durationFrames}|${asset.nativeFpsNum}/${asset.nativeFpsDen}|v$ANALYSIS_VERSION") {
            hash = (hash xor ch.code) * 0x01000193
        }
        return "${asset.id}.${Integer.toHexString(hash)}"
    }

    /** The header of a structurally sound cache file, or null when it is missing, truncated or not a cache. */
    fun readHeader(file: File): StabCacheHeader? = try {
        if (!file.isFile || file.length() < HEADER_BYTES + 4) {
            null
        } else {
            val bytes = ByteArray(HEADER_BYTES)
            file.inputStream().use { input ->
                var read = 0
                while (read < HEADER_BYTES) {
                    val n = input.read(bytes, read, HEADER_BYTES - read)
                    if (n < 0) break
                    read += n
                }
                if (read < HEADER_BYTES) null else bytes
            }?.let { parse(it, file.length()) }
        }
    } catch (_: IOException) {
        null
    }

    internal fun parse(bytes: ByteArray, fileLength: Long): StabCacheHeader? {
        if (bytes.size < HEADER_BYTES || !bytes.copyOfRange(0, 4).contentEquals(MAGIC)) return null
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (b.getInt(4) != FORMAT_VERSION) return null
        val samples = b.getLong(40)
        if (samples < 2 || fileLength != HEADER_BYTES + samples * SAMPLE_BYTES + 4) return null
        val aspect = b.getFloat(12)
        if (!(aspect > 0.1f && aspect < 20f)) return null
        return StabCacheHeader(
            analysisVersion = b.getInt(8),
            aspect = aspect,
            rangeStartUs = b.getLong(24),
            rangeEndUs = b.getLong(32),
            samples = samples,
        )
    }
}
