package com.qtekfun.ultimatevideoeditor.engine.export

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto
import com.qtekfun.ultimatevideoeditor.domain.Clip
import com.qtekfun.ultimatevideoeditor.domain.FrameIndex
import com.qtekfun.ultimatevideoeditor.domain.FrameRate
import com.qtekfun.ultimatevideoeditor.domain.Timeline
import com.qtekfun.ultimatevideoeditor.domain.Track
import com.qtekfun.ultimatevideoeditor.domain.TrackType
import com.qtekfun.ultimatevideoeditor.ui.export.buildExportPlan
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Measures export throughput on the device for the clips named by the `source` and `layers` instrumentation
 * arguments (see `scripts/run-export-throughput.sh`). The source `tp_src.mp4` is a 1080p30 clip pushed to the
 * app's external files directory; the result is logged as `UVPerf` and written to `tp_<name>_stats.txt`.
 */
@RunWith(AndroidJUnit4::class)
class ExportThroughputInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()

    @Test
    fun exportsAndReportsFramesPerSecond() {
        val name = args.getString("name") ?: "run"
        val layers = (args.getString("layers") ?: "1").toInt()
        val frames = (args.getString("frames") ?: "300").toLong()
        val dir = checkNotNull(context.getExternalFilesDir(null))
        val source = File(dir, args.getString("source") ?: "tp_src.mp4")
        assertTrue("push ${source.name} to ${dir.path} first", source.exists())
        val output = File(dir, "tp_${name}_out.mp4").also { it.delete() }

        // Layer n starts n * 40 frames into the same file, so each layer needs a decoder of its own.
        val tracks = (0 until layers).map { n ->
            val clip = Clip("c$n", "a", FrameIndex(0), FrameIndex(n * 40L), FrameIndex(n * 40L + frames))
            Track("v$n", TrackType.VIDEO, listOf(clip))
        }
        val fps = FrameRate(30, 1)
        val assets = listOf(MediaAssetDto("a", source.toURI().toString(), frames + layers * 40L, 30, 1, "Rec709-SDR", hasVideo = true, hasAudio = false))
        val plan = checkNotNull(buildExportPlan(Timeline(tracks), assets, fps))

        val sourceFd = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).detachFd()
        val outputFd = ParcelFileDescriptor.open(
            output,
            ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE,
        ).detachFd()
        val width = (args.getString("width") ?: "1920").toInt()
        val height = (args.getString("height") ?: "1080").toInt()
        val request = ExportRequest(
            settings = ExportSettings(width, height, fps.num, fps.den, ExportCodec.H264, 12_000_000),
            projectFpsNum = fps.num,
            projectFpsDen = fps.den,
            canvasWidth = width,
            canvasHeight = height,
            totalFrames = plan.projectFrames,
            assetFds = mapOf(plan.assetKeys.getValue("a") to sourceFd),
            videoClips = plan.videoClips,
            audioSnapshot = plan.audio?.encode(),
            outputFd = outputFd,
        )
        val done = CountDownLatch(1)
        var failure: ExportException? = null
        val started = SystemClock.elapsedRealtime()
        val handle = NativeExportRunner().start(
            request,
            object : ExportListener {
                override fun onProgress(permille: Int) = Unit

                override fun onFinished(error: ExportException?) {
                    failure = error
                    done.countDown()
                }
            },
        )
        val finished = done.await(6, TimeUnit.MINUTES)
        val seconds = (SystemClock.elapsedRealtime() - started) / 1000.0
        handle.close()
        val line = "name=$name layers=$layers frames=${plan.projectFrames} seconds=%.2f fps=%.1f realtime=%.2fx ok=%s".format(
            seconds,
            plan.projectFrames / seconds,
            plan.projectFrames / seconds / 30.0,
            finished && failure == null,
        )
        Log.i("UVPerf", line)
        File(dir, "tp_${name}_stats.txt").writeText(line + "\n")
        assertTrue("export did not finish in 6 minutes", finished)
        assertNull("export failed: ${failure?.message}", failure)
    }
}
