package com.ultimatevideo.uveditor.debug

import android.app.Activity
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import com.ultimatevideo.uveditor.engine.audio.AudioClipSpec
import com.ultimatevideo.uveditor.engine.audio.AudioSnapshot
import com.ultimatevideo.uveditor.engine.export.ExportCodec
import com.ultimatevideo.uveditor.engine.export.ExportException
import com.ultimatevideo.uveditor.engine.export.ExportListener
import com.ultimatevideo.uveditor.engine.export.ExportRequest
import com.ultimatevideo.uveditor.engine.export.ExportSettings
import com.ultimatevideo.uveditor.engine.export.NativeExportRunner
import com.ultimatevideo.uveditor.engine.export.VideoClipSpec
import java.io.File
import java.util.concurrent.CountDownLatch

/**
 * Debug-only harness that exports a video file through the native pipeline without any UI:
 *
 *   adb shell am start -n com.ultimatevideo.uveditor/.debug.ExportDemoActivity \
 *     --es video /sdcard/Android/data/com.ultimatevideo.uveditor/files/in.mp4 \
 *     --es out /sdcard/Android/data/com.ultimatevideo.uveditor/files/out.mp4 \
 *     --es codec hevc --ei w 1280 --ei h 720 --ei fps 30 --es layout split
 *
 * `--ez hdr true` exports HLG (HEVC Main10, BT.2020); pair it with `--ei color 1` so the source is read as HLG.
 *
 * Layouts: `single` exports the first `frames` frames; `split` exports 60 frames, a 30 frame gap and
 * 60 more frames from further into the file; `layers` stacks a transformed clip over a full-frame one. The outcome is written to `<out>.result.txt`.
 */
class ExportDemoActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val video = intent.getStringExtra("video") ?: return finishWith("missing --es video")
        val out = File(intent.getStringExtra("out") ?: return finishWith("missing --es out"))
        val codec = if (intent.getStringExtra("codec") == "hevc") ExportCodec.HEVC else ExportCodec.H264
        val width = intent.getIntExtra("w", 1280)
        val height = intent.getIntExtra("h", 720)
        val fps = intent.getIntExtra("fps", 30)
        val frames = intent.getIntExtra("frames", 150).toLong()
        val withAudio = intent.getBooleanExtra("audio", true)
        val layout = intent.getStringExtra("layout") ?: "single"
        val bitrate = intent.getIntExtra("bitrate", 12) * 1_000_000

        val clips = if (layout == "layers") {
            // A full-frame base (layer 1) with a smaller, rotated, half-transparent copy of a later part on top (layer 0).
            listOf(
                VideoClipSpec(0, 90, 0, 0, 1, 0),
                VideoClipSpec(
                    30, 60, 120, 0, 0, 0,
                    positionX = width * 0.25, positionY = -height * 0.2, scaleX = 0.5, scaleY = 0.5, rotationDegrees = 20.0, opacity = 0.7,
                ),
            )
        } else if (layout == "cover") {
            // Hidden-layer skipping: a base (layer 1, 6 s) under a top clip (layer 0) in one of the situations `--es case` names. The
            // export is compared frame by frame with `--ez keep_hidden true`, which draws every layer (see SPECS 5.10).
            val n = intent.getIntExtra("frames", 180).toLong()
            val color = intent.getIntExtra("color", 0)
            val base = VideoClipSpec(0, n, 0, 0, 1, color)
            fun top(start: Long = 0, len: Long = n, scale: Double = 1.0, opacity: Double = 1.0, fade: Long = 0, x: Double = 0.0) =
                VideoClipSpec(start, len, 100, 0, 0, color, positionX = x, scaleX = scale, scaleY = scale, opacity = opacity, crossfadeInFrames = fade)
            when (intent.getStringExtra("case") ?: "full") {
                "full" -> listOf(base, top())                                             // covers for the whole movie
                "resume" -> listOf(base, top(start = n / 4, len = n / 2))                 // covers in the middle, then the base resumes
                "small" -> listOf(base, top(scale = 0.5))
                "fade" -> listOf(base, top(fade = 60))                                    // partial opacity first, then full
                "opacity" -> listOf(base, top(opacity = 0.99))
                "short" -> listOf(base, top(start = n / 6, len = fps.toLong()))           // covers for 1 s only
                "zoom" -> listOf(base, top(scale = 1.2, x = 20.0))
                else -> return finishWith("unknown --es case")
            }
        } else if (layout == "split") {
            listOf(
                VideoClipSpec(0, 60, 0, 0, 0, 0),
                VideoClipSpec(90, 60, 100, 0, 0, intent.getIntExtra("color", 0)),
            )
        } else {
            listOf(VideoClipSpec(0, frames, 0, 0, 0, intent.getIntExtra("color", 0)))
        }
        val total = clips.maxOf { it.startFrame + it.durationFrames }
        val audio = if (withAudio) {
            AudioSnapshot(fps, 1, clips.mapIndexed { i, c ->
                AudioClipSpec(i.toLong(), 0, c.startFrame, c.durationFrames, c.sourceInFrame, fps, 1)
            }).encode()
        } else {
            null
        }

        val done = CountDownLatch(1)
        var outcome = "unknown"
        val started = System.nanoTime()
        Thread {
            try {
                val input = ParcelFileDescriptor.open(File(video), ParcelFileDescriptor.MODE_READ_ONLY).detachFd()
                out.delete()
                val output = ParcelFileDescriptor.open(
                    out,
                    ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE,
                ).detachFd()
                val request = ExportRequest(
                    settings = ExportSettings(width, height, fps, 1, codec, bitrate, hdr = intent.getBooleanExtra("hdr", false),
                        skipHiddenLayers = !intent.getBooleanExtra("keep_hidden", false)),
                    projectFpsNum = fps,
                    projectFpsDen = 1,
                    canvasWidth = intent.getIntExtra("cw", width),
                    canvasHeight = intent.getIntExtra("ch", height),
                    totalFrames = total,
                    assetFds = mapOf(0L to input),
                    videoClips = clips,
                    audioSnapshot = audio,
                    outputFd = output,
                )
                val handle = NativeExportRunner().start(
                    request,
                    object : ExportListener {
                        override fun onProgress(permille: Int) {
                            if (permille % 100 == 0) Log.i(TAG, "progress $permille")
                        }

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
            val seconds = (System.nanoTime() - started) / 1e9
            File(out.path + ".result.txt").writeText("$outcome in %.2f s, ${out.length()} bytes\n".format(seconds))
            Log.i(TAG, "result: $outcome")
            runOnUiThread { finish() }
        }.start()
    }

    private fun finishWith(message: String) {
        Log.e(TAG, message)
        finish()
    }

    private companion object {
        const val TAG = "ExportDemo"
    }
}
