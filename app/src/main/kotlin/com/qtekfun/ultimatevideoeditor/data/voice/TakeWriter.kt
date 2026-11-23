package com.qtekfun.ultimatevideoeditor.data.voice

/**
 * Turns what the microphone delivers into the bytes of a take: little-endian 16-bit PCM handed to [sink], counting what is kept. While
 * [paused] the sound is read from the microphone but dropped, so the take holds only recorded time and [elapsedMs] holds still. Pure
 * apart from the sink, so the pause and the clock are tested on the JVM; the microphone thread writes, the others read.
 */
class TakeWriter(private val sampleRate: Int, private val channels: Int, private val sink: (ByteArray, Int) -> Unit) {
    @Volatile
    var paused = false

    /** The loudest recent sound, 0..1; 0 while paused. */
    @Volatile
    var peak = 0f
        private set

    /** The bytes of sound kept so far. */
    @Volatile
    var bytes = 0L
        private set

    val elapsedMs: Long get() = WavHeader.durationMs(bytes, sampleRate, channels)

    private var raw = ByteArray(0)

    /** Takes [count] samples; returns whether they went into the take. @throws java.io.IOException from the sink. */
    fun write(samples: ShortArray, count: Int): Boolean {
        if (paused) {
            peak = 0f
            return false
        }
        val n = count.coerceAtMost(samples.size)
        peak = PeakMeter.peakOf(samples, n)
        if (raw.size < n * 2) raw = ByteArray(n * 2)
        var i = 0
        for (s in 0 until n) {
            raw[i++] = (samples[s].toInt() and 0xFF).toByte()
            raw[i++] = ((samples[s].toInt() shr 8) and 0xFF).toByte()
        }
        sink(raw, n * 2)
        bytes += n * 2L
        return true
    }

    /** The meter goes quiet (the microphone stopped). */
    fun silence() {
        peak = 0f
    }
}
