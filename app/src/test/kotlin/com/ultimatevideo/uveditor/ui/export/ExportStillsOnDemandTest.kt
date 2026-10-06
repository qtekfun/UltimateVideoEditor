package com.ultimatevideo.uveditor.ui.export

import com.ultimatevideo.uveditor.data.model.MediaAssetDto
import com.ultimatevideo.uveditor.domain.Clip
import com.ultimatevideo.uveditor.domain.FrameIndex
import com.ultimatevideo.uveditor.domain.FrameRate
import com.ultimatevideo.uveditor.domain.StillKind
import com.ultimatevideo.uveditor.domain.timeline
import com.ultimatevideo.uveditor.domain.track
import com.ultimatevideo.uveditor.engine.export.ExportHandle
import com.ultimatevideo.uveditor.engine.export.ExportListener
import com.ultimatevideo.uveditor.engine.export.ExportRequest
import com.ultimatevideo.uveditor.engine.export.ExportRunner
import com.ultimatevideo.uveditor.engine.still.PictureBudget
import com.ultimatevideo.uveditor.engine.still.StillRasterException
import com.ultimatevideo.uveditor.engine.still.StillRasterizer
import com.ultimatevideo.uveditor.engine.still.StillRef
import com.ultimatevideo.uveditor.engine.title.TitleBitmap
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Stills reach the engine on request, one at a time, instead of all being rasterised and held up front. */
@OptIn(ExperimentalCoroutinesApi::class)
class ExportStillsOnDemandTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class Io : ExportIO {
        private var next = 100
        override fun openAsset(uri: String) = next++
        override fun openOutput(uri: String) = next++
        override fun close(fd: Int) = Unit
        override fun deleteOutput(uri: String) = true
    }

    private class Runner : ExportRunner {
        var request: ExportRequest? = null
        override fun start(request: ExportRequest, listener: ExportListener): ExportHandle {
            this.request = request
            return object : ExportHandle {
                override fun cancel() = Unit
                override fun close() = Unit
            }
        }
    }

    /** A 480x270 frame stored natively and drawn at the 1920x1080 canvas, like a small GIF on a bigger canvas. */
    private class CountingRasterizer(private val failOn: (StillRef) -> Boolean = { false }) : StillRasterizer {
        val calls = ArrayList<StillRef>()
        override fun rasterize(ref: StillRef, canvasWidth: Int, canvasHeight: Int): TitleBitmap {
            calls += ref
            if (failOn(ref)) throw StillRasterException("the file is missing")
            return TitleBitmap(480, 270, ByteBuffer.allocateDirect(480 * 270 * 4), canvasWidth, canvasHeight)
        }
    }

    private val fps = FrameRate(30, 1)

    // 600 animation frames of 33 ms each: about one per project frame at 30 fps.
    private val gif = MediaAssetDto(
        "gif", "content://pic/anim", 600, 30, 1, "Rec709-SDR", hasVideo = false, hasAudio = false, isImage = true,
        animationDelaysMs = List(600) { 33 },
    )

    private fun input() = ExportInput(
        projectName = "Gifs",
        projectWidth = 1920,
        projectHeight = 1080,
        fps = fps,
        timeline = timeline(track("v1", Clip("g", "gif", FrameIndex(0), FrameIndex.ZERO, FrameIndex(600), still = StillKind.PHOTO))),
        assets = listOf(gif),
    )

    private fun start(rasterizer: StillRasterizer, runner: Runner): ExportViewModel {
        val vm = ExportViewModel(Io(), runner, dispatcher, stillRasterizer = rasterizer)
        vm.onIntent(ExportIntent.Open(input()))
        vm.onIntent(ExportIntent.LocationChosen("content://out/movie.mp4"))
        return vm
    }

    @Test
    fun `no picture is rasterised up front except one check per source file`() {
        val rasterizer = CountingRasterizer()
        val runner = Runner()
        start(rasterizer, runner)

        val request = runner.request!!
        assertTrue("stills are not passed as titles", request.titles.isEmpty())
        assertNotNull(request.pictureProvider)
        assertEquals(PictureBudget.DEFAULT_BYTES, request.pictureBudgetBytes)
        // One check of the first frame for the one source file, whatever the number of animation frames.
        assertEquals(listOf(StillRef(StillKind.PHOTO, "content://pic/anim", 0)), rasterizer.calls)
    }

    @Test
    fun `the engine pulls each distinct frame once and gets its native size and display size`() {
        val rasterizer = CountingRasterizer()
        val runner = Runner()
        start(rasterizer, runner)
        val request = runner.request!!
        val provider = request.pictureProvider!!
        val keys = request.videoClips.map { it.titleKey }.distinct()
        assertTrue("about one picture per animation frame, got ${keys.size}", keys.size in 550..600)
        rasterizer.calls.clear()

        var held = 0L
        for (key in keys) {
            val picture = provider.load(key)!!
            assertEquals(480 to 270, picture.width to picture.height)
            assertEquals(1920 to 1080, picture.displayWidth to picture.displayHeight)
            // What the engine would keep at once is bounded by its budget, not by the number of frames.
            held = maxOf(held, picture.width.toLong() * picture.height * 4)
        }
        assertEquals("one rasterisation per distinct picture", keys.size, rasterizer.calls.size)
        assertEquals("every picture is its own animation frame", keys.size, rasterizer.calls.map { it.frame }.distinct().size)
        assertNull(provider.lastError)
        // 600 distinct frames stored natively would take 311 MB held at once; the budget caps what stays resident.
        assertTrue(keys.size.toLong() * held > PictureBudget.DEFAULT_BYTES)
    }

    @Test
    fun `a picture that cannot be read fails the export before the engine starts`() {
        val rasterizer = CountingRasterizer(failOn = { true })
        val runner = Runner()
        val vm = start(rasterizer, runner)

        assertNull("the engine is never started", runner.request)
        val phase = vm.state.value.phase
        assertTrue("phase was $phase", phase is ExportPhase.Failed)
        assertTrue((phase as ExportPhase.Failed).message.contains("A picture could not be drawn"))
        assertTrue(phase.message.contains("the file is missing"))
    }

    @Test
    fun `the provider reports why a picture could not be made and an unknown key`() {
        val ref = StillRef(StillKind.PHOTO, "content://pic/x", 3)
        val provider = StillPictureProvider(mapOf(7 to ref), CountingRasterizer(failOn = { it.frame == 3 }), 1920, 1080)
        assertNull(provider.load(7))
        assertEquals("the file is missing", provider.lastError)
        assertNull(provider.load(99))
        assertEquals("an unknown picture was requested", provider.lastError)
        val ok = StillPictureProvider(mapOf(1 to ref.copy(frame = 0)), CountingRasterizer(), 1920, 1080)
        assertEquals(1, ok.load(1)!!.key)
        assertNull(ok.lastError)
    }
}
