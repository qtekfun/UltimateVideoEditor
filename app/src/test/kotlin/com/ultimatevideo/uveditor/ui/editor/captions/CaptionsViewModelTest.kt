package com.ultimatevideo.uveditor.ui.editor.captions

import com.ultimatevideo.uveditor.data.model.MediaAssetDto
import com.ultimatevideo.uveditor.domain.Clip
import com.ultimatevideo.uveditor.domain.FrameIndex
import com.ultimatevideo.uveditor.domain.FrameRate
import com.ultimatevideo.uveditor.domain.TitleContent
import com.ultimatevideo.uveditor.domain.Timeline
import com.ultimatevideo.uveditor.domain.Track
import com.ultimatevideo.uveditor.domain.TrackType
import com.ultimatevideo.uveditor.domain.captions.CaptionStyle
import com.ultimatevideo.uveditor.domain.captions.Transcript
import com.ultimatevideo.uveditor.domain.captions.TranscriptWord
import com.ultimatevideo.uveditor.engine.captions.CaptionErrorCode
import com.ultimatevideo.uveditor.engine.captions.CaptionException
import com.ultimatevideo.uveditor.engine.captions.CaptionLanguage
import com.ultimatevideo.uveditor.engine.captions.CaptionModel
import com.ultimatevideo.uveditor.engine.captions.CaptionModelProvider
import com.ultimatevideo.uveditor.engine.captions.CaptionModels
import com.ultimatevideo.uveditor.engine.captions.DownloadProgress
import com.ultimatevideo.uveditor.engine.captions.TranscribeRequest
import com.ultimatevideo.uveditor.engine.captions.Transcriber
import com.ultimatevideo.uveditor.ui.editor.EditorState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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

@OptIn(ExperimentalCoroutinesApi::class)
class CaptionsViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val fps = FrameRate(30, 1)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeModels(installed: Set<String> = emptySet(), private val failure: CaptionException? = null) : CaptionModelProvider {
        private val installedIds = installed.toMutableSet()
        val downloads = ArrayList<String>()
        val deleted = ArrayList<String>()

        /** Called after each step of a download, to look at the state as it is at that moment. */
        var probe: () -> Unit = {}

        override fun isInstalled(model: CaptionModel) = model.id in installedIds

        override fun download(model: CaptionModel): Flow<DownloadProgress> = flow {
            downloads += model.id
            failure?.let { throw it }
            probe()
            emit(DownloadProgress(0, 100))
            emit(DownloadProgress(50, 100))
            probe()
            installedIds += model.id
            emit(DownloadProgress(100, 100))
        }

