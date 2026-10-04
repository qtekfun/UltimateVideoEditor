package com.ultimatevideo.uveditor.engine.preview

import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Draws source frame 10 of `slowmo_src.mp4` in the real preview pipeline twice, plain and with a 0.5 mix towards frame
 * 11 (what smooth slow motion asks for between two frames), onto an ImageReader surface, and writes both pictures as
 * raw RGBA next to the clip. `scripts/check-slowmo-preview.sh` compares them with the 240 fps ground truth.
 */
@RunWith(AndroidJUnit4::class)
class SlowMotionPreviewInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun drawsAFrameAndItsInterpolatedNeighbour() {
        val dir = checkNotNull(context.getExternalFilesDir(null))
        val source = File(dir, "slowmo_src.mp4")
        val truth = File(dir, "slowmo_truth.mp4")
        assertTrue("push slowmo_src.mp4 and slowmo_truth.mp4 to ${dir.path} first", source.exists() && truth.exists())
        // The preview renders into a ten-bit surface (RGB10_A2), so the reader must have that format too.
        val reader = ImageReader.newInstance(1280, 720, HardwareBuffer.RGBA_1010102, 3)
        val errors = mutableListOf<String>()
        val engine = PreviewEngine.create(onError = { errors += "${it.code}: ${it.message}" })
        try {
            engine.attachSurface(reader.surface)
            val info = engine.openAsset(1, ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY))
            val truthInfo = engine.openAsset(2, ParcelFileDescriptor.open(truth, ParcelFileDescriptor.MODE_READ_ONLY))
            File(dir, "preview_info.txt").writeText("src $info\ntruth $truthInfo\n")
            // The same pipeline draws the 60 fps clip (plain and interpolated) and the 240 fps truth frames.
            draw(engine, reader, 1, 10, 0f, File(dir, "preview_mix0.rgba"), errors)
            draw(engine, reader, 1, 10, 0.5f, File(dir, "preview_mix50.rgba"), errors)
            draw(engine, reader, 1, 10, 0.25f, File(dir, "preview_mix25.rgba"), errors)
            draw(engine, reader, 2, 40, 0f, File(dir, "preview_truth40.rgba"), errors)
            draw(engine, reader, 2, 41, 0f, File(dir, "preview_truth41.rgba"), errors)
            draw(engine, reader, 2, 42, 0f, File(dir, "preview_truth42.rgba"), errors)
        } finally {
            engine.detachSurface()
            engine.close()
            reader.close()
        }
        assertTrue("native errors: $errors", errors.isEmpty())
    }

    private fun draw(engine: PreviewEngine, reader: ImageReader, asset: Int, frame: Long, mix: Float, out: File, errors: List<String>) {
        val before = engine.stats().framesDrawn
        engine.setScene(1280, 720, listOf(PreviewLayer(assetId = asset, frame = frame, mix = mix)))
        val deadline = System.nanoTime() + 8_000_000_000L
        while (engine.stats().framesDrawn <= before && System.nanoTime() < deadline) Thread.sleep(20)
        assertTrue("nothing was drawn for mix $mix; errors $errors", engine.stats().framesDrawn > before)
        Thread.sleep(150)
        var image = reader.acquireLatestImage()
        val retryUntil = System.nanoTime() + 3_000_000_000L
        while (image == null && System.nanoTime() < retryUntil) {
            Thread.sleep(20)
            image = reader.acquireLatestImage()
        }
        checkNotNull(image) { "no picture reached the reader for mix $mix" }
        image.use {
            val plane = it.planes[0]
            val row = it.width * 4
            val packed = ByteArray(row * it.height)
            val buffer = plane.buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
            // 32-bit little-endian words: red in bits 0-9, green 10-19, blue 20-29, alpha 30-31; written as 8-bit RGBX.
            for (y in 0 until it.height) {
                for (x in 0 until it.width) {
                    val word = buffer.getInt(y * plane.rowStride + x * 4)
                    val i = y * row + x * 4
                    packed[i] = ((word and 0x3FF) shr 2).toByte()
                    packed[i + 1] = (((word shr 10) and 0x3FF) shr 2).toByte()
                    packed[i + 2] = (((word shr 20) and 0x3FF) shr 2).toByte()
                    packed[i + 3] = 0xFF.toByte()
                }
            }
            out.writeBytes(packed)
        }
    }
}
