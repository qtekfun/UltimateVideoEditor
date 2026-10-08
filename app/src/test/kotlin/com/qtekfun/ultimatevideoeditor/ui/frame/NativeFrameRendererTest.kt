package com.qtekfun.ultimatevideoeditor.ui.frame

import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto
import com.qtekfun.ultimatevideoeditor.domain.FrameRate
import com.qtekfun.ultimatevideoeditor.domain.clip
import com.qtekfun.ultimatevideoeditor.domain.timeline
import com.qtekfun.ultimatevideoeditor.domain.track
import com.qtekfun.ultimatevideoeditor.engine.export.ExportErrorCode
import com.qtekfun.ultimatevideoeditor.engine.export.ExportException
import com.qtekfun.ultimatevideoeditor.engine.export.ExportHandle
import com.qtekfun.ultimatevideoeditor.engine.export.ExportListener
import com.qtekfun.ultimatevideoeditor.engine.export.ExportRequest
import com.qtekfun.ultimatevideoeditor.engine.export.ExportRunner
import com.qtekfun.ultimatevideoeditor.engine.still.StillRasterizer
import com.qtekfun.ultimatevideoeditor.engine.title.TitleRasterizer
import com.qtekfun.ultimatevideoeditor.ui.export.ExportIO
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NativeFrameRendererTest {
    private class FakeIO : ExportIO {
        val closed = mutableListOf<Int>()
        override fun openAsset(uri: String): Int = 7
        override fun openOutput(uri: String): Int = error("no output file for a picture")
        override fun close(fd: Int) {
            closed += fd
        }
        override fun deleteOutput(uri: String): Boolean = true
    }

    private class FakeRunner(val error: ExportException? = null, val draw: (ExportRequest) -> Unit = {}) : ExportRunner {
        var request: ExportRequest? = null
        var closed = false
        override fun start(request: ExportRequest, listener: ExportListener): ExportHandle {
            this.request = request
            draw(request)
            listener.onFinished(error)
            return object : ExportHandle {
                override fun cancel() = Unit
                override fun close() {
                    closed = true
                }
            }
        }
    }

    private val assets = listOf(MediaAssetDto("a", "content://a", 600, 30, 1, "Rec709-SDR"))
    private val tl = timeline(track("v1", clip("c1", 0, 30, asset = "a"), clip("c2", 60, 30, asset = "a")))

    private fun job(frame: Long) = FrameRenderJob(tl, assets, FrameRate(30, 1), 64, 36, frame, 64, 36, emptySet())

    private fun renderer(runner: FakeRunner) = NativeFrameRenderer(
        FakeIO(), runner, TitleRasterizer { _, _, _ -> error("no title") }, StillRasterizer { _, _, _ -> error("no still") }, log = {},
    )

    @Test
    fun `a request for one frame at the surface size is handed to the engine`() {
        val runner = FakeRunner { r -> r.still!!.pixels.putInt(0, 0x01020304) }

        val frame = runBlocking { renderer(runner).render(job(10)) }

        val request = runner.request!!
        assertEquals(10L, request.still!!.frame)
        assertEquals(64 to 36, request.settings.width to request.settings.height)
        assertEquals(1L, request.totalFrames)
        assertEquals(-1, request.outputFd)
        assertEquals(1, request.videoClips.size) // only the clip under the frame
        assertEquals(64 to 36, frame.width to frame.height)
        assertTrue(runner.closed)
    }

    @Test
    fun `a flat picture of a frame with video is refused`() {
        val runner = FakeRunner() // nothing drawn: all zero

        try {
            runBlocking { renderer(runner).render(job(10)) }
            fail("expected the guard to refuse")
        } catch (e: StillFrameException) {
            assertTrue(e.message!!.contains("flat colour"))
        }
        assertTrue(runner.closed)
    }

    @Test
    fun `a black frame in a gap is not refused`() {
        val runner = FakeRunner()

        val frame = runBlocking { renderer(runner).render(job(45)) }

        assertEquals(0, runner.request!!.videoClips.size)
        assertEquals(64, frame.width)
    }

    @Test
    fun `an engine error is passed on and the engine is closed`() {
        val runner = FakeRunner(ExportException(ExportErrorCode.CODEC_ERROR, "stalled"))

        try {
            runBlocking { renderer(runner).render(job(10)) }
            fail("expected an error")
        } catch (e: ExportException) {
            assertEquals("stalled", e.message)
        }
        assertTrue(runner.closed)
    }

    @Test
    fun `missing media is named before the engine starts`() {
        val runner = FakeRunner()
        val j = FrameRenderJob(tl, assets, FrameRate(30, 1), 64, 36, 10, 64, 36, setOf("a"))

        try {
            runBlocking { renderer(runner).render(j) }
            fail("expected a refusal")
        } catch (e: StillFrameException) {
            assertTrue(e.message!!.contains("missing"))
        }
        assertEquals(null, runner.request)
    }
}
