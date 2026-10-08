package com.qtekfun.ultimatevideoeditor.debug

import android.app.Activity
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import com.qtekfun.ultimatevideoeditor.domain.ClipFx
import com.qtekfun.ultimatevideoeditor.domain.StabCrop
import com.qtekfun.ultimatevideoeditor.domain.StabKey
import com.qtekfun.ultimatevideoeditor.domain.Stabilise
import com.qtekfun.ultimatevideoeditor.engine.export.ExportCodec
import com.qtekfun.ultimatevideoeditor.engine.export.ExportException
import com.qtekfun.ultimatevideoeditor.engine.export.ExportListener
import com.qtekfun.ultimatevideoeditor.engine.export.ExportRequest
import com.qtekfun.ultimatevideoeditor.engine.export.ExportSettings
import com.qtekfun.ultimatevideoeditor.engine.export.NativeExportRunner
import com.qtekfun.ultimatevideoeditor.engine.export.VideoClipSpec
import com.qtekfun.ultimatevideoeditor.engine.stabilise.JniStabNative
import com.qtekfun.ultimatevideoeditor.engine.stabilise.StabCacheFile
import com.qtekfun.ultimatevideoeditor.engine.stabilise.StabJob
import java.io.File
import java.util.concurrent.CountDownLatch

/**
 * Debug-only harness for the stabiliser, without any UI: analyses a video on the device, then exports it with and
 * without the correction so the two files can be compared on a computer.
 *
 *   adb shell am start -n com.qtekfun.ultimatevideoeditor/.debug.StabiliseDemoActivity \
 *     --es video /sdcard/Android/data/com.qtekfun.ultimatevideoeditor/files/shaky.mp4 \
 *     --es out /sdcard/Android/data/com.qtekfun.ultimatevideoeditor/files/stab \
 *     --ef strength 0.5 --ei crop 1 --ei frames 150 --ei w 1280 --ei h 720 --ei fps 30
 *
 * Writes `<out>_on.mp4` (stabilised), `<out>_off.mp4` (plain), the analysis cache `<out>.cache` and `<out>.result.txt`
 * (analysis time, frames analysed, export times). Frames are numbered at --ei fps, the same as the preview decoder.
 */
class StabiliseDemoActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val video = intent.getStringExtra("video") ?: return finishWith("missing --es video")
        val outBase = intent.getStringExtra("out") ?: return finishWith("missing --es out")
        val width = intent.getIntExtra("w", 1280)
        val height = intent.getIntExtra("h", 720)
        val fps = intent.getIntExtra("fps", 30)
        val frames = intent.getIntExtra("frames", 150).toLong()
        val strength = intent.getFloatExtra("strength", 0.5f).toDouble()
        val crop = StabCrop.entries.first { it.code == intent.getIntExtra("crop", StabCrop.MEDIUM.code) }
        val stabilise = Stabilise(strength, crop)

        Thread {
            val report = StringBuilder()
            try {
                val cache = File("$outBase.cache")
                cache.delete()
                // 1. Analyse the whole file.
                val analysisStart = System.nanoTime()
                val fd = ParcelFileDescriptor.open(File(video), ParcelFileDescriptor.MODE_READ_ONLY).detachFd()
                val handle = JniStabNative.create()
                val started = JniStabNative.start(handle, fd, 0L, 0L, cache.path)
                report.appendLine("analysis start status $started")
                var job = StabJob.unpack(JniStabNative.poll(handle))
                while (job.state == StabJob.State.RUNNING || job.state == StabJob.State.IDLE) {
                    Thread.sleep(100)
                    job = StabJob.unpack(JniStabNative.poll(handle))
                }
                JniStabNative.destroy(handle)
                val analysisSeconds = (System.nanoTime() - analysisStart) / 1e9
                val header = StabCacheFile.readHeader(cache)
                report.appendLine("analysis ${job.state} error ${job.errorCode} in %.2f s, header $header".format(analysisSeconds))
                if (job.state != StabJob.State.DONE || header == null) throw IllegalStateException("analysis failed")

                // 2. Register the table under the key the render plan would use.
                val key = StabKey.of("demo", stabilise)
                val registered = JniStabNative.register(key, cache.path, fps, 1, strength.toFloat(), crop.code)
                report.appendLine("register key $key status $registered strength $strength crop ${crop.label}")

                // 3. Export twice: plain and stabilised.
                for ((suffix, fx) in listOf("off" to ClipFx.NONE, "on" to ClipFx(stabKey = key))) {
                    val out = File("${outBase}_$suffix.mp4")
                    val seconds = export(video, out, width, height, fps, frames, fx)
                    report.appendLine("export $suffix: $seconds, ${out.length()} bytes")
                }
                JniStabNative.releaseAll()
            } catch (e: Exception) {
                report.appendLine("FAILED: $e")
                Log.e(TAG, "stabilise demo failed", e)
            }
            File("$outBase.result.txt").writeText(report.toString())
            Log.i(TAG, report.toString())
            runOnUiThread { finish() }
        }.start()
    }

    private fun export(video: String, out: File, width: Int, height: Int, fps: Int, frames: Long, fx: ClipFx): String {
        val done = CountDownLatch(1)
        var outcome = "unknown"
        val started = System.nanoTime()
        val input = ParcelFileDescriptor.open(File(video), ParcelFileDescriptor.MODE_READ_ONLY).detachFd()
        out.delete()
        val output = ParcelFileDescriptor.open(
            out,
            ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE,
        ).detachFd()
        val request = ExportRequest(
            settings = ExportSettings(width, height, fps, 1, ExportCodec.H264, 20_000_000),
            projectFpsNum = fps,
            projectFpsDen = 1,
            canvasWidth = width,
            canvasHeight = height,
            totalFrames = frames,
            assetFds = mapOf(0L to input),
            videoClips = listOf(VideoClipSpec(0, frames, 0, 0, 0, 0, fx = fx)),
            audioSnapshot = null,
            outputFd = output,
        )
        try {
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
        } catch (e: ExportException) {
            outcome = "START FAILED ${e.code}: ${e.message}"
        }
        return "$outcome in %.2f s".format((System.nanoTime() - started) / 1e9)
    }

    private fun finishWith(message: String) {
        Log.e(TAG, message)
        finish()
    }

    private companion object {
        const val TAG = "StabiliseDemo"
    }
}
