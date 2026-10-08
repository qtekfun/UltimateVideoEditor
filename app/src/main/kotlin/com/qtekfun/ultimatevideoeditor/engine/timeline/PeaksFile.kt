package com.qtekfun.ultimatevideoeditor.engine.timeline

import com.qtekfun.ultimatevideoeditor.domain.beat.PeakEnvelope
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Reads the waveform peak cache the native service writes (`waveforms/<assetId>.peaks`, format "UVPK"
 * v1 and v2, see `audio/waveform_peaks.h`) and turns its finest level into a loudness envelope for beat
 * detection, so analysis never decodes audio a second time.
 */
object PeaksFile {
    private const val MAGIC = 0x4B505655
    private val SUPPORTED_VERSIONS = 1..2
    private const val HEADER_BYTES = 12 + 8 + 4
    private const val LEVEL_HEADER_BYTES = 8
    private const val MAX_LEVEL_COUNT = 16
    private const val MIN_ENVELOPE_SAMPLES_PER_PEAK = 64
    private const val MAX_PEAKS = 1 shl 28
    private const val FULL_SCALE = 32768f

    /** What was found in a peaks file: the envelope of the requested window and where that window starts. */
    class Window(val envelope: PeakEnvelope, val startMicros: Long)

    /**
     * The loudness envelope of [startMicros, endMicros) of the source (clamped to what the file holds),
     * or null when the file is missing, from another version, or truncated. Only the finest level and only
     * the window are read from disk.
     */
    fun readWindow(file: File, startMicros: Long, endMicros: Long): Window? {
        if (!file.isFile || endMicros <= startMicros) return null
        return try {
            RandomAccessFile(file, "r").use { raf -> read(raf, startMicros, endMicros) }
        } catch (_: IOException) {
            null
        }
    }

    private fun read(raf: RandomAccessFile, startMicros: Long, endMicros: Long): Window? {
        val header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        if (!readFully(raf, header)) return null
        header.flip()
        // v1 has min/max pairs per level; v2 adds an RMS block after the pairs of each level of 64 samples per peak or more.
        // Only that level's pairs are read here, laid out the same in both.
        if (header.getInt() != MAGIC || header.getInt() !in SUPPORTED_VERSIONS) return null
        val sampleRate = header.getInt()
        header.getLong() // total frames
        val levelCount = header.getInt()
        if (sampleRate <= 0 || levelCount <= 0 || levelCount > MAX_LEVEL_COUNT) return null

        // The first level of 64 samples per peak or more: finer levels (16, added for the timeline waveform) are skipped, they
        // carry no RMS block, so each is just its header and count * 4 bytes of pairs.
        var levelStart = HEADER_BYTES.toLong()
        var samplesPerPeak = 0
        var count = 0
        var index = 0
        while (true) {
            if (index++ >= levelCount) return null
            val levelHeader = ByteBuffer.allocate(LEVEL_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            raf.seek(levelStart)
            if (!readFully(raf, levelHeader)) return null
            levelHeader.flip()
            samplesPerPeak = levelHeader.getInt()
            count = levelHeader.getInt()
            if (samplesPerPeak <= 0 || count < 0 || count > MAX_PEAKS) return null
            if (samplesPerPeak >= MIN_ENVELOPE_SAMPLES_PER_PEAK) break
            levelStart += LEVEL_HEADER_BYTES + count * 4L
        }

        val binsPerSecond = sampleRate.toDouble() / samplesPerPeak
        val firstBin = max(0L, (startMicros * binsPerSecond / 1_000_000.0).toLong())
        val lastBin = min(count.toLong(), (endMicros * binsPerSecond / 1_000_000.0).toLong() + 1)
        if (lastBin <= firstBin) return null

        val bins = (lastBin - firstBin).toInt()
        val data = ByteBuffer.allocate(bins * 4).order(ByteOrder.LITTLE_ENDIAN)
        raf.seek(levelStart + LEVEL_HEADER_BYTES + firstBin * 4)
        if (!readFully(raf, data)) return null
        data.flip()
        val values = FloatArray(bins)
        for (i in 0 until bins) {
            val low = data.getShort().toInt()
            val high = data.getShort().toInt()
            values[i] = max(abs(low), abs(high)) / FULL_SCALE
        }
        val windowStart = (firstBin / binsPerSecond * 1_000_000.0).toLong()
        return Window(PeakEnvelope(binsPerSecond, values), windowStart)
    }

    private fun readFully(raf: RandomAccessFile, into: ByteBuffer): Boolean {
        val array = into.array()
        var read = 0
        while (read < array.size) {
            val n = raf.read(array, read, array.size - read)
            if (n < 0) return false
            read += n
        }
        into.position(array.size)
        return true
    }
}
