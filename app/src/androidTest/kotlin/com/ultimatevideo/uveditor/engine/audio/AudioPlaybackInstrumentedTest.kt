package com.ultimatevideo.uveditor.engine.audio

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device checks for the audio engine using synthetic clips: 2 s each of 440, 880 and 1320 Hz
 * (amplitude 0.5), generated with ffmpeg at 48 kHz and 44.1 kHz.
 *
 * The offline tests pull audio through the real decode -> resample -> mix path without a device
 * stream and verify the content with a Goertzel filter. [realStreamClockDriftAndLatency] drives
 * the actual Oboe stream for about a minute and logs latency and clock drift (tag UVAudioTest).
 */
@RunWith(AndroidJUnit4::class)
class AudioPlaybackInstrumentedTest {

    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext = InstrumentationRegistry.getInstrumentation().context
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

    private fun engine(): AudioPlaybackEngine = checkNotNull(engine)

    private fun registerAsset(assetKey: Long, assetName: String) {
        val file = File(targetContext.cacheDir, assetName)
        testContext.assets.open(assetName).use { input -> file.outputStream().use { input.copyTo(it) } }
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        openFds += pfd
        engine().setAsset(assetKey, pfd)
    }

    private fun tonesSnapshot(assetKey: Long): AudioSnapshot = AudioSnapshot(
        fpsNum = 30,
        fpsDen = 1,
        clips = listOf(
            AudioClipSpec(1, assetKey, 0, 60, 0, 30, 1),
            AudioClipSpec(2, assetKey, 60, 60, 60, 30, 1, gainDb = -6.0206f),
            AudioClipSpec(3, assetKey, 120, 60, 120, 30, 1),
        ),
    )

