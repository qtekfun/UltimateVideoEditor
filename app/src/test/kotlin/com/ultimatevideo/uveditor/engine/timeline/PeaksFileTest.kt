package com.ultimatevideo.uveditor.engine.timeline

import com.ultimatevideo.uveditor.domain.beat.BeatDetector
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import org.junit.After
import org.junit.Assert.assertEquals

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeaksFileTest {
    private val dir = kotlin.io.path.createTempDirectory("uv-peaks").toFile()

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun <T : Any> present(value: T?): T = checkNotNull(value) { "expected a value" }

    /**
     * A "UVPK" file with one level: clicks every 60/bpm seconds starting at [first]. Version 2 has the RMS block after the
     * level's min/max pairs, as the native writer does.
     */
    private fun write(
        name: String,
        seconds: Double,
        bpm: Double,
        first: Double,
        magic: Int = 0x4B505655,
        cut: Int = 0,
        version: Int = 1,
        fineLevel: Boolean = false,
    ): File {
        val sampleRate = 48_000
        val samplesPerPeak = 64
        val count = (seconds * sampleRate / samplesPerPeak).toInt()
        val period = 60.0 / bpm
        val rmsBytes = if (version >= 2) count * 2 else 0
        val fineCount = if (fineLevel) count * 4 else 0
        val fineBytes = if (fineLevel) 8 + fineCount * 4 else 0
        val buffer = ByteBuffer.allocate(24 + fineBytes + 8 + count * 4 + rmsBytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(magic).putInt(version).putInt(sampleRate)
        buffer.putLong((seconds * sampleRate).toLong())
        buffer.putInt(if (fineLevel) 2 else 1)
        if (fineLevel) {
            // The 16 samples per peak level the native writer puts first: pairs only, no RMS. Loud, so reading it by mistake shows.
            buffer.putInt(16).putInt(fineCount)
            repeat(fineCount) { buffer.putShort(-30000).putShort(30000) }
        }
        buffer.putInt(samplesPerPeak).putInt(count)
        for (i in 0 until count) {
            val t = i * samplesPerPeak.toDouble() / sampleRate
            val since = if (t < first) Double.MAX_VALUE else (t - first) % period
            val amplitude = ((0.02 + 0.9 * exp(-since / 0.05)) * 32767).toInt().coerceAtMost(32767)
            buffer.putShort((-amplitude).toShort()).putShort(amplitude.toShort())
        }
        if (version >= 2) repeat(count) { buffer.putShort(1000) }
        val file = File(dir, name)
        file.writeBytes(buffer.array().copyOf(buffer.capacity() - cut))
        return file
    }

    @Test
    fun `a whole file reads as an envelope and its beats are found`() {
        val window = present(PeaksFile.readWindow(write("a.peaks", 30.0, 120.0, 0.25), 0, 30_000_000))
        assertEquals(750.0, window.envelope.binsPerSecond, 1e-9)
        assertEquals(0L, window.startMicros)
        assertEquals(30.0, window.envelope.durationSeconds, 0.05)
        val grid = present(BeatDetector.analyze(window.envelope))
        assertEquals(120.0, grid.bpm, 2.0)
        assertEquals(250_000.0, grid.beatsMicros.first().toDouble(), 25_000.0)
    }

    @Test
    fun `a version 2 file with the rms block reads the same as version 1`() {
        val v1 = present(PeaksFile.readWindow(write("g1.peaks", 30.0, 120.0, 0.25), 0, 30_000_000))
        val v2 = present(PeaksFile.readWindow(write("g2.peaks", 30.0, 120.0, 0.25, version = 2), 0, 30_000_000))
        assertEquals(v1.envelope.binsPerSecond, v2.envelope.binsPerSecond, 1e-9)
        assertEquals(v1.envelope.durationSeconds, v2.envelope.durationSeconds, 1e-9)
        assertEquals(120.0, present(BeatDetector.analyze(v2.envelope)).bpm, 2.0)
        // The 16 sample level in front is skipped: the envelope is the 64 sample one, as before the finer level existed.
        val fine = present(PeaksFile.readWindow(write("g4.peaks", 30.0, 120.0, 0.25, version = 2, fineLevel = true), 0, 30_000_000))
        assertEquals(750.0, fine.envelope.binsPerSecond, 1e-9)
        assertEquals(120.0, present(BeatDetector.analyze(fine.envelope)).bpm, 2.0)
        // A file from a future version is not guessed at.
        assertNull(PeaksFile.readWindow(write("g3.peaks", 10.0, 120.0, 0.0, version = 3), 0, 10_000_000))
    }

    @Test
    fun `a window reads only its part and reports where it starts`() {
        val window = present(PeaksFile.readWindow(write("b.peaks", 40.0, 90.0, 0.13), 10_000_000, 30_000_000))
        assertTrue(abs(window.startMicros - 10_000_000L) < 2_000)
        assertEquals(20.0, window.envelope.durationSeconds, 0.05)
        val grid = present(BeatDetector.analyze(window.envelope))
        assertEquals(90.0, grid.bpm, 2.0)
        // Absolute beat times are the window's start plus the grid's: they stay on the 90 BPM grid of the file.
        val period = 60_000_000L / 90
        for (micros in grid.beatsMicros) {
            val absolute = window.startMicros + micros
            val phase = ((absolute - 130_000L) % period + period) % period
            val offGrid = minOf(phase, period - phase)
            assertTrue("beat at $absolute us is $offGrid us off the grid", offGrid < 30_000L)
        }
    }

    @Test
    fun `a window is clamped to what the file holds`() {
        val file = write("c.peaks", 10.0, 120.0, 0.0)
        val window = present(PeaksFile.readWindow(file, 5_000_000, 60_000_000))
        assertEquals(5.0, window.envelope.durationSeconds, 0.05)
        assertNull(PeaksFile.readWindow(file, 20_000_000, 30_000_000))
        assertNull(PeaksFile.readWindow(file, 5_000_000, 5_000_000))
    }

    @Test
    fun `missing, foreign and truncated files read as nothing`() {
        assertNull(PeaksFile.readWindow(File(dir, "none.peaks"), 0, 10_000_000))
        assertNull(PeaksFile.readWindow(write("d.peaks", 10.0, 120.0, 0.0, magic = 0x12345678), 0, 10_000_000))
        assertNull(PeaksFile.readWindow(write("e.peaks", 10.0, 120.0, 0.0, cut = 400), 0, 10_000_000))
        assertNull(PeaksFile.readWindow(File(dir, "f.peaks").also { it.writeBytes(ByteArray(5)) }, 0, 10_000_000))
    }
}
