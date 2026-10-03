package com.ultimatevideo.uveditor.ui.editor.captions

import com.ultimatevideo.uveditor.domain.Clip
import com.ultimatevideo.uveditor.domain.FrameIndex
import com.ultimatevideo.uveditor.domain.FrameRate
import com.ultimatevideo.uveditor.domain.TitleAnimation
import com.ultimatevideo.uveditor.domain.captions.CaptionStyle
import com.ultimatevideo.uveditor.domain.captions.Transcript
import com.ultimatevideo.uveditor.domain.captions.TranscriptWord
import com.ultimatevideo.uveditor.engine.captions.CaptionModel
import com.ultimatevideo.uveditor.engine.captions.CaptionModelProvider
import com.ultimatevideo.uveditor.engine.captions.DownloadProgress
import com.ultimatevideo.uveditor.engine.captions.TranscribeRequest
import com.ultimatevideo.uveditor.engine.captions.Transcriber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
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

    private class InstalledModels : CaptionModelProvider {
        override fun isInstalled(model: CaptionModel) = true
        override fun download(model: CaptionModel): Flow<DownloadProgress> = emptyFlow()
        override fun delete(model: CaptionModel) = Unit
    }

    private class FixedTranscriber(private val result: Transcript) : Transcriber {
        override suspend fun transcribe(request: TranscribeRequest, onProgress: (Int) -> Unit): Transcript = result
    }

    private val clip = Clip("c1", "a1", FrameIndex(0), FrameIndex(0), FrameIndex(300))
    private val target = CaptionTarget(clip, "content://media/1", FrameRate(30, 1), canvasHeight = 1920)
    private val speech = Transcript("en", listOf(TranscriptWord("Say", 0, 400), TranscriptWord("it", 500, 800), TranscriptWord("loud", 900, 1_500)))

    private fun viewModel(transcript: Transcript = speech) = CaptionsViewModel(InstalledModels(), FixedTranscriber(transcript)) { "id" }

    @Test
    fun `picking a style resets the colour overrides of the previous one`() {
        val vm = viewModel()
        vm.onIntent(CaptionsIntent.Open(target))
        vm.onIntent(CaptionsIntent.SelectStyle("karaoke"))
        vm.onIntent(CaptionsIntent.SelectTextColor(0xFFFF0000.toInt()))
        vm.onIntent(CaptionsIntent.SelectHighlightColor(0xFF00FF00.toInt()))

        assertEquals(0xFFFF0000.toInt(), vm.state.value.style.colorArgb)
        assertEquals(0xFF00FF00.toInt(), vm.state.value.style.highlightArgb)
        assertEquals(TitleAnimation.KARAOKE, vm.state.value.style.animation)

        vm.onIntent(CaptionsIntent.SelectStyle("wordpop"))

        assertNull(vm.state.value.textColor)
        assertNull(vm.state.value.highlightColor)
        assertEquals(CaptionStyle.WORD_POP, vm.state.value.style)
    }

    @Test
    fun `the colour a user picked is used for the generated captions`() = runTest(dispatcher) {
        val vm = viewModel()
        val effects = ArrayList<CaptionsEffect>()
        backgroundScope.launch { vm.effects.toList(effects) }
        vm.onIntent(CaptionsIntent.Open(target))
        vm.onIntent(CaptionsIntent.SelectStyle("karaoke"))
        vm.onIntent(CaptionsIntent.SelectHighlightColor(0xFFFF4FD8.toInt()))

        vm.onIntent(CaptionsIntent.Generate)

        val clips = effects.filterIsInstance<CaptionsEffect.ClipsReady>().single().clips
        val title = clips.first().title!!
        assertEquals(TitleAnimation.KARAOKE, title.animation)
        assertEquals(0xFFFF4FD8.toInt(), title.highlightArgb)
        assertEquals(listOf("Say", "it", "loud"), title.words.map { it.text })
        assertEquals(0L, title.words.first().startFrame)
    }

    @Test
    fun `the sheet can be opened only to restyle and applying emits the chosen style`() = runTest(dispatcher) {
        val vm = viewModel()
        val effects = ArrayList<CaptionsEffect>()
        backgroundScope.launch { vm.effects.toList(effects) }

        vm.onIntent(CaptionsIntent.OpenRestyle(existingCaptions = 5, canvasHeight = 1080))

        assertTrue(vm.state.value.isOpen)
        assertTrue(vm.state.value.restyleOnly)
        assertNull(vm.state.value.target)
        assertEquals(5, vm.state.value.existingCaptions)

        vm.onIntent(CaptionsIntent.SelectStyle("bounce"))
        vm.onIntent(CaptionsIntent.SelectTextColor(0xFF39FF14.toInt()))
        vm.onIntent(CaptionsIntent.ApplyToExisting)

        val restyle = effects.filterIsInstance<CaptionsEffect.Restyle>().single()
        assertEquals("bounce", restyle.style.id)
        assertEquals(0xFF39FF14.toInt(), restyle.style.colorArgb)
        assertEquals(1080, restyle.canvasHeight)
        assertFalse(vm.state.value.isOpen)
    }

    @Test
    fun `restyling from a clip sheet uses the clip's canvas and does nothing without captions`() = runTest(dispatcher) {
        val vm = viewModel()
        val effects = ArrayList<CaptionsEffect>()
        backgroundScope.launch { vm.effects.toList(effects) }

        vm.onIntent(CaptionsIntent.Open(target, existingCaptions = 0))
        vm.onIntent(CaptionsIntent.ApplyToExisting)
        assertTrue(effects.none { it is CaptionsEffect.Restyle })
        assertTrue(vm.state.value.isOpen)

        vm.onIntent(CaptionsIntent.Open(target, existingCaptions = 3))
        vm.onIntent(CaptionsIntent.ApplyToExisting)

        assertEquals(1920, effects.filterIsInstance<CaptionsEffect.Restyle>().single().canvasHeight)
        assertFalse(vm.state.value.isOpen)
    }

    @Test
    fun `closing leaves restyle mode`() {
        val vm = viewModel()
        vm.onIntent(CaptionsIntent.OpenRestyle(2, 1080))

        vm.onIntent(CaptionsIntent.Close)

        assertFalse(vm.state.value.isOpen)
        assertFalse(vm.state.value.restyleOnly)
    }
}
