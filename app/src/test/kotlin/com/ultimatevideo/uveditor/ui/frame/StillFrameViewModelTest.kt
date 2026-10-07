package com.ultimatevideo.uveditor.ui.frame

import com.ultimatevideo.uveditor.data.model.MediaAssetDto
import com.ultimatevideo.uveditor.domain.FrameRate
import com.ultimatevideo.uveditor.domain.clip
import com.ultimatevideo.uveditor.domain.timeline
import com.ultimatevideo.uveditor.domain.track
import com.ultimatevideo.uveditor.engine.export.ExportErrorCode
import com.ultimatevideo.uveditor.engine.export.ExportException
import com.ultimatevideo.uveditor.ui.export.ExportJobHost
import com.ultimatevideo.uveditor.ui.export.ExportJobState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.time.LocalDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class StillFrameViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeRenderer : FrameRenderer {
        var job: FrameRenderJob? = null
        var failWith: Exception? = null
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun render(job: FrameRenderJob): RenderedFrame {
            this.job = job
            gate?.await()
            failWith?.let { throw it }
            return RenderedFrame(job.width, job.height, ByteBuffer.allocateDirect(job.width * job.height * 4))
        }
    }

    private class FakeEncoder : FrameEncoder {
        var quality = 0
        override fun encodeJpeg(frame: RenderedFrame, quality: Int): ByteArray {
            this.quality = quality
            return ByteArray(1234)
        }
    }

    private class FakeSink : FrameSink {
        val saved = mutableListOf<Pair<String, ByteArray>>()
        var fail = false

        override fun saveJpeg(displayName: String, bytes: ByteArray): SavedImage {
            if (fail) throw IOException("The gallery could not create Pictures/ultimateVE/$displayName")
            saved += displayName to bytes
            return SavedImage("content://media/$displayName", displayName, "Pictures/ultimateVE")
        }
    }

    private class FakeExports(val flow: MutableStateFlow<ExportJobState> = MutableStateFlow(ExportJobState.Idle)) : ExportJobHost {
        override val state: StateFlow<ExportJobState> = flow
        override fun cancel() = Unit
        override fun acknowledge(onlyProject: String?) = Unit
    }

    private val renderer = FakeRenderer()
    private val encoder = FakeEncoder()
    private val sink = FakeSink()
    private val assets = listOf(MediaAssetDto("a", "content://a", 600, 60, 1, "Rec709-SDR"))
    private val tl = timeline(track("v1", clip("c1", 0, 30), clip("c2", 60, 30)))

    private fun viewModel(exports: ExportJobHost = FakeExports()) =
        StillFrameViewModel(renderer, encoder, sink, exports, dispatcher, "p1") { LocalDateTime.of(2026, 10, 7, 9, 5, 3) }

    private fun input(frame: Long = 10, w: Int = 3840, h: Int = 2160, timeline: com.ultimatevideo.uveditor.domain.Timeline = tl) =
        StillFrameInput("p1", "My movie", w, h, FrameRate(60, 1), timeline, assets, frame)

    /** Collects every effect the view model emits while [block] runs. */
    private fun StillFrameViewModel.collecting(block: () -> Unit): List<StillFrameEffect> {
        val seen = mutableListOf<StillFrameEffect>()
        val scope = kotlinx.coroutines.CoroutineScope(dispatcher)
        val job = scope.launch { effects.toList(seen) }
        block()
        job.cancel()
        return seen
    }

    @Test
    fun `one tap draws the frame at the project size and saves a named JPEG`() {
        val vm = viewModel()

        val effects = vm.collecting { vm.onIntent(StillFrameIntent.Save(input(frame = 83L * 60 + 12 - 83L * 60 + 10))) }

        val job = renderer.job!!
        assertEquals(22L, job.frame) // an integer project frame
        assertEquals(3840 to 2160, job.width to job.height)
        assertEquals(95, encoder.quality)
        assertEquals("My_movie_00h00m00s22f_20261007-090503.jpg", sink.saved.single().first)
        assertEquals(StillFrameEffect.Started, effects.first())
        val saved = (effects.last() as StillFrameEffect.Saved).frame
        assertEquals("Pictures/ultimateVE", saved.folder)
        assertTrue(saved.notes.isEmpty())
        assertTrue(vm.state.value.phase is StillFramePhase.Saved)
    }

    @Test
    fun `a project larger than the limit is saved smaller and the note says so`() {
        val vm = viewModel()

        val effects = vm.collecting { vm.onIntent(StillFrameIntent.Save(input(w = 7680, h = 4320))) }

        assertEquals(4096 to 2304, renderer.job!!.width to renderer.job!!.height)
        assertTrue((effects.last() as StillFrameEffect.Saved).frame.notes.single().contains("4096"))
    }

    @Test
    fun `a frame in a gap is saved black with a notice`() {
        val vm = viewModel()

        val effects = vm.collecting { vm.onIntent(StillFrameIntent.Save(input(frame = 45))) }

        assertTrue((effects.last() as StillFrameEffect.Saved).frame.notes.single().contains("gap"))
    }

    @Test
    fun `an empty project is refused without drawing`() {
        val vm = viewModel()

        val effects = vm.collecting { vm.onIntent(StillFrameIntent.Save(input(timeline = timeline()))) }

        assertNull(renderer.job)
        assertTrue((effects.single() as StillFrameEffect.Message).text.contains("nothing to save"))
        assertEquals(StillFramePhase.Idle, vm.state.value.phase)
    }

    @Test
    fun `an export that is running refuses the save with its name`() {
        val vm = viewModel(FakeExports(MutableStateFlow(ExportJobState.Running("p2", "Holiday", 100, 0))))

        val effects = vm.collecting { vm.onIntent(StillFrameIntent.Save(input())) }

        assertNull(renderer.job)
        assertTrue((effects.single() as StillFrameEffect.Message).text.contains("Holiday"))
    }

    @Test
    fun `an export of this project also refuses`() {
        val vm = viewModel(FakeExports(MutableStateFlow(ExportJobState.Running("p1", "My movie", 100, 0))))

        val effects = vm.collecting { vm.onIntent(StillFrameIntent.Save(input())) }

        assertNull(renderer.job)
        assertTrue((effects.single() as StillFrameEffect.Message).text.contains("exporting"))
    }

    @Test
    fun `an engine failure and the flat picture guard are shown and nothing is saved`() {
        renderer.failWith = StillFrameException("The picture came out as one flat colour although the frame has video. Nothing was saved; try again.")
        val vm = viewModel()

        val effects = vm.collecting { vm.onIntent(StillFrameIntent.Save(input())) }

        assertTrue(sink.saved.isEmpty())
        assertTrue((effects.last() as StillFrameEffect.Message).text.contains("flat colour"))
        assertTrue(vm.state.value.phase is StillFramePhase.Failed)

        renderer.failWith = ExportException(ExportErrorCode.CODEC_ERROR, "the decoder stalled")
        val more = vm.collecting { vm.onIntent(StillFrameIntent.Save(input())) }
        assertTrue((more.last() as StillFrameEffect.Message).text.contains("the decoder stalled"))
    }

    @Test
    fun `a gallery that refuses the file gives a clear error`() {
        sink.fail = true
        val vm = viewModel()

        val effects = vm.collecting { vm.onIntent(StillFrameIntent.Save(input())) }

        assertTrue((effects.last() as StillFrameEffect.Message).text.contains("could not be saved"))
        assertTrue(vm.state.value.phase is StillFramePhase.Failed)
    }

    @Test
    fun `a second tap while saving is ignored and cancel stops the save`() {
        renderer.gate = CompletableDeferred()
        val vm = viewModel()
        val effects = vm.collecting {
            vm.onIntent(StillFrameIntent.Save(input()))
            assertTrue(vm.state.value.isSaving)
            renderer.job = null
            vm.onIntent(StillFrameIntent.Save(input(frame = 5)))
            assertNull(renderer.job)
            vm.onIntent(StillFrameIntent.Cancel)
        }

        assertEquals(StillFramePhase.Idle, vm.state.value.phase)
        assertTrue(sink.saved.isEmpty())
        assertTrue((effects.last() as StillFrameEffect.Message).text.contains("cancelled"))
    }

    @Test
    fun `share and open act on the last saved picture`() {
        val vm = viewModel()
        vm.collecting { vm.onIntent(StillFrameIntent.Save(input())) }

        val effects = vm.collecting {
            vm.onIntent(StillFrameIntent.Share)
            vm.onIntent(StillFrameIntent.Open)
        }

        val uri = "content://media/${sink.saved.single().first}"
        assertEquals(listOf(StillFrameEffect.ShareFile(uri), StillFrameEffect.OpenFile(uri)), effects)
    }

    @Test
    fun `share before anything was saved does nothing`() = runTest {
        val vm = viewModel()

        val effects = vm.collecting { vm.onIntent(StillFrameIntent.Share) }

        assertFalse(effects.any { it is StillFrameEffect.ShareFile })
    }
}
