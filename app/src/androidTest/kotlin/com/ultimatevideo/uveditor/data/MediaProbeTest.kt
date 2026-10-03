package com.ultimatevideo.uveditor.data

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Probes a clip pushed to the app's external files dir:
 * `adb push editor_clip.mp4 /sdcard/Android/data/com.ultimatevideo.uveditor/files/`
 * (10 s, 30 fps, H.264 + AAC). Skipped when the file is absent.
 */
@RunWith(AndroidJUnit4::class)
class MediaProbeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun clip(): File? = context.getExternalFilesDir(null)?.let { File(it, "editor_clip.mp4") }?.takeIf { it.isFile }

    @Test
    fun probesDurationFrameRateAndTracks() {
        val file = clip()
        assumeTrue("editor_clip.mp4 not pushed to the app files dir", file != null)

        val probed = AndroidMediaImporter(context).probe(Uri.fromFile(file))

        assertEquals(10_000_000.0, probed.durationMicros.toDouble(), 100_000.0)
        assertEquals(30 to 1, probed.fpsNum to probed.fpsDen)
        assertTrue(probed.hasVideo)
        assertTrue(probed.hasAudio)
        assertEquals("Rec709-SDR", probed.colorSpace)
    }

    @Test
    fun unreadableFileIsReportedNotSwallowed() {
        val missing = Uri.fromFile(File(context.cacheDir, "does-not-exist.mp4"))

        try {
            AndroidMediaImporter(context).probe(missing)
            throw AssertionError("expected MediaImportException")
        } catch (e: MediaImportException) {
            assertTrue(e.message.orEmpty().isNotBlank())
        }
    }
}
