package com.qtekfun.ultimatevideoeditor.proxy

import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.qtekfun.ultimatevideoeditor.engine.export.NativeExportRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Makes a real proxy of a real file on the device with the real engine. The source is given by the
 * instrumentation argument `proxySource` (a file the app can read); without it the test is skipped.
 * The frame rate and length come from `proxyFrames` and `proxyFpsNum` (defaults: 240 frames at 30).
 */
@RunWith(AndroidJUnit4::class)
class ProxyGenerationInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val args = InstrumentationRegistry.getArguments()

    @Test
    fun aProxyOfAHeavyVideoKeepsFrameRateAndLengthAndHasNoAudio() {
        val sourcePath = args.getString("proxySource")
        assumeTrue("no proxySource argument", sourcePath != null)
        val source = File(checkNotNull(sourcePath))
        assertTrue("source not readable: $source", source.canRead())
        val frames = args.getString("proxyFrames")?.toLong() ?: 240L
        val fpsNum = args.getString("proxyFpsNum")?.toInt() ?: 30

        val dir = File(context.cacheDir, "proxy-test-${System.nanoTime()}")
        val index = ProxyIndex(dir)
        val media = AndroidProxyMediaAccess(context)
        val generator = ProxyGenerator(NativeExportRunner(), media, index)
        val job = ProxyJob(android.net.Uri.fromFile(source).toString(), frames, fpsNum, 1, "Rec709-SDR")
        val entry = ProxyEntry(ProxyKeys.of(job, 720), job, ProxyState.QUEUED, 720)
        index.put(entry)

        val started = System.nanoTime()
        var lastPermille = 0
        val ready = generator.generate(entry) { lastPermille = it }
        val seconds = (System.nanoTime() - started) / 1e9

        Log.i("ProxyTest", "proxy made in %.1f s for %d frames (%d permille at last report), %d bytes".format(seconds, frames, lastPermille, ready.bytes))
        assertEquals(ProxyState.READY, ready.state)
        val file = checkNotNull(index.fileOf(ready))
        assertTrue(file.length() > 0)
        assertFalse(index.partFileFor(entry.key).exists())

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val formats = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
            val video = formats.single { it.getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
            assertEquals("a proxy has no audio track", 1, formats.size)
            assertEquals(1280, video.getInteger(MediaFormat.KEY_WIDTH))
            assertEquals(720, video.getInteger(MediaFormat.KEY_HEIGHT))
            // Count the frames: the length and the rate must be the source's, so the mapping is identical.
            extractor.selectTrack(formats.indexOf(video))
            var count = 0
            var lastTimeUs = 0L
            while (extractor.sampleTime >= 0) {
                lastTimeUs = extractor.sampleTime
                count++
                extractor.advance()
            }
            Log.i("ProxyTest", "proxy has $count frames, last pts $lastTimeUs us")
            assertEquals("same frame count as the source", frames, count.toLong())
            val expectedLastUs = (frames - 1) * 1_000_000L / fpsNum
            assertTrue("last pts $lastTimeUs vs $expectedLastUs", kotlin.math.abs(lastTimeUs - expectedLastUs) <= 1_000_000L / fpsNum)
        } finally {
            extractor.release()
            dir.deleteRecursively()
        }
    }
}
