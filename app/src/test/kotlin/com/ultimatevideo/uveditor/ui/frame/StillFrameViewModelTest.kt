package com.ultimatevideo.uveditor.ui.frame

import com.ultimatevideo.uveditor.data.model.MediaAssetDto
import com.ultimatevideo.uveditor.domain.FrameRate
import com.ultimatevideo.uveditor.domain.ProjectColorSpace
import com.ultimatevideo.uveditor.domain.clip
import com.ultimatevideo.uveditor.domain.stillframe.FrameContent
import com.ultimatevideo.uveditor.domain.stillframe.FrameFit
import com.ultimatevideo.uveditor.domain.stillframe.FrameFormat
import com.ultimatevideo.uveditor.domain.stillframe.FrameSizePreset
import com.ultimatevideo.uveditor.domain.stillframe.FrameSource
import com.ultimatevideo.uveditor.domain.stillframe.SelectedClipStatus
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer

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
            val w = job.plan.outWidth
            val h = job.plan.outHeight
            return RenderedFrame(w, h, ByteBuffer.allocateDirect(w * h * 4))
        }
    }

    private class FakeEncoder : FrameEncoder {
        var format: FrameFormat? = null
        var quality = 0
        var maxBytes: Long? = null
        var result = EncodedFrame(ByteArray(1234), null, true)

        override fun encode(frame: RenderedFrame, format: FrameFormat, quality: Int, maxBytes: Long?): EncodedFrame {
            this.format = format
            this.quality = quality
            this.maxBytes = maxBytes
            return result
        }
    }

    private class FakeSink : FrameSink {
        val written = mutableMapOf<String, ByteArray>()
        val deleted = mutableListOf<String>()
        var failWrite = false

        override fun write(uri: String, bytes: ByteArray) {
            if (failWrite) throw IOException("disk full")
            written[uri] = bytes
        }

        override fun delete(uri: String): Boolean {
            deleted += uri
            return true
        }

        override fun displayName(uri: String): String? = "saved.png"
    }

    private class FakeExports(initial: ExportJobState = ExportJobState.Idle) : ExportJobHost {
        override val state: StateFlow<ExportJobState> = MutableStateFlow(initial)
        override fun cancel() = Unit
        override fun acknowledge(onlyProject: String?) = Unit
    }

    private val renderer = FakeRenderer()
    private val encoder = FakeEncoder()
    private val sink = FakeSink()

    private fun viewModel(exports: ExportJobHost = FakeExports()) =
        StillFrameViewModel(renderer, encoder, sink, exports, dispatcher, projectId = "p1")

    private val assets = listOf(MediaAssetDto("a", "content://a", 600, 30, 1, "Rec709-SDR"))
    private val twoClips = timeline(track("v1", clip("c1", 0, 30), clip("c2", 60, 30)))

    private fun input(frame: Long = 10, selected: String? = null, hdr: Boolean = false) = StillFrameInput(
        projectId = "p1",
        projectName = "My movie",
        projectWidth = 1920,
        projectHeight = 1080,
        fps = FrameRate(30, 1),
        timeline = twoClips,
        assets = assets,
        colorSpace = if (hdr) ProjectColorSpace.REC2020_HLG else ProjectColorSpace.REC709_SDR,
        frame = frame,
        selectedClipId = selected,
    )

    private fun StillFrameViewModel.openAndSave(frame: Long = 10, selected: String? = null, uri: String = "content://out/frame.png") {
        onIntent(StillFrameIntent.Open(input(frame, selected)))
        onIntent(StillFrameIntent.LocationChosen(uri))
    }

    @Test
    fun `opening shows the frame's timecode and what it contains`() {
        val vm = viewModel()

        vm.onIntent(StillFrameIntent.Open(input(frame = 45)))

        val state = vm.state.value
        assertTrue(state.visible)
        assertEquals("00:00:01:15", state.timecode)
        assertEquals(FrameContent.GAP, state.content)
        assertEquals(FrameSource.WHOLE_PICTURE, state.source)
        assertEquals(SelectedClipStatus.NO_SELECTION, state.clipStatus)
    }

    @Test
    fun `the clip source is refused without a usable selection and accepted with one`() {
        val vm = viewModel()
        vm.onIntent(StillFrameIntent.Open(input(frame = 10)))
        vm.onIntent(StillFrameIntent.SelectSource(FrameSource.SELECTED_CLIP))
        assertEquals(FrameSource.WHOLE_PICTURE, vm.state.value.source)

        vm.onIntent(StillFrameIntent.Open(input(frame = 10, selected = "c2"))) // playhead outside the selected clip
        assertEquals(SelectedClipStatus.OUTSIDE_CLIP, vm.state.value.clipStatus)
        vm.onIntent(StillFrameIntent.SelectSource(FrameSource.SELECTED_CLIP))
        assertEquals(FrameSource.WHOLE_PICTURE, vm.state.value.source)

        vm.onIntent(StillFrameIntent.Open(input(frame = 10, selected = "c1")))
        vm.onIntent(StillFrameIntent.SelectSource(FrameSource.SELECTED_CLIP))
        assertEquals(FrameSource.SELECTED_CLIP, vm.state.value.source)
    }

    @Test
    fun `the YouTube preset switches to JPEG and has a size limit`() {
        val vm = viewModel()
        vm.onIntent(StillFrameIntent.Open(input()))

        vm.onIntent(StillFrameIntent.SelectPreset(FrameSizePreset.YOUTUBE_THUMBNAIL))

        val state = vm.state.value
        assertEquals(FrameFormat.JPEG, state.format)
        assertEquals(2_000_000L, state.sizeLimit)
        assertEquals(92, state.jpegQuality)
        assertFalse(state.shapeDiffers) // 1280 x 720 is the project's 16:9
    }

    @Test
    fun `the shape choice matters only when the shapes differ`() {
        val vm = viewModel()
        vm.onIntent(StillFrameIntent.Open(input()))
        vm.onIntent(StillFrameIntent.SelectPreset(FrameSizePreset.VERTICAL_COVER))
        vm.onIntent(StillFrameIntent.SelectFit(FrameFit.FILL))

        assertTrue(vm.state.value.shapeDiffers)
        assertTrue(vm.state.value.renderPlan.isCropped)
        assertEquals(1080 to 1920, vm.state.value.renderPlan.outWidth to vm.state.value.renderPlan.outHeight)
    }

    @Test
    fun `quality is kept in range and custom width is accepted`() {
        val vm = viewModel()
        vm.onIntent(StillFrameIntent.Open(input()))

        vm.onIntent(StillFrameIntent.SetQuality(500))
        assertEquals(100, vm.state.value.jpegQuality)
        vm.onIntent(StillFrameIntent.SetQuality(-3))
        assertEquals(1, vm.state.value.jpegQuality)
        vm.onIntent(StillFrameIntent.SelectPreset(FrameSizePreset.CUSTOM))
        vm.onIntent(StillFrameIntent.SetCustomWidth(1000))
        assertEquals(1000 to 563, vm.state.value.target.size.width to vm.state.value.target.size.height)
    }

    @Test
    fun `choosing a location asks for a named file of the chosen format`() = runTest {
        val vm = viewModel()
        vm.onIntent(StillFrameIntent.Open(input(frame = 45)))
        vm.onIntent(StillFrameIntent.SelectFormat(FrameFormat.JPEG))

        vm.onIntent(StillFrameIntent.ChooseLocation)

        assertEquals(StillFrameEffect.LaunchCreateDocument("My movie frame 00-00-01-15.jpg", "image/jpeg"), vm.effects.first())
    }

    @Test
    fun `dismissing the picker draws nothing`() {
        val vm = viewModel()
        vm.onIntent(StillFrameIntent.Open(input()))

        vm.onIntent(StillFrameIntent.LocationChosen(null))

        assertNull(renderer.job)
        assertEquals(StillFramePhase.Configuring, vm.state.value.phase)
    }

    @Test
    fun `saving draws the frame at the planned size, encodes it and writes it`() {
        val vm = viewModel()

        vm.openAndSave(frame = 10)

        val job = renderer.job!!
        assertEquals(10L, job.frame) // an integer project frame
        assertEquals(1920 to 1080, job.plan.outWidth to job.plan.outHeight)
        assertEquals(twoClips, job.timeline)
        assertEquals(FrameFormat.PNG, encoder.format)
        assertNotNull(sink.written["content://out/frame.png"])
        val saved = vm.state.value.phase as StillFramePhase.Saved
        assertEquals(1920 to 1080, saved.width to saved.height)
        assertEquals(1234L, saved.bytes)
        assertTrue(saved.notes.isEmpty())
    }

    @Test
    fun `the clip source draws only that clip`() {
        val vm = viewModel()
        vm.onIntent(StillFrameIntent.Open(input(frame = 70, selected = "c2")))
        vm.onIntent(StillFrameIntent.SelectSource(FrameSource.SELECTED_CLIP))

        vm.onIntent(StillFrameIntent.LocationChosen("content://out/f.png"))

        assertEquals(listOf("c2"), renderer.job!!.timeline.tracks.flatMap { it.clips }.map { it.id })
        assertTrue(renderer.job!!.timeline.transitions.isEmpty())
    }

    @Test
    fun `a JPEG is encoded with the quality and the YouTube limit`() {
        val vm = viewModel()
        vm.onIntent(StillFrameIntent.Open(input()))
        vm.onIntent(StillFrameIntent.SelectPreset(FrameSizePreset.YOUTUBE_THUMBNAIL))
        vm.onIntent(StillFrameIntent.SetQuality(85))
        encoder.result = EncodedFrame(ByteArray(1_900_000), 63, true)

        vm.onIntent(StillFrameIntent.LocationChosen("content://out/t.jpg"))

        assertEquals(FrameFormat.JPEG, encoder.format)
        assertEquals(85, encoder.quality)
        assertEquals(2_000_000L, encoder.maxBytes)
        assertEquals(1280 to 720, renderer.job!!.plan.outWidth to renderer.job!!.plan.outHeight)
        val saved = vm.state.value.phase as StillFramePhase.Saved
        assertEquals(63, saved.quality)
        assertTrue(saved.notes.single().contains("lowered to 63"))
    }

    @Test
    fun `a file that cannot be made small enough says so`() {
        val vm = viewModel()
        vm.onIntent(StillFrameIntent.Open(input()))
        vm.onIntent(StillFrameIntent.SelectPreset(FrameSizePreset.YOUTUBE_THUMBNAIL))
        encoder.result = EncodedFrame(ByteArray(2_500_000), 1, false)

        vm.onIntent(StillFrameIntent.LocationChosen("content://out/t.jpg"))

        assertTrue((vm.state.value.phase as StillFramePhase.Saved).notes.any { it.contains("still over 2 MB") })
    }

    @Test
    fun `an oversize YouTube PNG is flagged`() {
        val vm = viewModel()
        vm.onIntent(StillFrameIntent.Open(input()))
        vm.onIntent(StillFrameIntent.SelectPreset(FrameSizePreset.YOUTUBE_THUMBNAIL))
        vm.onIntent(StillFrameIntent.SelectFormat(FrameFormat.PNG))
        encoder.result = EncodedFrame(ByteArray(3_000_000), null, true)

        vm.onIntent(StillFrameIntent.LocationChosen("content://out/t.png"))

        assertNull(encoder.maxBytes)
        assertTrue((vm.state.value.phase as StillFramePhase.Saved).notes.single().contains("Save it as JPEG"))
    }

    @Test
    fun `an engine failure shows the reason and removes the empty file`() {
        renderer.failWith = ExportException(ExportErrorCode.CODEC_ERROR, "the decoder stalled")
        val vm = viewModel()

        vm.openAndSave()

        val failed = vm.state.value.phase as StillFramePhase.Failed
        assertTrue(failed.message.contains("the decoder stalled"))
        assertEquals(listOf("content://out/frame.png"), sink.deleted)
    }

    @Test
    fun `a write failure is reported and the file removed`() {
        sink.failWrite = true
        val vm = viewModel()

        vm.openAndSave()

        assertTrue((vm.state.value.phase as StillFramePhase.Failed).message.contains("disk full"))
        assertEquals(1, sink.deleted.size)
    }

    @Test
    fun `back returns from a failure to the settings`() {
        renderer.failWith = StillFrameException("There is nothing to save yet.")
        val vm = viewModel()
        vm.openAndSave()

        vm.onIntent(StillFrameIntent.Back)

        assertEquals(StillFramePhase.Configuring, vm.state.value.phase)
        assertTrue(vm.state.value.visible)
    }

    @Test
    fun `cancelling a running save stops it and deletes the file`() {
        renderer.gate = CompletableDeferred()
        val vm = viewModel()
        vm.openAndSave()
        assertTrue(vm.state.value.isWorking)

        vm.onIntent(StillFrameIntent.Cancel)

        assertEquals(StillFramePhase.Configuring, vm.state.value.phase)
        assertEquals(listOf("content://out/frame.png"), sink.deleted)
        assertTrue(sink.written.isEmpty())
    }

    @Test
    fun `the dialog cannot be dismissed while saving`() {
        renderer.gate = CompletableDeferred()
        val vm = viewModel()
        vm.openAndSave()

        vm.onIntent(StillFrameIntent.Dismiss)

        assertTrue(vm.state.value.visible)
        assertTrue(vm.state.value.isWorking)
    }

    @Test
    fun `an export that is running refuses the dialog with its name`() = runTest {
        val running = ExportJobState.Running("p2", "Holiday", 100, 0)
        val vm = viewModel(FakeExports(running))

        vm.onIntent(StillFrameIntent.Open(input()))

        assertFalse(vm.state.value.visible)
        val message = vm.effects.first() as StillFrameEffect.Message
        assertTrue(message.text.contains("Holiday"))
    }

    @Test
    fun `an export of this project also refuses`() = runTest {
        val vm = viewModel(FakeExports(ExportJobState.Running("p1", "My movie", 100, 0)))

        vm.onIntent(StillFrameIntent.Open(input()))

        assertFalse(vm.state.value.visible)
        assertTrue((vm.effects.first() as StillFrameEffect.Message).text.contains("exporting"))
    }

    @Test
    fun `an export that started after the dialog opened refuses the save and removes the file`() {
        val exports = MutableStateFlow<ExportJobState>(ExportJobState.Idle)
        val host = object : ExportJobHost {
            override val state: StateFlow<ExportJobState> = exports
            override fun cancel() = Unit
            override fun acknowledge(onlyProject: String?) = Unit
        }
        val vm = viewModel(host)
        vm.onIntent(StillFrameIntent.Open(input()))
        exports.value = ExportJobState.Running("p2", "Holiday", 0, 0)

        vm.onIntent(StillFrameIntent.LocationChosen("content://out/frame.png"))

        assertNull(renderer.job)
        assertTrue(vm.state.value.phase is StillFramePhase.Failed)
        assertEquals(listOf("content://out/frame.png"), sink.deleted)
    }

    @Test
    fun `an HLG project is described as converted to SDR`() {
        val vm = viewModel()

        vm.onIntent(StillFrameIntent.Open(input(hdr = true)))

        assertTrue(vm.state.value.hdrProject)
    }

    @Test
    fun `a project frame past the end or in a gap carries a notice`() {
        val vm = viewModel()
        vm.onIntent(StillFrameIntent.Open(input(frame = 500)))
        assertEquals(FrameContent.PAST_END, vm.state.value.content)
        assertTrue(frameNotice(vm.state.value)!!.contains("after the end"))

        vm.onIntent(StillFrameIntent.Open(input(frame = 40)))
        assertTrue(frameNotice(vm.state.value)!!.contains("gap"))

        vm.onIntent(StillFrameIntent.Open(input(frame = 0)))
        assertNull(frameNotice(vm.state.value))
    }

    @Test
    fun `settings are frozen while saving`() {
        renderer.gate = CompletableDeferred()
        val vm = viewModel()
        vm.openAndSave()

        vm.onIntent(StillFrameIntent.SelectFormat(FrameFormat.JPEG))

        assertEquals(FrameFormat.PNG, vm.state.value.format)
    }
}
