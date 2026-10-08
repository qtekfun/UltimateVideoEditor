package com.qtekfun.ultimatevideoeditor.debug

import android.app.Activity
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Bundle
import java.io.File
import java.util.zip.CRC32

/**
 * Debug-only spike harness (smart export feasibility): decodes MP4 files from the app's external files dir with
 * MediaExtractor + MediaCodec (ByteBuffer output), logs frame count, pts order, gaps, format changes and a CRC per frame,
 * and saves a few frames around the given join positions as PNG via MediaMetadataRetriever.
 *   adb shell am start -n <pkg>/com.qtekfun.ultimatevideoeditor.debug.DecodeCheckActivity --es files a.mp4,b.mp4 --es joins 60,357,417
 * `--es dir <path>` reads and writes there instead of the external files dir (e.g. the app's private files dir, pushed through run-as).
 * Result: files/decodecheck.txt (the last line is DONE), frames/<name>_<n>.png, crc/<name>.txt.
 */
class DecodeCheckActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dir = intent.getStringExtra("dir")?.let { File(it) } ?: getExternalFilesDir(null)!!
        val names = intent.getStringExtra("files").orEmpty().split(',').filter { it.isNotBlank() }
        val joins = intent.getStringExtra("joins").orEmpty().split(',').mapNotNull { it.toIntOrNull() }
        Thread {
            val log = StringBuilder()
            for (n in names) {
                try { check(File(dir, n), joins, dir, log) } catch (t: Throwable) { log.appendLine("$n EXCEPTION $t") }
                File(dir, "decodecheck.txt").writeText(log.toString())
            }
            log.appendLine("DONE")
            File(dir, "decodecheck.txt").writeText(log.toString())
            runOnUiThread { finish() }
        }.start()
    }

    private fun check(file: File, joins: List<Int>, dir: File, log: StringBuilder) {
        val name = file.nameWithoutExtension
        log.appendLine("== ${file.name}")
        val ex = MediaExtractor()
        ex.setDataSource(file.absolutePath)
        log.appendLine("tracks=${ex.trackCount}")
        var track = -1
        var fmt: MediaFormat? = null
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            log.appendLine("track $i $f")
            if (track < 0 && f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) { track = i; fmt = f }
        }
        val format = fmt ?: return
        ex.selectTrack(track)
        val csd0 = format.getByteBuffer("csd-0")
        log.appendLine("csd-0 bytes=${csd0?.remaining()}")
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        log.appendLine("decoder=${codec.name}")
        codec.configure(format, null, null, 0)
        codec.start()
        val info = MediaCodec.BufferInfo()
        val crcs = ArrayList<Pair<Long, Long>>()
        var inDone = false
        var outDone = false
        var fed = 0
        var fmtChanges = 0
        val t0 = System.nanoTime()
        var stall = 0
        while (!outDone) {
            if (!inDone) {
                val ii = codec.dequeueInputBuffer(5_000)
                if (ii >= 0) {
                    val buf = codec.getInputBuffer(ii)!!
                    val sz = ex.readSampleData(buf, 0)
                    if (sz < 0) {
                        codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true
                    } else {
                        codec.queueInputBuffer(ii, 0, sz, ex.sampleTime, 0); ex.advance(); fed++
                    }
                }
            }
            val oi = codec.dequeueOutputBuffer(info, 5_000)
            when {
                oi >= 0 -> {
                    stall = 0
                    if (info.size > 0) {
                        val ob = codec.getOutputBuffer(oi)!!
                        val c = CRC32()
                        val arr = ByteArray(minOf(info.size, 1 shl 20))
                        ob.position(info.offset); ob.limit(info.offset + info.size)
                        while (ob.hasRemaining()) { val k = minOf(ob.remaining(), arr.size); ob.get(arr, 0, k); c.update(arr, 0, k) }
                        crcs += info.presentationTimeUs to c.value
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                    codec.releaseOutputBuffer(oi, false)
                }
                oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { fmtChanges++; log.appendLine("output format changed: ${codec.outputFormat}") }
                else -> if (inDone && ++stall > 400) { log.appendLine("STALL waiting for EOS"); outDone = true }
            }
        }
        codec.stop(); codec.release(); ex.release()
        val ms = (System.nanoTime() - t0) / 1_000_000
        log.appendLine("fed=$fed decoded=${crcs.size} formatChanges=$fmtChanges time=${ms}ms")
        val pts = crcs.map { it.first }
        val sorted = pts.sorted()
        var nonMono = 0
        for (i in 1 until pts.size) if (pts[i] <= pts[i - 1]) nonMono++
        val gaps = (1 until sorted.size).filter { kotlin.math.abs((sorted[it] - sorted[it - 1]) - 16_667L) > 2 }
        log.appendLine("pts first=${pts.firstOrNull()} last=${pts.lastOrNull()} nonMonotonicOutput=$nonMono gapsAfterSort=${gaps.size} ${gaps.take(6).map { "@$it d=${sorted[it] - sorted[it - 1]}" }}")
        File(dir, "crc").mkdirs()
        File(dir, "crc/$name.txt").writeText(crcs.sortedBy { it.first }.joinToString("\n") { "${it.first} ${it.second}" })
        // frames around the joins through MediaMetadataRetriever
        File(dir, "frames").mkdirs()
        val mmr = MediaMetadataRetriever()
        mmr.setDataSource(file.absolutePath)
        val base = sorted.firstOrNull() ?: 0L
        for (j in joins) for (d in -1..1) {
            val n = j + d
            val bmp = mmr.getFrameAtTime(base + n * 1_000_000L / 60, MediaMetadataRetriever.OPTION_CLOSEST) ?: run { log.appendLine("frame $n: null"); null }
            if (bmp != null) {
                val s = Bitmap.createScaledBitmap(bmp, 480, 270, true)
                File(dir, "frames/${name}_$n.png").outputStream().use { s.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        mmr.release()
    }
}
