package com.qtekfun.ultimatevideoeditor.engine.track

import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto
import com.qtekfun.ultimatevideoeditor.domain.FrameRate
import com.qtekfun.ultimatevideoeditor.domain.TrackFrame
import com.qtekfun.ultimatevideoeditor.domain.TrackPath
import com.qtekfun.ultimatevideoeditor.domain.TrackSeed
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import kotlin.math.roundToLong

/**
 * What a track cache file says about itself, read from its fixed 48-byte header (layout in `track/track_path.h`;
 * keep the offsets in sync). [rangeStartUs] and [rangeEndUs] are measured from the first frame of the media.
 */
data class TrackCacheHeader(
    val analysisVersion: Int,
    val aspect: Float,
    val seedUs: Long,
    val rangeStartUs: Long,
    val rangeEndUs: Long,
    val samples: Long,
)

object TrackCacheFile {
    /** Bumped together with `kTrackAnalysisVersion` in the native code when the tracker changes results. */
    const val ANALYSIS_VERSION = 1

    const val HEADER_BYTES = 48
    const val SAMPLE_BYTES = 36
    private const val FORMAT_VERSION = 1
    private val MAGIC = byteArrayOf('U'.code.toByte(), 'V'.code.toByte(), 'T'.code.toByte(), 'K'.code.toByte())

    /**
     * File name `<assetId>.<hash>`: the hash covers what identifies the media (where it is, how long, at what rate), the
     * analysis version and the seed (frame and box), so another target, a relinked file or a tracker change finds no cache.
     */
    fun nameFor(asset: MediaAssetDto, seed: TrackSeed): String {
        var hash = 0x811C9DC5.toInt()
        val key = "${asset.uri}|${asset.durationFrames}|${asset.nativeFpsNum}/${asset.nativeFpsDen}|v$ANALYSIS_VERSION|" +
            "${seed.sourceFrame}|${q(seed.cx)}|${q(seed.cy)}|${q(seed.w)}|${q(seed.h)}"
        for (ch in key) hash = (hash xor ch.code) * 0x01000193
        return "${asset.id}.${Integer.toHexString(hash)}"
    }

    private fun q(v: Double): Long = (v * 10_000.0).roundToLong()

    /** The header of a structurally sound cache file, or null when it is missing, truncated or not a cache. */
    fun readHeader(file: File): TrackCacheHeader? = try {
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
            }?.let { parseHeader(it, file.length()) }
        }
    } catch (_: IOException) {
        null
    }

    internal fun parseHeader(bytes: ByteArray, fileLength: Long): TrackCacheHeader? {
        if (bytes.size < HEADER_BYTES || !bytes.copyOfRange(0, 4).contentEquals(MAGIC)) return null
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (b.getInt(4) != FORMAT_VERSION) return null
        val samples = b.getLong(40)
        if (samples < 2 || fileLength != HEADER_BYTES + samples * SAMPLE_BYTES + 4) return null
        val aspect = b.getFloat(12)
        if (!(aspect > 0.1f && aspect < 20f)) return null
        return TrackCacheHeader(b.getInt(8), aspect, b.getLong(16), b.getLong(24), b.getLong(32), samples)
    }

    /** The whole path with source frames in project frames ([fps] is the frame rate the decoder numbers frames with), or null for a bad file. */
    fun readPath(file: File, fps: FrameRate): TrackPath? = try {
        parsePath(file.readBytes(), fps)
    } catch (_: IOException) {
        null
    }

    internal fun parsePath(bytes: ByteArray, fps: FrameRate): TrackPath? {
        val header = parseHeader(bytes, bytes.size.toLong()) ?: return null
        val crc = CRC32().apply { update(bytes, 0, bytes.size - 4) }.value
        val stored = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(bytes.size - 4).toLong() and 0xFFFFFFFFL
        if (crc != stored) return null
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val half = fps.framesToMicros(1) / 2
        val frames = ArrayList<TrackFrame>(header.samples.toInt())
        for (i in 0 until header.samples.toInt()) {
            val p = HEADER_BYTES + i * SAMPLE_BYTES
            val pts = b.getLong(p)
            frames += TrackFrame(
                sourceFrame = fps.microsToFrames(pts + half),
                cx = b.getFloat(p + 8).toDouble(),
                cy = b.getFloat(p + 12).toDouble(),
                w = b.getFloat(p + 16).toDouble(),
                h = b.getFloat(p + 20).toDouble(),
                confidence = b.getFloat(p + 28).toDouble(),
                lost = bytes[p + 32].toInt() != 0,
            )
        }
        return TrackPath(header.aspect.toDouble(), frames)
    }
}