        override fun delete(model: CaptionModel) {
            installedIds -= model.id
            deleted += model.id
        }
    }

    private class FakeTranscriber(
        var result: Transcript = Transcript("en", emptyList()),
        var failure: CaptionException? = null,
        private val gate: CompletableDeferred<Unit>? = null,
    ) : Transcriber {
        val requests = ArrayList<TranscribeRequest>()
        var cancelled = false
        var probe: () -> Unit = {}

        override suspend fun transcribe(request: TranscribeRequest, onProgress: (Int) -> Unit): Transcript {
            requests += request
            probe()
            onProgress(40)
            try {
                gate?.await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled = true
                throw e
            }
            failure?.let { throw it }
            onProgress(100)
            return result
        }
    }

    // A video clip showing source frames 300..600 (10 s to 20 s) at timeline frame 90.
    private val clip = Clip("c1", "a1", FrameIndex(90), FrameIndex(300), FrameIndex(600))
    private val target = CaptionTarget(clip, "content://media/1", fps, canvasHeight = 1920)

    private val speech = Transcript(
        "en",
        listOf(
            TranscriptWord("Hello", 10_500, 10_900),
            TranscriptWord("there.", 11_000, 11_500),
            TranscriptWord("Subscribe", 13_000, 13_600),
        ),
    )

    private var next = 0
    private fun viewModel(models: CaptionModelProvider, transcriber: Transcriber) =
        CaptionsViewModel(models, transcriber) { "id${next++}" }

    @Test
    fun `opening shows the target and which models are installed`() {
        val vm = viewModel(FakeModels(installed = setOf("base")), FakeTranscriber())

        vm.onIntent(CaptionsIntent.Open(target))

        val state = vm.state.value
        assertTrue(state.isOpen)
        assertEquals(target, state.target)
        assertEquals(listOf("base" to true, "tiny" to false), state.models.map { it.model.id to it.installed })
        assertEquals(CaptionLanguage.AUTO, state.languageCode)
    }

    @Test
    fun `generating transcribes the clip's source range and hands the captions to the editor`() = runTest(dispatcher) {
        val transcriber = FakeTranscriber(result = speech)
        val vm = viewModel(FakeModels(installed = setOf("base")), transcriber)
        val effects = ArrayList<CaptionsEffect>()
        backgroundScope.launch { vm.effects.toList(effects) }
        vm.onIntent(CaptionsIntent.Open(target))
        vm.onIntent(CaptionsIntent.SelectLanguage("es"))
        vm.onIntent(CaptionsIntent.SelectStyle("pop"))

        vm.onIntent(CaptionsIntent.Generate)

        val request = transcriber.requests.single()
        assertEquals("content://media/1", request.assetUri)
        assertEquals(10_000L, request.startMs)
        assertEquals(20_000L, request.endMs)
        assertEquals("es", request.language)
        assertEquals(CaptionModels.BASE, request.model)

        val ready = effects.filterIsInstance<CaptionsEffect.ClipsReady>().single()
        assertEquals(2, ready.clips.size) // "Hello there." then "Subscribe"
        assertEquals(listOf("Hello there.", "Subscribe"), ready.clips.map { it.title?.text })
        // 10.5 s of source is frame 315 -> timeline 90 + (315 - 300) = 105.
        assertEquals(105L, ready.clips[0].timelineStart.value)
        assertTrue(ready.clips.all { it.title?.outline == true && it.assetId == null })
        assertEquals(CaptionStyle.POP.titleFor("x").colorArgb, ready.clips[0].title?.colorArgb)
        assertEquals(CaptionStyle.POP.anchorY * 1920, ready.clips[0].transform.positionY, 1e-9)

        assertTrue(effects.any { it is CaptionsEffect.ShowMessage })
        assertFalse(vm.state.value.isOpen)
        assertEquals(CaptionPhase.IDLE, vm.state.value.phase)
    }

    @Test
    fun `a missing model is downloaded first and the download progress is shown`() = runTest(dispatcher) {
        val models = FakeModels()
        val transcriber = FakeTranscriber(result = speech)
        val vm = viewModel(models, transcriber)
        val snapshots = ArrayList<Pair<CaptionPhase, Int>>()
        models.probe = { snapshots += vm.state.value.phase to vm.state.value.progress }
        transcriber.probe = { snapshots += vm.state.value.phase to vm.state.value.progress }
        vm.onIntent(CaptionsIntent.Open(target))
        vm.onIntent(CaptionsIntent.SelectModel("tiny"))

        vm.onIntent(CaptionsIntent.Generate)

        assertEquals(listOf("tiny"), models.downloads)
        assertEquals(CaptionModels.TINY, transcriber.requests.single().model)
        // Downloading from 0 %, then 50 % into the download, then transcribing from 0 %.
        assertEquals(
            listOf(CaptionPhase.DOWNLOADING to 0, CaptionPhase.DOWNLOADING to 50, CaptionPhase.TRANSCRIBING to 0),
            snapshots,
        )
        assertTrue(vm.state.value.models.first { it.model.id == "tiny" }.installed)
    }

    @Test
    fun `a failed download shows the error and never transcribes`() {
        val models = FakeModels(failure = CaptionException(CaptionErrorCode.Download, "Could not download the model: offline"))
        val transcriber = FakeTranscriber(result = speech)
        val vm = viewModel(models, transcriber)
        vm.onIntent(CaptionsIntent.Open(target))

        vm.onIntent(CaptionsIntent.Generate)

        assertEquals("Could not download the model: offline", vm.state.value.error)
        assertEquals(CaptionPhase.IDLE, vm.state.value.phase)
        assertTrue(transcriber.requests.isEmpty())
        assertTrue(vm.state.value.isOpen)
    }

    @Test
    fun `a transcription failure is shown and the sheet stays open to retry`() {
        val transcriber = FakeTranscriber(failure = CaptionException(CaptionErrorCode.CodecError, "The clip's audio could not be decoded or recognised"))
        val vm = viewModel(FakeModels(installed = setOf("base")), transcriber)
        vm.onIntent(CaptionsIntent.Open(target))

        vm.onIntent(CaptionsIntent.Generate)

        assertEquals("The clip's audio could not be decoded or recognised", vm.state.value.error)
        assertTrue(vm.state.value.isOpen)
        assertEquals(CaptionPhase.IDLE, vm.state.value.phase)

        transcriber.failure = null
        transcriber.result = speech
        vm.onIntent(CaptionsIntent.Generate)
        assertNull(vm.state.value.error)
        assertFalse(vm.state.value.isOpen)
    }

    @Test
    fun `a clip with no speech reports it and adds nothing`() = runTest(dispatcher) {
        val vm = viewModel(FakeModels(installed = setOf("base")), FakeTranscriber(result = Transcript("en", emptyList())))
        val effects = ArrayList<CaptionsEffect>()
        backgroundScope.launch { vm.effects.toList(effects) }
        vm.onIntent(CaptionsIntent.Open(target))

        vm.onIntent(CaptionsIntent.Generate)

        assertEquals("No speech was found in this clip", vm.state.value.error)
        assertTrue(effects.none { it is CaptionsEffect.ClipsReady })
    }

    @Test
    fun `cancelling stops the transcription and adds nothing`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val transcriber = FakeTranscriber(result = speech, gate = gate)
        val vm = viewModel(FakeModels(installed = setOf("base")), transcriber)
        val effects = ArrayList<CaptionsEffect>()
        backgroundScope.launch { vm.effects.toList(effects) }
        vm.onIntent(CaptionsIntent.Open(target))
        vm.onIntent(CaptionsIntent.Generate)
        assertEquals(CaptionPhase.TRANSCRIBING, vm.state.value.phase)
        assertEquals(40, vm.state.value.progress)

        vm.onIntent(CaptionsIntent.Cancel)

        assertTrue(transcriber.cancelled)
        assertEquals(CaptionPhase.IDLE, vm.state.value.phase)
        assertTrue(effects.isEmpty())
        assertTrue(vm.state.value.isOpen)
    }

    @Test
    fun `closing while busy cancels the work`() {
        val gate = CompletableDeferred<Unit>()
        val transcriber = FakeTranscriber(result = speech, gate = gate)
        val vm = viewModel(FakeModels(installed = setOf("base")), transcriber)
        vm.onIntent(CaptionsIntent.Open(target))
        vm.onIntent(CaptionsIntent.Generate)

        vm.onIntent(CaptionsIntent.Close)

        assertTrue(transcriber.cancelled)
        assertFalse(vm.state.value.isOpen)
        assertFalse(vm.state.value.busy)
    }

    @Test
    fun `choices cannot change while working, and a second generate is ignored`() {
        val gate = CompletableDeferred<Unit>()
        val transcriber = FakeTranscriber(result = speech, gate = gate)
        val vm = viewModel(FakeModels(installed = setOf("base")), transcriber)
        vm.onIntent(CaptionsIntent.Open(target))
        vm.onIntent(CaptionsIntent.Generate)

        vm.onIntent(CaptionsIntent.SelectLanguage("fr"))
        vm.onIntent(CaptionsIntent.SelectStyle("impact"))
        vm.onIntent(CaptionsIntent.SelectModel("tiny"))
        vm.onIntent(CaptionsIntent.Generate)
        vm.onIntent(CaptionsIntent.DeleteModel("base"))

        assertEquals(CaptionLanguage.AUTO, vm.state.value.languageCode)
        assertEquals(CaptionStyle.CLASSIC.id, vm.state.value.styleId)
        assertEquals(CaptionModels.DEFAULT.id, vm.state.value.modelId)
        assertEquals(1, transcriber.requests.size)
    }

    @Test
    fun `removing a model updates the list`() {
        val models = FakeModels(installed = setOf("base"))
        val vm = viewModel(models, FakeTranscriber())
        vm.onIntent(CaptionsIntent.Open(target))

        vm.onIntent(CaptionsIntent.DeleteModel("base"))

        assertEquals(listOf("base"), models.deleted)
        assertFalse(vm.state.value.selectedModel!!.installed)
    }

    // region target from the editor state

    private val asset = MediaAssetDto("a1", "content://media/1", 1800, 30, 1, "Rec709-SDR")

    private fun editor(selected: String?, assets: List<MediaAssetDto> = listOf(asset), clips: List<Clip> = listOf(clip)) = EditorState(
        isLoading = false,
        fps = fps,
        canvasHeight = 1080,
        timeline = Timeline(listOf(Track("v1", TrackType.VIDEO, clips))),
        assets = assets,
        selectedClipId = selected,
    )

    @Test
    fun `the selected clip with audio is a caption target`() {
        val result = editor("c1").captionTarget()

        assertNotNull(result)
        assertEquals("content://media/1", result!!.assetUri)
        assertEquals(1080, result.canvasHeight)
        assertEquals(clip, result.clip)
    }

    @Test
    fun `nothing selected, a title, a silent clip or a missing asset are not targets`() {
        assertNull(editor(null).captionTarget())
        assertNull(editor("c1", assets = listOf(asset.copy(hasAudio = false))).captionTarget())
        assertNull(editor("c1", assets = emptyList()).captionTarget())
        val title = Clip("t1", null, FrameIndex(0), FrameIndex(0), FrameIndex(30), title = TitleContent("hi"))
        assertNull(editor("t1", clips = listOf(title)).captionTarget())
        assertNull(editor("missing").captionTarget())
    }

    // endregion
}
