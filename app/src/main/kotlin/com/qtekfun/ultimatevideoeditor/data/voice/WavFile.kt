package com.qtekfun.ultimatevideoeditor.data.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The 44-byte header of a PCM WAV file, and the arithmetic of the sizes it carries. Pure. */
object WavHeader {
    const val SIZE = 44

    /** The header for [dataBytes] bytes of 16-bit PCM. A length that does not fit in a WAV (4 GiB) is cut to what does. */
    fun build(sampleRate: Int, channels: Int, dataBytes: Long): ByteArray {
        val bitsPerSample = 16
        val data = dataBytes.coerceIn(0, MAX_DATA).toInt()
        val blockAlign = channels * bitsPerSample / 8
        return ByteBuffer.allocate(SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + data)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1) // PCM
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(sampleRate * blockAlign)
            putShort(blockAlign.toShort())
            putShort(bitsPerSample.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(data)
        }.array()
    }

    /** How long [dataBytes] of 16-bit PCM last, in milliseconds. */
    fun durationMs(dataBytes: Long, sampleRate: Int, channels: Int): Long =
        if (sampleRate <= 0 || channels <= 0) 0 else dataBytes * 1000 / (sampleRate.toLong() * channels * 2)

    /** The most data a WAV can say it holds. */
    const val MAX_DATA: Long = 0x7FFF_FFFFL - 36
}

/** The loudness of a buffer of 16-bit samples as 0..1 (the highest peak), for the meter. */
object PeakMeter {
    fun peakOf(samples: ShortArray, count: Int): Float {
        var peak = 0
        for (i in 0 until count.coerceAtMost(samples.size)) {
            val v = samples[i].toInt()
            val magnitude = if (v == Short.MIN_VALUE.toInt()) 32768 else Math.abs(v)
            if (magnitude > peak) peak = magnitude
        }
        return peak / 32768f
    }
}
