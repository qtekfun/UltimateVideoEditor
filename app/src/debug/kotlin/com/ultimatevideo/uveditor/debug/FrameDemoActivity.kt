package com.ultimatevideo.uveditor.debug

import android.app.Activity
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import com.ultimatevideo.uveditor.data.model.MediaAssetDto
import com.ultimatevideo.uveditor.domain.Clip
import com.ultimatevideo.uveditor.domain.FrameIndex
import com.ultimatevideo.uveditor.domain.FrameRate
import com.ultimatevideo.uveditor.domain.Timeline
import com.ultimatevideo.uveditor.domain.Track
import com.ultimatevideo.uveditor.domain.TrackType
import com.ultimatevideo.uveditor.domain.stillframe.FrameFit
import com.ultimatevideo.uveditor.domain.stillframe.FrameFormat
import com.ultimatevideo.uveditor.domain.stillframe.FrameSize
import com.ultimatevideo.uveditor.domain.stillframe.planFrameRender
import com.ultimatevideo.uveditor.engine.export.ExportCodec
import com.ultimatevideo.uveditor.engine.export.ExportException
import com.ultimatevideo.uveditor.engine.export.ExportListener
import com.ultimatevideo.uveditor.engine.export.ExportRequest
import com.ultimatevideo.uveditor.engine.export.ExportSettings
import com.ultimatevideo.uveditor.engine.export.NativeExportRunner
import com.ultimatevideo.uveditor.engine.still.AndroidStillRasterizer
import com.ultimatevideo.uveditor.engine.title.AndroidTitleRasterizer
import com.ultimatevideo.uveditor.ui.export.ContentResolverExportIO
import com.ultimatevideo.uveditor.ui.export.buildExportPlan
import com.ultimatevideo.uveditor.ui.frame.BitmapFrameEncoder
import com.ultimatevideo.uveditor.ui.frame.FrameRenderJob
import com.ultimatevideo.uveditor.ui.frame.NativeFrameRenderer
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.CountDownLatch

/**
 * Debug-only harness for "Save frame as image" without UI (SPECS 5.35). It builds a three clip project from one video file
 * (frame-numbered source from scripts/check-retime-export.py: a plain clip starting mid-GOP, a reversed clip and a 2x clip),
 * and either saves single frames through the same renderer the editor uses, or exports the same project to an MP4 so the
 * frames can be compared:
 *
 *   adb shell am start -n <pkg>/com.ultimatevideo.uveditor.debug.FrameDemoActivity --es video <in.mp4> --es mode frames \
 *     --es frames 0,37,59,60,90,119,120,130,149,150 [--es size 1280x720] [--es fit letterbox|fill] [--es format png|jpg] \
 *     [--ei quality 92] [--ei limit <bytes>] [--es colour hlg]
 *   ... --es mode export        writes <dir>/frame_export.mp4 (all 150 frames)
 *
 * Output goes to the app's external files directory; the log (tag UVFrameDemo) and `<dir>/frame_result.txt` list, for every
 * frame, the file, its size in bytes and the frame number read back from the picture's binary squares.
 */
class FrameDemoActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val video = intent.getStringExtra("video") ?: return finishWith("missing --es video")
        val mode = intent.getStringExtra("mode") ?: "frames"
        val dir = getExternalFilesDir(null) ?: return finishWith("no external files dir")
        Thread {
            val report = StringBuilder()
            try {
                if (mode == "export") exportMovie(video, dir, report) else saveFrames(video, dir, report)
            } catch (e: Exception) {
                report.append("FAILED ${e.javaClass.simpleName}: ${e.message}\n")
                Log.e(TAG, "failed", e)
            }
            File(dir, "frame_result.txt").writeText(report.toString())
            Log.i(TAG, report.toString())
            runOnUiThread { finish() }
        }.start()
    }

    private fun project(video: String): Triple<Timeline, List<MediaAssetDto>, FrameRate> {
        val hlg = intent.getStringExtra("colour") == "hlg"
        val asset = MediaAssetDto("a", Uri.fromFile(File(video)).toString(), 300, 30, 1, if (hlg) "Rec2020-HLG" else "Rec709-SDR")
        fun clip(id: String, start: Long, sourceIn: Long, sourceOut: Long, retimed: Long? = null, reverse: Boolean = false) =
            Clip(id, "a", FrameIndex(start), FrameIndex(sourceIn), FrameIndex(sourceOut), retimedFrames = retimed, reverse = reverse)
        val track = Track(
            "v1",
            TrackType.VIDEO,
            listOf(
                clip("c1", 0, 100, 160), // plain, source frame 100 + f: starts in the middle of a 30 frame GOP
                clip("c2", 60, 10, 70, reverse = true), // reversed: shows 69, 68, ...
                clip("c3", 120, 150, 210, retimed = 30), // 2x: 150, 152, ...
            ),
        )
        return Triple(Timeline(listOf(track)), listOf(asset), FrameRate(30, 1))
    }

    private fun saveFrames(video: String, dir: File, report: StringBuilder) {
        val (timeline, assets, fps) = project(video)
        val frames = (intent.getStringExtra("frames") ?: "0").split(",").map { it.trim().toLong() }
        val size = (intent.getStringExtra("size") ?: "1280x720").split("x").let { FrameSize(it[0].toInt(), it[1].toInt()) }
        val fit = if (intent.getStringExtra("fit") == "fill") FrameFit.FILL else FrameFit.LETTERBOX
        val format = if (intent.getStringExtra("format") == "jpg") FrameFormat.JPEG else FrameFormat.PNG
        val quality = intent.getIntExtra("quality", 92)
        val plan = planFrameRender(size, 1280, 720, fit)
        report.append("plan $plan\n")
        val app = applicationContext
        val renderer = NativeFrameRenderer(
            ContentResolverExportIO(app),
            NativeExportRunner(),
            AndroidTitleRasterizer(),
            AndroidStillRasterizer(app),
        )
        val encoder = BitmapFrameEncoder()
        for (frame in frames) {
            val started = System.nanoTime()
            val rendered = runBlocking { renderer.render(FrameRenderJob(timeline, assets, fps, 1280, 720, frame, plan, emptySet())) }
            val drawn = (System.nanoTime() - started) / 1_000_000
            val limit = if (intent.hasExtra("limit")) intent.getIntExtra("limit", 0).toLong() else null
            val encoded = encoder.encode(rendered, format, quality, limit)
            val file = File(dir, "frame_$frame.${format.extension}")
            file.writeBytes(encoded.bytes)
            val number = if (rendered.width == 1280 && rendered.height == 720) frameNumberOf(file) else -1
            report.append("frame $frame -> ${file.name} ${rendered.width}x${rendered.height} ${encoded.bytes.size} bytes q=${encoded.quality} fits=${encoded.fitsLimit} number=$number drew=${drawn}ms\n")
        }
    }

    private fun frameNumberOf(file: File): Int {
        val bitmap = BitmapFactory.decodeFile(file.path) ?: return -2
        var n = 0
        for (bit in 0 until 9) {
            val pixel = bitmap.getPixel(bit * 100 + 50, 50)
            val luma = (android.graphics.Color.red(pixel) + android.graphics.Color.green(pixel) + android.graphics.Color.blue(pixel)) / 3
            if (luma > 128) n = n or (1 shl bit)
        }
        return n
    }

    private fun exportMovie(video: String, dir: File, report: StringBuilder) {
        val (timeline, assets, fps) = project(video)
        val plan = checkNotNull(buildExportPlan(timeline, assets, fps, 1280, 720))
        val out = File(dir, "frame_export.mp4").also { it.delete() }
        val input = ParcelFileDescriptor.open(File(video), ParcelFileDescriptor.MODE_READ_ONLY).detachFd()
        val output = ParcelFileDescriptor.open(
            out,
            ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE,
        ).detachFd()
        val request = ExportRequest(
            settings = ExportSettings(1280, 720, 30, 1, ExportCodec.H264, 20_000_000),
            projectFpsNum = 30,
            projectFpsDen = 1,
            canvasWidth = 1280,
            canvasHeight = 720,
            totalFrames = plan.projectFrames,
            assetFds = mapOf(plan.assetKeys.getValue("a") to input),
            videoClips = plan.videoClips,
            audioSnapshot = null,
            outputFd = output,
        )
        val done = CountDownLatch(1)
        var outcome = "unknown"
        val handle = NativeExportRunner().start(
            request,
            object : ExportListener {
                override fun onProgress(permille: Int) = Unit

                override fun onFinished(error: ExportException?) {
                    outcome = if (error == null) "OK" else "ERROR ${error.code}: ${error.message}"
                    done.countDown()
                }
            },
        )
        done.await()
        handle.close()
        report.append("export $outcome ${out.length()} bytes\n")
    }

    private fun finishWith(message: String) {
        Log.e(TAG, message)
        finish()
    }

    private companion object {
        const val TAG = "UVFrameDemo"
    }
}
