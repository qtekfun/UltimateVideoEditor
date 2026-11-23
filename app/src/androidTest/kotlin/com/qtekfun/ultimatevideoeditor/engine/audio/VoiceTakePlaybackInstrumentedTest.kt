package com.qtekfun.ultimatevideoeditor.engine.audio

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The voice-over take as the mixer plays it: a 16-bit mono 48 kHz WAV (what the recorder writes) whose pitch steps every 100 ms, so every
 * 100 ms of the file says where it is, put on the timeline with a source-in of 10 frames at 60 fps (the trim a take gets for the time
 * the microphone ran before the timeline). A user heard the start of the take repeated ("edge 70 fusion motorola edge fusion"); this
 * renders the clip offline and checks which moment of the file is heard at each moment of the timeline.
 */
@RunWith(AndroidJUnit4::class)
class VoiceTakePlaybackInstrumentedTest {
    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    private val openFds = mutableListOf<ParcelFileDescriptor>()
    private var engine: AudioPlaybackEngine? = null

    @Before
    fun setUp() {
        engine = AudioPlaybackEngine()
    }

    @After
    fun tearDown() {
        engine?.close()
        openFds.forEach { it.close() }
    }

    private fun stepFreq(step: Int) = 400.0 + 100.0 * step

    /** A mono 48 kHz WAV of [seconds] whose tone is [stepFreq] of the 100 ms step it is in. */
    private fun writeSteppedWav(name: String, seconds: Double): File {
        val frames = (seconds * RATE).toInt()
        val data = ByteBuffer.allocate(frames * 2).order(ByteOrder.LITTLE_ENDIAN)
        var phase = 0.0
        for (i in 0 until frames) {
            val step = i / (RATE / 10)
            phase += 2.0 * PI * stepFreq(step) / RATE
            data.putShort((sin(phase) * 0.5 * 32767).toInt().toShort())
        }
        val out = ByteArrayOutputStream()
        fun int(v: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        fun short(v: Int) = out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array())
        out.write("RIFF".toByteArray()); int(36 + frames * 2); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); int(16); short(1); short(1); int(RATE); int(RATE * 2); short(2); short(16)
        out.write("data".toByteArray()); int(frames * 2); out.write(data.array())
        return File(targetContext.cacheDir, name).also { it.writeBytes(out.toByteArray()) }
    }

    private fun amplitude(pcm: FloatArray, startFrame: Int, frames: Int, freq: Double): Double {
        val w = 2.0 * PI * freq / RATE
        var re = 0.0
        var im = 0.0
        for (i in 0 until frames) {
            val s = pcm[(startFrame + i) * 2].toDouble() * (0.5 - 0.5 * cos(2.0 * PI * i / frames))
            re += s * cos(w * i)
            im -= s * sin(w * i)
        }
        return 2.0 * sqrt(re * re + im * im) / frames / 0.5
    }

    private fun render(frames: Int): FloatArray {
        val out = FloatArray(frames * 2)
        val block = FloatArray(BLOCK * 2)
        var collected = 0
        var spins = 0
        while (collected < frames) {
            val pos = engine!!.renderOffline(block, BLOCK)
            if (pos > 0 && (collected > 0 || pos == BLOCK.toLong())) {
                val n = minOf(BLOCK, frames - collected)
                System.arraycopy(block, 0, out, collected * 2, n * 2)
                collected += n
            } else {
                assertTrue("engine never left its buffering hold", ++spins < 400)
                Thread.sleep(5)
            }
        }
        return out
    }

    /** Renders the take from timeline frame 0 and returns the list of wrong moments, empty when every moment is the right one. */
    private fun wrongMoments(sourceInFrames: Long, takeSeconds: Double, play: Int): List<String> {
        val engine = engine!!
        engine.startOffline()
        val file = writeSteppedWav("take-${sourceInFrames}.wav", takeSeconds)
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).also { openFds += it }
        engine.setAsset(1, pfd)
        val fps = 60
        val clipFrames = ((takeSeconds - sourceInFrames.toDouble() / fps) * fps).toLong() - 2
        engine.setSnapshot(AudioSnapshot(fps, 1, listOf(AudioClipSpec(1, 1, 0, clipFrames, sourceInFrames, fps, 1))))
        engine.play()
        val pcm = render(play * RATE)
        val trimMs = sourceInFrames * 1000.0 / fps
        val wrong = mutableListOf<String>()
        // A window of 40 ms in the middle of each 100 ms step of the file, skipping the first 150 ms for the codec's priming.
        var step = ((trimMs + 150) / 100).toInt() + 1
        while (true) {
            val sourceMs = step * 100 + 30.0
            val outMs = sourceMs - trimMs
            val start = (outMs * RATE / 1000).toInt()
            if (start + 1920 > play * RATE) break
            val heard = amplitude(pcm, start, 1920, stepFreq(step))
            if (heard < 0.3) {
                val other = (0 until (takeSeconds * 10).toInt()).maxBy { amplitude(pcm, start, 1920, stepFreq(it)) }
                wrong += "at ${outMs.toInt()} ms the file's ${step * 100}-${step * 100 + 100} ms should play, but the file's ${other * 100}-${other * 100 + 100} ms does"
            }
            step++
        }
        return wrong
    }

    @Test
    fun aTakeTrimmedByTenFramesPlaysTheRightMomentsOfTheFile() {
        val wrong = wrongMoments(sourceInFrames = 10, takeSeconds = 6.4, play = 6)
        assertTrue("wrong moments:\n" + wrong.joinToString("\n"), wrong.isEmpty())
    }

    @Test
    fun aTakeWithNoTrimPlaysTheRightMomentsOfTheFile() {
        val wrong = wrongMoments(sourceInFrames = 0, takeSeconds = 6.4, play = 6)
        assertTrue("wrong moments:\n" + wrong.joinToString("\n"), wrong.isEmpty())
    }

    private companion object {
        const val RATE = 48000
        const val BLOCK = 480
    }
}
