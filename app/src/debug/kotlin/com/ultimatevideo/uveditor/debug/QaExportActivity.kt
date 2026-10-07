package com.ultimatevideo.uveditor.debug

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.util.Log
import com.ultimatevideo.uveditor.data.ProjectJson
import com.ultimatevideo.uveditor.data.TimelineMapper
import com.ultimatevideo.uveditor.domain.FrameRate
import com.ultimatevideo.uveditor.engine.export.ExportCodec
import com.ultimatevideo.uveditor.engine.export.ExportErrorCode
import com.ultimatevideo.uveditor.engine.export.ExportException
import com.ultimatevideo.uveditor.engine.export.ExportRequest
import com.ultimatevideo.uveditor.engine.export.ExportSettings
import com.ultimatevideo.uveditor.engine.export.ExportTitle
import com.ultimatevideo.uveditor.engine.still.AndroidStillRasterizer
import com.ultimatevideo.uveditor.engine.title.AndroidTitleRasterizer
import com.ultimatevideo.uveditor.engine.export.NativeExportRunner
import com.ultimatevideo.uveditor.engine.verify.ChannelByteSource
import com.ultimatevideo.uveditor.engine.verify.Mp4Reader
import com.ultimatevideo.uveditor.engine.verify.VerificationText
import com.ultimatevideo.uveditor.ui.export.ContentResolverExportIO
import com.ultimatevideo.uveditor.ui.export.DeviceExportVerifier
import com.ultimatevideo.uveditor.ui.export.ExportExecutor
import com.ultimatevideo.uveditor.ui.export.ExportVerifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.ultimatevideo.uveditor.ui.export.ExportCenter
import com.ultimatevideo.uveditor.ui.export.ExportJob
import com.ultimatevideo.uveditor.ui.export.ExportJobState
import com.ultimatevideo.uveditor.ui.export.StillPictureProvider
import com.ultimatevideo.uveditor.ui.export.buildExportPlan
import com.ultimatevideo.uveditor.ui.export.outputFrameCount
import java.io.File
import java.io.IOException

/**
 * Debug-only harness for scripts/qa-smoke.sh: exports a project.json through the same path as the Export dialog, without any UI.
 * It builds the plan with the real planner (clips of mixed frame rates, rotation, audio of every codec, photos, titles), hands the
 * job to the process-wide [ExportCenter] executor, which starts the real [com.ultimatevideo.uveditor.ui.export.ExportService]
 * (so the foreground service is exercised exactly as a user's export does it), and waits for the outcome.
 *
 *   adb shell am start -n <pkg>/com.ultimatevideo.uveditor.debug.QaExportActivity --es project <project.json> --es out <out.mp4> \
 *     [--es codec avc|hevc] [--ez hdr true] [--ei w <px> --ei h <px>] [--ei fps <n>] [--ei bitrate <Mbps>]
 *
 * `--es damage truncate|zero2mb|zerotail|garble` damages the saved file before it is verified (scripts/qa-smoke.sh VERIFY-DAMAGE).
 *
 * Writes `<out>.result.txt`: `OK in <s> s, <bytes> bytes frames=<expected>` or `ERROR <code>: <message> ...`. The log tag is UVQa.
 */
class QaExportActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val projectPath = intent.getStringExtra("project") ?: return finishWith("missing --es project")
        val out = File(intent.getStringExtra("out") ?: return finishWith("missing --es out"))
        Thread {
            val started = System.nanoTime()
            val outcome = try {
                run(File(projectPath), out)
            } catch (e: ExportException) {
                "ERROR ${e.code}: ${e.message}"
            } catch (e: IOException) {
                "ERROR IO: ${e.message}"
            } catch (e: IllegalArgumentException) {
                "ERROR ARGUMENT: ${e.message}"
            } catch (e: RuntimeException) {
                // A harness: whatever went wrong is the result the script reports, with its class name.
                Log.e(TAG, "export harness failed", e)
                "ERROR ${e.javaClass.simpleName}: ${e.message}"
            }
            val seconds = (System.nanoTime() - started) / 1e9
            try {
                File(out.path + ".result.txt").writeText("$outcome in %.2f s, ${out.length()} bytes\n".format(java.util.Locale.ROOT, seconds))
            } catch (e: IOException) {
                Log.e(TAG, "cannot write the result file: ${e.message}")
            }
            Log.i(TAG, "result: $outcome")
            runOnUiThread { finish() }
        }.start()
    }

    private fun run(projectFile: File, out: File): String {
        val project = ProjectJson.decode(projectFile.readText())
        val timeline = TimelineMapper.toTimeline(project)
        val fps = FrameRate(project.settings.fpsNum, project.settings.fpsDen)
        val width = intent.getIntExtra("w", project.settings.width)
        val height = intent.getIntExtra("h", project.settings.height)
        val outputFps = FrameRate(intent.getIntExtra("fps", project.settings.fpsNum), project.settings.fpsDen)
        val codec = if (intent.getStringExtra("codec") == "hevc") ExportCodec.HEVC else ExportCodec.H264
        val hdr = intent.getBooleanExtra("hdr", false)
        val bitrate = intent.getIntExtra("bitrate", 12) * 1_000_000
        val plan = checkNotNull(buildExportPlan(timeline, project.mediaLibrary, fps, project.settings.width, project.settings.height)) { "empty plan" }
        val app = applicationContext
        val io = ContentResolverExportIO(app)
        val titleRasterizer = AndroidTitleRasterizer()
        val stillRasterizer = AndroidStillRasterizer(app)
        val expectedFrames = outputFrameCount(plan.projectFrames, fps, outputFps)
        val outputUri = Uri.fromFile(out).toString()
        out.delete()

        // --es damage <mode>: a private executor whose verifier first damages the finished file, to show the check catches it.
        val damage = intent.getStringExtra("damage")
        val executor = if (damage == null) ExportCenter.executor(app) else damagingExecutor(app, io, damage)
        val job = ExportJob(project.id, project.name, outputUri) {
            val titles = plan.titles.map { (key, content) ->
                val bitmap = titleRasterizer.rasterize(content, project.settings.width, project.settings.height)
                ExportTitle(key, bitmap.width, bitmap.height, bitmap.pixels)
            }
            val uriByAsset = project.mediaLibrary.associate { it.id to it.uri }
            val opened = LinkedHashMap<Long, Int>()
            val outputFd: Int
            try {
                for ((assetId, key) in plan.assetKeys) {
                    opened[key] = io.openAsset(uriByAsset.getValue(assetId))
                }
                outputFd = io.openOutput(outputUri)
            } catch (e: IOException) {
                opened.values.forEach(io::close)
                throw ExportException(ExportErrorCode.IO_ERROR, "Cannot open a file for the export: ${e.message}")
            }
            ExportRequest(
                settings = ExportSettings(width, height, outputFps.num, outputFps.den, codec, bitrate, hdr = hdr),
                projectFpsNum = fps.num,
                projectFpsDen = fps.den,
                canvasWidth = project.settings.width,
                canvasHeight = project.settings.height,
                totalFrames = expectedFrames,
                assetFds = opened,
                videoClips = plan.videoClips,
                audioSnapshot = plan.audio?.encode(),
                outputFd = outputFd,
                titles = titles,
                pictureProvider = StillPictureProvider(plan.stills, stillRasterizer, project.settings.width, project.settings.height)
                    .takeIf { plan.stills.isNotEmpty() },
            )
        }
        check(executor.start(job)) { "another export is already running" }
        // The job runs on the executor's threads and the service shows its notification; wait for a final state.
        val deadline = System.currentTimeMillis() + intent.getIntExtra("timeout", 600) * 1000L
        while (System.currentTimeMillis() < deadline) {
            when (val state = executor.state.value) {
                is ExportJobState.Done -> {
                    executor.acknowledge(null)
                    val verification = state.verification?.let { VerificationText.resultLine(it) } ?: "verification=none"
                    val headline = state.verification?.let { " headline=\"${VerificationText.headline(it)}\" detail=\"${VerificationText.detail(it)}\"" } ?: ""
                    return "OK frames=$expectedFrames audio=${plan.audio != null}" + (if (state.note.isNotEmpty()) " note=${state.note}" else "") +
                        " $verification$headline" + (if (damage != null) " damage=$damage" else "")
                }
                is ExportJobState.Failed -> {
                    executor.acknowledge(null)
                    return "ERROR ${state.error?.code}: ${state.error?.message}"
                }
                is ExportJobState.Cancelled -> return "ERROR cancelled"
                else -> Thread.sleep(250)
            }
        }
        executor.cancel()
        return "ERROR timeout"
    }

    /** An executor of its own (no service, not the process-wide one) that damages the saved file before it is verified. */
    private fun damagingExecutor(app: android.content.Context, io: ContentResolverExportIO, mode: String): ExportExecutor {
        val inner = DeviceExportVerifier(io)
        return ExportExecutor(
            io = io,
            runner = NativeExportRunner(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            ioDispatcher = Dispatchers.IO,
            verifier = ExportVerifier { target, cancel, progress ->
                damageFile(File(Uri.parse(target.uri).path.orEmpty()), mode)
                inner.verify(target, cancel, progress)
            },
        )
    }

    private fun damageFile(file: File, mode: String) {
        java.io.RandomAccessFile(file, "rw").use { raf ->
            val length = raf.length()
            fun zero(from: Long, to: Long) {
                raf.seek(from)
                val block = ByteArray(1 shl 16)
                var left = to - from
                while (left > 0) {
                    val n = minOf(left, block.size.toLong()).toInt()
                    raf.write(block, 0, n)
                    left -= n
                }
            }
            when (mode) {
                "truncate" -> raf.setLength(length - 2 * 1024 * 1024)
                "zero2mb" -> zero(length - 2 * 1024 * 1024, length)
                "zerotail", "garble" -> {
                    val video = Mp4Reader.read(ChannelByteSource(raf.channel)).video ?: error("no video track")
                    for (i in maxOf(0, video.count - DAMAGED_SAMPLES) until video.count) {
                        val at = video.offsets[i]
                        val size = video.sizes[i]
                        if (mode == "zerotail") {
                            zero(at, at + size)
                        } else {
                            // Burst of flipped bits in the middle of the sample: the NAL structure stays intact, the decoder is left to cope.
                            val middle = at + size / 2
                            val burst = ByteArray(minOf(64, size / 4))
                            raf.seek(middle)
                            raf.read(burst)
                            for (k in burst.indices) burst[k] = (burst[k].toInt() xor 0xFF).toByte()
                            raf.seek(middle)
                            raf.write(burst)
                        }
                    }
                }
                else -> error("unknown damage mode $mode")
            }
        }
        Log.i(TAG, "damaged ${file.name} with $mode")
    }

    private fun finishWith(message: String) {
        Log.e(TAG, message)
        finish()
    }

    private companion object {
        const val TAG = "UVQa"
        const val DAMAGED_SAMPLES = 45
    }
}