    /** Pulls [frames] audible stereo frames, waiting out the engine's buffering hold first. */
    private fun renderAudible(startSample: Long, frames: Int): FloatArray {
        val out = FloatArray(frames * 2)
        val block = FloatArray(BLOCK * 2)
        var collected = 0
        var spins = 0
        while (collected < frames) {
            val pos = engine().renderOffline(block, BLOCK)
            val audible = pos > startSample && (collected > 0 || pos == startSample + BLOCK)
            if (audible) {
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

    /** Estimated amplitude of a pure tone at [freq] in the left channel over [startFrame, startFrame + frames). */
    private fun toneAmplitude(pcm: FloatArray, startFrame: Int, frames: Int, freq: Double, rate: Int): Double {
        val w = 2.0 * PI * freq / rate
        var re = 0.0
        var im = 0.0
        for (i in 0 until frames) {
            val s = pcm[(startFrame + i) * 2].toDouble() * (0.5 - 0.5 * cos(2.0 * PI * i / frames)) // Hann window
            re += s * cos(w * i)
            im -= s * sin(w * i)
        }
        return 2.0 * sqrt(re * re + im * im) / frames / 0.5 // 0.5 = mean of the Hann window
    }

    private fun assertTone(pcm: FloatArray, fromFrame: Int, frames: Int, expectedHz: Double, expectedAmp: Double, label: String) {
        val others = listOf(440.0, 880.0, 1320.0).filter { it != expectedHz }
        val at = toneAmplitude(pcm, fromFrame, frames, expectedHz, RATE)
        assertEquals("$label: amplitude at $expectedHz Hz", expectedAmp, at, expectedAmp * 0.15)
        for (hz in others) {
            val leak = toneAmplitude(pcm, fromFrame, frames, hz, RATE)
            assertTrue("$label: unexpected $hz Hz energy $leak", leak < 0.03)
        }
    }

    private fun assertAllThreeTones(pcm: FloatArray, label: String) {
        // Windows stay 100 ms away from clip boundaries to ignore codec priming and edges.
        val win = RATE * 3 / 10
        assertTone(pcm, RATE / 10, win, 440.0, 0.5, "$label seg1")
        assertTone(pcm, 2 * RATE + RATE / 10, win, 880.0, 0.25, "$label seg2 (-6 dB)")
        assertTone(pcm, 4 * RATE + RATE / 10, win, 1320.0, 0.5, "$label seg3")
    }

    @Test
    fun offlineRenderPlacesSourceTonesAndAppliesGain() {
        engine().startOffline()
        registerAsset(1, "tones48k.m4a")
        engine().setSnapshot(tonesSnapshot(1))
        engine().play()
        val pcm = renderAudible(startSample = 0, frames = 6 * RATE)
        val faults = engine().pollFaults()
        assertTrue("faults: $faults", faults.isEmpty())
        assertEquals(0L, engine().stats().underrunBlocks)
        assertAllThreeTones(pcm, "48k")
    }

    @Test
    fun offlineRenderResamples44100To48000() {
        engine().startOffline()
        registerAsset(1, "tones44k.m4a")
        engine().setSnapshot(tonesSnapshot(1))
        engine().play()
        val pcm = renderAudible(startSample = 0, frames = 6 * RATE)
        val faults = engine().pollFaults()
        assertTrue("faults: $faults", faults.isEmpty())
        // A wrong rate would shift every tone by 8.8 %, far outside the narrow Goertzel bin.
        assertAllThreeTones(pcm, "44.1k->48k")
    }

    @Test
    fun seekPauseAndResumeLandOnTheRightAudio() {
        engine().startOffline()
        registerAsset(1, "tones48k.m4a")
        engine().setSnapshot(tonesSnapshot(1))

        engine().seek(150) // 5 s: inside the 1320 Hz segment
        engine().play()
        val seg3 = renderAudible(startSample = 150L * 1600, frames = RATE / 2)
        assertTone(seg3, RATE / 10, RATE / 5, 1320.0, 0.5, "after seek to 5 s")

        engine().seek(75) // 2.5 s: back into the 880 Hz segment, with gain
        val seg2 = renderAudible(startSample = 75L * 1600, frames = RATE / 2)
        assertTone(seg2, RATE / 10, RATE / 5, 880.0, 0.25, "after seek to 2.5 s")

        val heard = engine().positionSamples()
        engine().pause()
        val scratch = FloatArray(BLOCK * 2)
        repeat(3) { engine().renderOffline(scratch, BLOCK) } // let the audio thread apply the pause
        val frozen = engine().positionSamples()
        assertEquals("pause rewinds to the heard position", heard, frozen)
        repeat(5) { engine().renderOffline(scratch, BLOCK) }
        assertEquals("position must not move while paused", frozen, engine().positionSamples())
        assertTrue("paused output must be silent", scratch.all { it == 0f })
        assertEquals(frozen / 1600, engine().positionFrame())

        // Resuming continues exactly from the paused position.
        engine().play()
        val resumed = renderAudible(startSample = frozen, frames = RATE / 4)
        assertTone(resumed, RATE / 20, RATE / 10, 880.0, 0.25, "after resume")
    }

    @Test
    fun missingAssetIsReportedNotSwallowed() {
        engine().startOffline()
        engine().setSnapshot(tonesSnapshot(99)) // asset 99 was never registered
        engine().play()
        val block = FloatArray(BLOCK * 2)
        var decode: AudioFault.Decode? = null
        var attempts = 0
        while (decode == null && attempts++ < 200) {
            engine().renderOffline(block, BLOCK)
            decode = engine().pollFaults().filterIsInstance<AudioFault.Decode>().firstOrNull()
            if (decode == null) Thread.sleep(5)
        }
        assertNotNull("expected a Decode fault", decode)
        assertEquals(1L, decode?.clipKey)
        assertEquals(AudioErrorCode.InvalidArgument, decode?.error)
    }

    /**
     * Plays 60 s (ten 6 s clips) through the real Oboe stream, quietly (-30 dB), sampling the
     * master clock against CLOCK_MONOTONIC. Latency and drift are logged and loosely asserted.
     */
    @Test
    fun realStreamClockDriftAndLatency() {
        engine().start()
        registerAsset(1, "tones48k.m4a")
        val clips = (0 until 10).map { i ->
            AudioClipSpec(clipKey = i + 1L, assetKey = 1, startFrame = i * 180L, durationFrames = 180, sourceInFrame = 0,
                sourceFpsNum = 30, sourceFpsDen = 1, gainDb = -30f)
        }
        engine().setSnapshot(AudioSnapshot(30, 1, clips))
        engine().play()

        // Wait for the clock to start moving (buffering hold + stream start).
        val waitStart = SystemClock.elapsedRealtimeNanos()
        while (engine().positionSamples() == 0L) {
            assertTrue("clock never started", SystemClock.elapsedRealtimeNanos() - waitStart < 5_000_000_000L)
            Thread.sleep(5)
        }
        Thread.sleep(500) // let the hardware timestamps settle

        val stats0 = engine().stats()
        val rate = stats0.sampleRate
        val samples = ArrayList<Pair<Long, Long>>() // (monotonic ns, timeline sample)
        val runNs = 55_000_000_000L
        val t0 = System.nanoTime()
        while (System.nanoTime() - t0 < runNs) {
            samples += System.nanoTime() to engine().positionSamples()
            Thread.sleep(100)
        }
        samples += System.nanoTime() to engine().positionSamples()

        val (tFirst, pFirst) = samples.first()
        val (tLast, pLast) = samples.last()
        val elapsedS = (tLast - tFirst) / 1e9
        val advancedS = (pLast - pFirst).toDouble() / rate
        val driftMs = (advancedS - elapsedS) * 1000.0
        // Residual of every sample against the ideal line through the first point (clock jitter).
        val maxJitterMs = samples.maxOf { (t, p) ->
            abs((p - pFirst).toDouble() / rate - (t - tFirst) / 1e9) * 1000.0
        }
        val stats = engine().stats()
        val faults = engine().pollFaults()
        val report = "rate=$rate burst=${stats.framesPerBurst} buffer=${stats.bufferSizeFrames} exclusive=${stats.exclusive} " +
            "lowLatency=${stats.lowLatency} api=${stats.api} latencyMs=${stats.latencyMicros?.let { it / 1000.0 }} " +
            "xruns=${stats.xruns} underrunBlocks=${stats.underrunBlocks} elapsed=${"%.3f".format(elapsedS)}s " +
            "advanced=${"%.3f".format(advancedS)}s driftMs=${"%.2f".format(driftMs)} maxJitterMs=${"%.2f".format(maxJitterMs)} " +
            "samples=${samples.size} faults=$faults"
        Log.i(TAG, report)
        println("$TAG $report")

        engine().pause()
        assertTrue("faults: $faults", faults.isEmpty())
        assertEquals("app-side underruns", 0L, stats.underrunBlocks)
        assertTrue("clock drift ${driftMs}ms over ${elapsedS}s", abs(driftMs) < 25.0)
        stats.latencyMicros?.let { assertTrue("implausible output latency ${it}us", it in 0..300_000) }
    }

    private companion object {
        const val TAG = "UVAudioTest"
        const val RATE = 48000 // offline mode renders at 48 kHz
        const val BLOCK = 480
    }
}
