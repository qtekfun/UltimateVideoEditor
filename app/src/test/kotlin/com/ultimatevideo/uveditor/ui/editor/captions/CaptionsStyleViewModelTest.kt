package com.ultimatevideo.uveditor.ui.editor.captions

import com.ultimatevideo.uveditor.domain.FrameRate
import com.ultimatevideo.uveditor.domain.captions.CAPTION_ID_PREFIX
import com.ultimatevideo.uveditor.domain.captions.CaptionStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
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

@OptIn(ExperimentalCoroutinesApi::class)
class CaptionsStyleViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val fps = FrameRate(30, 1)

    private class FakeSource(private val files: Map<String, ByteArray> = emptyMap()) : SubtitleSource {
        override suspend fun read(uri: String): ByteArray = files[uri] ?: throw IOException("missing $uri")
    }

    private fun viewModel(files: Map<String, ByteArray> = emptyMap()) =
        CaptionsViewModel(FakeSource(files), dispatcher) { "id" }

    private fun CaptionsViewModel.open(playhead: Long = 90, existing: Int = 0) =
        onIntent(CaptionsIntent.Open(fps, canvasHeight = 1080, playhead = playhead, existingCaptions = existing))

    private val srt = """
        1
        00:00:01,000 --> 00:00:02,500
        Hello <i>there</i>

        2
        00:00:03,000 --> 00:00:04,000
        General Kenobi
    """.trimIndent().toByteArray()

    @Test
    fun `opening starts the draft at the playhead with a two second length`() {
        val vm = viewModel()
        vm.open(playhead = 90)

        val state = vm.state.value
        assertTrue(state.isOpen)
        assertEquals(90L, state.draftStart)
        assertEquals(60L, state.draftLength)
        assertFalse(state.canAdd)
    }

    @Test
    fun `nudging moves the start and length by frames and seconds and never below the minimum`() {
        val vm = viewModel()
        vm.open(playhead = 10)

        vm.onIntent(CaptionsIntent.NudgeStart(-30))
        assertEquals(0L, vm.state.value.draftStart)
        vm.onIntent(CaptionsIntent.NudgeStart(5))
        assertEquals(5L, vm.state.value.draftStart)
        vm.onIntent(CaptionsIntent.NudgeLength(-1000))
        assertEquals(1L, vm.state.value.draftLength)
    }

    @Test
    fun `a typed caption becomes one styled clip with evenly timed words and the next one starts after it`() = runTest(dispatcher) {
        val vm = viewModel()
        val effects = mutableListOf<CaptionsEffect>()
        val job = launch { vm.effects.toList(effects) }
        vm.open(playhead = 30)
        vm.onIntent(CaptionsIntent.SelectStyle(CaptionStyle.KARAOKE.id))
        vm.onIntent(CaptionsIntent.SetDraftText("  Hello brave world  "))
        vm.onIntent(CaptionsIntent.AddDraft)

        val ready = effects.filterIsInstance<CaptionsEffect.ClipsReady>().single()
        assertTrue(ready.intoExistingTrack)
        val clip = ready.clips.single()
        assertTrue(clip.id.startsWith(CAPTION_ID_PREFIX))
        assertEquals(30L, clip.timelineStart.value)
        assertEquals(60L, clip.durationFrames)
        val title = checkNotNull(clip.title)
        assertEquals("Hello brave world", title.text)
        assertEquals(listOf("Hello", "brave", "world"), title.words.map { it.text })
        assertEquals(clip.durationFrames, title.words.last().endFrame)
        // Ready for the next caption: it starts where this one ended, with an empty text box.
        assertEquals(90L, vm.state.value.draftStart)
        assertEquals("", vm.state.value.draftText)
        assertEquals(1, vm.state.value.existingCaptions)
        job.cancel()
    }

    @Test
    fun `adding needs text`() = runTest(dispatcher) {
        val vm = viewModel()
        val effects = mutableListOf<CaptionsEffect>()
        val job = launch { vm.effects.toList(effects) }
        vm.open()
        vm.onIntent(CaptionsIntent.SetDraftText("   "))
        vm.onIntent(CaptionsIntent.AddDraft)
        assertTrue(effects.isEmpty())
        job.cancel()
    }

    @Test
    fun `importing a subtitle file places its captions from the start of the project on a new track`() = runTest(dispatcher) {
        val vm = viewModel(mapOf("content://subs" to srt))
        val effects = mutableListOf<CaptionsEffect>()
        val job = launch { vm.effects.toList(effects) }
        vm.open(playhead = 300)
        vm.onIntent(CaptionsIntent.ImportFile("content://subs"))

        val ready = effects.filterIsInstance<CaptionsEffect.ClipsReady>().single()
        assertFalse(ready.intoExistingTrack)
        assertEquals(listOf(30L to 75L, 90L to 120L), ready.clips.map { it.timelineStart.value to it.timelineEnd.value })
        assertEquals("Hello there", ready.clips.first().title?.text)
        assertTrue(effects.filterIsInstance<CaptionsEffect.ShowMessage>().single().text.startsWith("Added 2 captions"))
        assertFalse(vm.state.value.isOpen)
        assertFalse(vm.state.value.importing)
        job.cancel()
    }

    @Test
    fun `an imported file can start at the playhead`() = runTest(dispatcher) {
        val vm = viewModel(mapOf("content://subs" to srt))
        val effects = mutableListOf<CaptionsEffect>()
        val job = launch { vm.effects.toList(effects) }
        vm.open(playhead = 300)
        vm.onIntent(CaptionsIntent.SetImportAtPlayhead(true))
        vm.onIntent(CaptionsIntent.ImportFile("content://subs"))

        val ready = effects.filterIsInstance<CaptionsEffect.ClipsReady>().single()
        assertEquals(330L, ready.clips.first().timelineStart.value)
        job.cancel()
    }

    @Test
    fun `a file with no subtitles or one that cannot be read shows an error and adds nothing`() = runTest(dispatcher) {
        val vm = viewModel(mapOf("content://empty" to "hello, this is not a subtitle file".toByteArray()))
        val effects = mutableListOf<CaptionsEffect>()
        val job = launch { vm.effects.toList(effects) }
        vm.open()

        vm.onIntent(CaptionsIntent.ImportFile("content://empty"))
        assertNotNull(vm.state.value.error)
        assertFalse(vm.state.value.importing)

        vm.onIntent(CaptionsIntent.ImportFile("content://gone"))
        assertEquals("missing content://gone", vm.state.value.error)
        assertTrue(effects.none { it is CaptionsEffect.ClipsReady })
        assertTrue(vm.state.value.isOpen)
        job.cancel()
    }

    @Test
    fun `picking a style resets the colour overrides of the previous one`() {
        val vm = viewModel()
        vm.open()
        vm.onIntent(CaptionsIntent.SelectStyle(CaptionStyle.KARAOKE.id))
        vm.onIntent(CaptionsIntent.SelectTextColor(0xFF00FF00.toInt()))
        vm.onIntent(CaptionsIntent.SelectHighlightColor(0xFFFF00FF.toInt()))
        assertEquals(0xFF00FF00.toInt(), vm.state.value.style.colorArgb)
        assertEquals(0xFFFF00FF.toInt(), vm.state.value.style.highlightArgb)

        vm.onIntent(CaptionsIntent.SelectStyle(CaptionStyle.BOLD.id))
        assertNull(vm.state.value.textColor)
        assertNull(vm.state.value.highlightColor)
        assertEquals(CaptionStyle.BOLD.colorArgb, vm.state.value.style.colorArgb)
    }

    @Test
    fun `the colour a user picked is used for typed captions`() = runTest(dispatcher) {
        val vm = viewModel()
        val effects = mutableListOf<CaptionsEffect>()
        val job = launch { vm.effects.toList(effects) }
        vm.open()
        vm.onIntent(CaptionsIntent.SelectTextColor(0xFF123456.toInt()))
        vm.onIntent(CaptionsIntent.SetDraftText("Colour"))
        vm.onIntent(CaptionsIntent.AddDraft)

        val clip = effects.filterIsInstance<CaptionsEffect.ClipsReady>().single().clips.single()
        assertEquals(0xFF123456.toInt(), clip.title?.colorArgb)
        job.cancel()
    }

    @Test
    fun `applying a style to existing captions emits it with the canvas height and closes the sheet`() = runTest(dispatcher) {
        val vm = viewModel()
        val effects = mutableListOf<CaptionsEffect>()
        val job = launch { vm.effects.toList(effects) }
        vm.open(existing = 3)
        vm.onIntent(CaptionsIntent.SelectStyle(CaptionStyle.BOUNCE.id))
        vm.onIntent(CaptionsIntent.ApplyToExisting)

        val restyle = effects.filterIsInstance<CaptionsEffect.Restyle>().single()
        assertEquals(CaptionStyle.BOUNCE.id, restyle.style.id)
        assertEquals(1080, restyle.canvasHeight)
        assertFalse(vm.state.value.isOpen)
        job.cancel()
    }

    @Test
    fun `restyling does nothing when there are no captions`() = runTest(dispatcher) {
        val vm = viewModel()
        val effects = mutableListOf<CaptionsEffect>()
        val job = launch { vm.effects.toList(effects) }
        vm.open(existing = 0)
        vm.onIntent(CaptionsIntent.ApplyToExisting)
        assertTrue(effects.isEmpty())
        assertTrue(vm.state.value.isOpen)
        job.cancel()
    }

    @Test
    fun `closing hides the sheet and clears the error`() {
        val vm = viewModel()
        vm.open()
        vm.onIntent(CaptionsIntent.Close)
        assertFalse(vm.state.value.isOpen)
        assertNull(vm.state.value.error)
    }
}
