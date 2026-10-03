package com.ultimatevideo.uveditor.engine.export

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ultimatevideo.uveditor.data.model.MediaAssetDto
import com.ultimatevideo.uveditor.domain.Clip
import com.ultimatevideo.uveditor.domain.EditResult
import com.ultimatevideo.uveditor.domain.FrameIndex
import com.ultimatevideo.uveditor.domain.FrameRate
import com.ultimatevideo.uveditor.domain.SpeedRamps
import com.ultimatevideo.uveditor.domain.Timeline
import com.ultimatevideo.uveditor.domain.TimelineOps
import com.ultimatevideo.uveditor.domain.Track
import com.ultimatevideo.uveditor.domain.TrackType
import com.ultimatevideo.uveditor.domain.sourceSpan
import com.ultimatevideo.uveditor.domain.renderClips
import com.ultimatevideo.uveditor.domain.visualClipsAt
import com.ultimatevideo.uveditor.ui.export.buildExportPlan
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exports a retimed timeline (a freeze frame, a 2x clip, a reversed clip, a ramped clip and a slow-motion
 * clip) from a synthetic source whose every frame carries its own number in binary squares, and writes
 * the expected source frame of each output frame next to the MP4 so `scripts/check-retime-export.py` can compare
 * what was really rendered. Needs `retime_src.mp4` (made by that script) in the app's external files directory.
 */
@RunWith(AndroidJUnit4::class)
class RetimeExportInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun <T> EditResult<T>.unwrap(): T = when (this) {
        is EditResult.Success -> value
        is EditResult.Failure -> throw AssertionError("edit failed: $error")
    }

    private fun timeline(): Timeline {
        fun clip(id: String, start: Long, from: Long, to: Long) = Clip(id, "a", FrameIndex(start), FrameIndex(from), FrameIndex(to))
        var tl = Timeline(
            listOf(
                Track(
                    "v1", TrackType.VIDEO,
                    listOf(clip("A", 0, 0, 100), clip("B", 100, 100, 200), clip("C", 200, 200, 230), clip("D", 230, 230, 260), clip("E", 260, 260, 290)),
                ),
            ),
        )
        tl = TimelineOps.setSpeed(tl, "B", 2, 1, ripple = true).unwrap() // B 100..150: source 100..200 at 2x
        tl = TimelineOps.setReverse(tl, "C", true).unwrap() // C 150..180 plays source 229 down to 200
        tl = TimelineOps.setSpeedRamp(tl, "D", SpeedRamps.bell(30)).unwrap() // D 180..210, slow-fast-slow
        tl = TimelineOps.setSpeed(tl, "E", 1, 2, ripple = true).unwrap() // E 210..270 at half speed
        tl = TimelineOps.freezeFrame(tl, "v1", FrameIndex(40), 15, "FZ", "A2").unwrap() // hold frame 40 for 15 frames
        assertTrue(tl.invariantViolations().isEmpty())
        return tl
    }

    @Test
    fun exportsRetimedClipsWithTheFramesAndPitchTheDomainSays() = export(timeline(), "retime")

    /** One untouched 1x clip: every exported frame must be the source frame it should be, with no retiming involved. */
    @Test
    fun exportsAPlainClipFrameForFrame() {
        val clip = Clip("P", "a", FrameIndex(0), FrameIndex(0), FrameIndex(250))
        export(Timeline(listOf(Track("v1", TrackType.VIDEO, listOf(clip)))), "plain")
    }

    private fun export(tl: Timeline, prefix: String) {
        val dir = checkNotNull(context.getExternalFilesDir(null))
        val source = File(dir, "retime_src.mp4")
        assertTrue("push retime_src.mp4 to ${dir.path} first", source.exists())
        val output = File(dir, "${prefix}_out.mp4").also { it.delete() }

        val fps = FrameRate(30, 1)
        val assets = listOf(MediaAssetDto("a", source.toURI().toString(), 300, 30, 1, "Rec709-SDR", hasVideo = true, hasAudio = true))
        val plan = checkNotNull(buildExportPlan(tl, assets, fps))

        // What every output frame must show: the source frame of the topmost clip under it.
        val render = tl.renderClips()
        val expected = (0 until plan.projectFrames).map { frame -> visualClipsAt(render, frame).last().sourceFrameAt(frame) }
        File(dir, "${prefix}_expected.txt").writeText(expected.joinToString("\n"))
        // Clips with a steady pitch: id, first frame, last frame (exclusive), expected Hz (0 = silent, -1 = varies).
        val segments = tl.tracks.flatMap { it.clips }.joinToString("\n") { c ->
            val hz = when {
                c.id == "FZ" -> 0.0
                c.speedRamp.isNotEmpty() -> -1.0
                else -> 440.0 * c.sourceSpan / c.durationFrames
            }
            "${c.id} ${c.timelineStart.value} ${c.timelineEnd.value} $hz"
        }
        File(dir, "${prefix}_segments.txt").writeText(segments)

        val sourceFd = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).detachFd()
        val outputFd = ParcelFileDescriptor.open(
            output,
            ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE,
        ).detachFd()
        val request = ExportRequest(
            settings = ExportSettings(1280, 720, fps.num, fps.den, ExportCodec.H264, 8_000_000),
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
        handle.close()
        assertNull("export failed: ${failure?.message}", failure)
        assertNotNull(plan.audio)
        assertTrue("output is empty", output.length() > 10_000)
    }
}
