package com.ultimatevideo.uveditor.engine.export

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ultimatevideo.uveditor.data.model.MediaAssetDto
import com.ultimatevideo.uveditor.domain.Clip
import com.ultimatevideo.uveditor.domain.EditResult
import com.ultimatevideo.uveditor.domain.FrameIndex
import com.ultimatevideo.uveditor.domain.FrameRate
import com.ultimatevideo.uveditor.domain.Timeline
import com.ultimatevideo.uveditor.domain.TimelineOps
import com.ultimatevideo.uveditor.domain.Track
import com.ultimatevideo.uveditor.domain.TrackType
import com.ultimatevideo.uveditor.ui.export.buildExportPlan
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exports the same 60 fps clip at a quarter of its speed twice, once repeating frames and once with smooth slow motion
 * (optical-flow interpolation at the exporter's quality), and records how long each export took. A script
 * (`scripts/check-slowmo-export.sh`) then compares both with a 240 fps ground-truth render of the same synthetic motion.
 * Needs `slowmo_src.mp4` (60 fps, 1280x720, 60 frames) in the app's external files directory.
 */
@RunWith(AndroidJUnit4::class)
class SlowMotionExportInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun <T> EditResult<T>.unwrap(): T = when (this) {
        is EditResult.Success -> value
        is EditResult.Failure -> throw AssertionError("edit failed: $error")
    }

    /** Source frames 5 until 45 (40 frames) at 0.25x: 160 frames of the project's 60 fps. */
    private fun timeline(smooth: Boolean): Timeline {
        var tl = Timeline(
            listOf(Track("v1", TrackType.VIDEO, listOf(Clip("A", "a", FrameIndex(0), FrameIndex(5), FrameIndex(45))))),
        )
        tl = TimelineOps.setSpeed(tl, "A", 1, 4).unwrap()
        if (smooth) tl = TimelineOps.setSmoothSlowMo(tl, "A", true).unwrap()
        return tl
    }

    @Test
    fun exportsAQuarterSpeedClipWithAndWithoutInterpolation() {
        val dir = checkNotNull(context.getExternalFilesDir(null))
        val source = File(dir, "slowmo_src.mp4")
        assertTrue("push slowmo_src.mp4 to ${dir.path} first", source.exists())
        val timings = StringBuilder()
        for ((name, smooth) in listOf("slowmo_off" to false, "slowmo_on" to true)) {
            val millis = export(timeline(smooth), source, File(dir, "${name}_out.mp4"))
            timings.append("$name $millis\n")
        }
        File(dir, "slowmo_times.txt").writeText(timings.toString())
    }

    private fun export(tl: Timeline, source: File, output: File): Long {
        output.delete()
        val fps = FrameRate(60, 1)
        val assets = listOf(MediaAssetDto("a", source.toURI().toString(), 60, 60, 1, "Rec709-SDR", hasVideo = true, hasAudio = false))
        val plan = checkNotNull(buildExportPlan(tl, assets, fps))
        val sourceFd = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).detachFd()
        val outputFd = ParcelFileDescriptor.open(
            output,
            ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE,
        ).detachFd()
        val request = ExportRequest(
            settings = ExportSettings(1280, 720, fps.num, fps.den, ExportCodec.H264, 24_000_000),
            projectFpsNum = fps.num,
            projectFpsDen = fps.den,
            canvasWidth = 1280,
            canvasHeight = 720,
            totalFrames = plan.projectFrames,
            assetFds = mapOf(plan.assetKeys.getValue("a") to sourceFd),
            videoClips = plan.videoClips,
            audioSnapshot = plan.audio?.encode(),
            outputFd = outputFd,
        )
        val done = CountDownLatch(1)
        var failure: ExportException? = null
        val began = System.nanoTime()
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
        assertTrue("export did not finish in 4 minutes", done.await(4, TimeUnit.MINUTES))
        val millis = (System.nanoTime() - began) / 1_000_000
        handle.close()
        assertNull("export failed: ${failure?.message}", failure)
        assertTrue("output is empty", output.length() > 10_000)
        return millis
    }
}
