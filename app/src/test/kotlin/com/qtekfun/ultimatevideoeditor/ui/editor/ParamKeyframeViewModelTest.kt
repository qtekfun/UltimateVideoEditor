package com.qtekfun.ultimatevideoeditor.ui.editor

import com.qtekfun.ultimatevideoeditor.data.MediaImportException
import com.qtekfun.ultimatevideoeditor.data.MediaImporter
import com.qtekfun.ultimatevideoeditor.data.ProbedMedia
import com.qtekfun.ultimatevideoeditor.data.ProjectError
import com.qtekfun.ultimatevideoeditor.data.ProjectStore
import com.qtekfun.ultimatevideoeditor.data.model.ClipAudioDto
import com.qtekfun.ultimatevideoeditor.data.model.ClipDto
import com.qtekfun.ultimatevideoeditor.data.model.EffectDto
import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto
import com.qtekfun.ultimatevideoeditor.data.model.ParamKeyDto
import com.qtekfun.ultimatevideoeditor.data.model.ParamTrackDto
import com.qtekfun.ultimatevideoeditor.data.model.ProjectDto
import com.qtekfun.ultimatevideoeditor.data.model.ProjectSettingsDto
import com.qtekfun.ultimatevideoeditor.data.model.TrackDto
import com.qtekfun.ultimatevideoeditor.domain.BezierHandle
import com.qtekfun.ultimatevideoeditor.domain.Clip
import com.qtekfun.ultimatevideoeditor.domain.Interpolation
import com.qtekfun.ultimatevideoeditor.domain.ParamIds
import com.qtekfun.ultimatevideoeditor.domain.paramKeys
import com.qtekfun.ultimatevideoeditor.engine.timeline.HitKind
import com.qtekfun.ultimatevideoeditor.engine.timeline.TimelineHit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
class ParamKeyframeViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeStore(var project: ProjectDto?) : ProjectStore {
        override suspend fun load(id: String): ProjectDto = project ?: throw ProjectError.NotFound(id)

        override suspend fun save(project: ProjectDto) {
            this.project = project
        }
    }

    private class NoImporter : MediaImporter {
        override suspend fun import(uri: String): ProbedMedia = throw MediaImportException("not used")
    }

    private val asset = MediaAssetDto("a1", "content://m/a1", durationFrames = 300, nativeFpsNum = 30, nativeFpsDen = 1, colorSpace = "Rec709-SDR")
    private val contrast = ParamIds.fx("e1", 0)

    private fun project(c1: ClipDto) = ProjectDto(
        id = "p1",
        name = "Test",
        settings = ProjectSettingsDto(1920, 1080, 30, 1, "Rec709-SDR"),
        mediaLibrary = listOf(asset),
        tracks = listOf(
            TrackDto("v1", "video", 0, listOf(c1, ClipDto("c2", "a1", 100, 0, 100))),
            TrackDto("a1", "audio", 1),
        ),
    )

    private fun plainClip(params: List<ParamTrackDto> = emptyList(), audio: ClipAudioDto? = null, gainDb: Double = 0.0) = ClipDto(
        "c1", "a1", 0, 0, 100,
        gainDb = gainDb,
        effects = listOf(EffectDto("e1", "contrast", listOf(1.0))),
        audio = audio,
        params = params,
    )

    private val animatedContrast = listOf(ParamTrackDto("fx.e1.0", listOf(ParamKeyDto(0, 0.5), ParamKeyDto(60, 1.5))))

    private class Harness(val vm: EditorViewModel, val effects: MutableList<EditorEffect>) {
        val state get() = vm.state.value
        val c1: Clip get() = checkNotNull(state.timeline.track("v1")!!.clip("c1"))

        fun select(clipId: String) {
            val ordered = state.visibleTimeline.tracks.flatMap { it.clips }.map { it.id }
            val key = vm.snapshotOf(state).clips[ordered.indexOf(clipId)].clipKey
            vm.onIntent(EditorIntent.TapTimeline(TimelineHit(HitKind.CLIP, 0, key, 0)))
        }

        fun at(frame: Long) = vm.onIntent(EditorIntent.SetPlayhead(frame))

        fun messages(): List<String> = effects.filterIsInstance<EditorEffect.ShowMessage>().map { it.text }
    }

    private fun TestScope.harness(project: ProjectDto): Harness {
        var counter = 0
        val vm = EditorViewModel("p1", FakeStore(project), NoImporter(), idGenerator = { "n${counter++}" })
        val effects = mutableListOf<EditorEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { effects += it } }
        advanceUntilIdle()
        return Harness(vm, effects)
    }

    // region the diamond

    @Test
    fun `the diamond animates an effect value with a key holding the shown value`() = runTest(dispatcher) {
        val h = harness(project(plainClip()))
        h.select("c1")
        h.at(30)

        h.vm.onIntent(EditorIntent.ToggleParamKey(contrast))

        assertEquals(listOf(30L), h.c1.paramKeys(contrast).map { it.frame })
        assertEquals(1.0, h.c1.paramKeys(contrast).single().value, 0.0)
        assertTrue(h.state.keyUi(contrast)!!.keyHere)
        assertTrue(h.state.canUndo)
    }

    @Test
    fun `the diamond on a key removes it and the value keeps what it was`() = runTest(dispatcher) {
        val h = harness(project(plainClip(animatedContrast)))
        h.select("c1")
        h.at(60)
        assertTrue(h.state.keyUi(contrast)!!.keyHere)

        h.vm.onIntent(EditorIntent.ToggleParamKey(contrast))
        assertEquals(listOf(0L), h.c1.paramKeys(contrast).map { it.frame })
        h.at(0)
        h.vm.onIntent(EditorIntent.ToggleParamKey(contrast))

        assertTrue(h.c1.params.isEmpty())
        assertEquals(0.5, h.c1.fx.effect("e1")!!.values[0], 1e-12) // the last key's value becomes the fixed one
    }

    @Test
    fun `a key needs the playhead inside the clip`() = runTest(dispatcher) {
        val h = harness(project(plainClip()))
        h.select("c1")
        h.at(150)

        h.vm.onIntent(EditorIntent.ToggleParamKey(contrast))

        assertTrue(h.c1.params.isEmpty())
        assertTrue(h.messages().any { it.contains("playhead inside the clip") })
    }

    @Test
    fun `a value that cannot be animated says so`() = runTest(dispatcher) {
        val h = harness(project(plainClip()))
        h.select("c1")
        h.at(10)

        h.vm.onIntent(EditorIntent.ToggleParamKey(ParamIds.fx("nope", 0)))

        assertTrue(h.messages().any { it.contains("cannot be animated") })
        assertNull(h.state.keyUi(ParamIds.fx("nope", 0)))
    }

    // endregion

    // region editing animated values

    @Test
    fun `the controls show keyframed values as they are at the playhead`() = runTest(dispatcher) {
        val h = harness(project(plainClip(animatedContrast, ClipAudioDto(pan = 0.0))))
        h.select("c1")
        h.at(30)

        assertEquals(1.0, h.state.displayedClip!!.fx.effect("e1")!!.values[0], 1e-9)
        h.at(60)
        assertEquals(1.5, h.state.displayedClip!!.fx.effect("e1")!!.values[0], 1e-9)
        h.at(150)
        assertEquals(1.0, h.state.displayedClip!!.fx.effect("e1")!!.values[0], 1e-9) // outside: the fixed value
    }

    @Test
    fun `moving the slider of an animated effect value writes a key at the playhead`() = runTest(dispatcher) {
        val h = harness(project(plainClip(animatedContrast)))
        h.select("c1")
        h.at(30)

        h.vm.onIntent(EditorIntent.UpdateEffect("e1", listOf(1.2)))
        h.vm.onIntent(EditorIntent.EndFxEdit(commit = true))

        assertEquals(listOf(0L, 30L, 60L), h.c1.paramKeys(contrast).map { it.frame })
        assertEquals(1.2, h.c1.paramKeys(contrast)[1].value, 1e-12)
        assertEquals(1.0, h.c1.fx.effect("e1")!!.values[0], 0.0) // the fixed value is untouched
        h.vm.onIntent(EditorIntent.Undo)
        assertEquals(listOf(0L, 60L), h.c1.paramKeys(contrast).map { it.frame })
    }

    @Test
    fun `an unanimated value is still edited in place`() = runTest(dispatcher) {
        val h = harness(project(plainClip()))
        h.select("c1")
        h.at(30)

        h.vm.onIntent(EditorIntent.UpdateEffect("e1", listOf(1.7)))
        h.vm.onIntent(EditorIntent.EndFxEdit(commit = true))

        assertTrue(h.c1.params.isEmpty())
        assertEquals(1.7, h.c1.fx.effect("e1")!!.values[0], 1e-12)
    }

    @Test
    fun `editing pan on a keyframed pan keys it while other audio fields stay fixed`() = runTest(dispatcher) {
        val pan = listOf(ParamTrackDto("audio.pan", listOf(ParamKeyDto(0, -1.0), ParamKeyDto(60, 1.0))))
        val h = harness(project(plainClip(pan, ClipAudioDto(pan = 0.25, fadeInFrames = 5))))
        h.select("c1")
        h.at(30)
        val shown = h.state.displayedClip!!.audio
        assertEquals(0.0, shown.pan, 1e-9)

        h.vm.onIntent(EditorIntent.UpdateClipAudio(shown.copy(pan = 0.4, fadeInFrames = 8)))
        h.vm.onIntent(EditorIntent.EndAudioEdit(commit = true))

        assertEquals(0.4, h.c1.paramKeys(ParamIds.PAN).first { it.frame == 30L }.value, 1e-12)
        assertEquals(0.25, h.c1.audio.pan, 0.0)  // the fixed pan did not take the shown value
        assertEquals(8L, h.c1.audio.fadeInFrames) // everything else is an ordinary edit
    }

    @Test
    fun `the volume slider keys a keyframed volume`() = runTest(dispatcher) {
        val volume = listOf(ParamTrackDto("audio.gainDb", listOf(ParamKeyDto(0, -6.0), ParamKeyDto(60, 0.0))))
        val h = harness(project(plainClip(volume, gainDb = -3.0)))
        h.select("c1")
        h.at(30)

        h.vm.onIntent(EditorIntent.UpdateGain(-12.0))
        h.vm.onIntent(EditorIntent.EndAppearanceEdit(commit = true))

        assertEquals(-12.0, h.c1.paramKeys(ParamIds.GAIN_DB).first { it.frame == 30L }.value, 1e-12)
        assertEquals(-3.0, h.c1.gainDb, 0.0)
    }

    // endregion

    // region jumping, copying and the lane

    @Test
    fun `previous and next jump between the keys of one value`() = runTest(dispatcher) {
        val keys = listOf(ParamTrackDto("fx.e1.0", listOf(ParamKeyDto(10, 0.5), ParamKeyDto(40, 1.0), ParamKeyDto(70, 1.5))))
        val h = harness(project(plainClip(keys)))
        h.select("c1")
        h.at(20)

        h.vm.onIntent(EditorIntent.JumpToParamKey(contrast, forward = true))
        assertEquals(40L, h.state.playhead.value)
        h.vm.onIntent(EditorIntent.JumpToParamKey(contrast, forward = false))
        h.vm.onIntent(EditorIntent.JumpToParamKey(contrast, forward = false))
        assertEquals(10L, h.state.playhead.value)
        h.vm.onIntent(EditorIntent.JumpToParamKey(contrast, forward = false))
        assertTrue(h.messages().any { it.contains("No earlier keyframe") })
    }

    @Test
    fun `copied keys paste at the playhead in one undo step`() = runTest(dispatcher) {
        val h = harness(project(plainClip(animatedContrast)))
        h.select("c1")
        h.vm.onIntent(EditorIntent.CopyParamKeys(contrast))
        assertEquals(listOf(0L, 60L), h.state.paramClipboard!!.keys.map { it.frame })
        h.at(20)

        h.vm.onIntent(EditorIntent.PasteParamKeys(ParamIds.fx("e1", 0)))

        assertEquals(listOf(0L, 20L, 60L, 80L), h.c1.paramKeys(contrast).map { it.frame })
        h.vm.onIntent(EditorIntent.Undo)
        assertEquals(listOf(0L, 60L), h.c1.paramKeys(contrast).map { it.frame })
    }

    @Test
    fun `pasting needs something copied and room in the clip`() = runTest(dispatcher) {
        val h = harness(project(plainClip(animatedContrast)))
        h.select("c1")
        h.at(10)
        h.vm.onIntent(EditorIntent.PasteParamKeys(contrast))
        assertTrue(h.messages().any { it.contains("Copy keyframes first") })
        h.vm.onIntent(EditorIntent.CopyParamKeys(contrast))
        h.at(99)
        h.vm.onIntent(EditorIntent.PasteParamKeys(contrast))
        // Only the first key fits at frame 99; the clip ends there.
        assertEquals(99L, h.c1.paramKeys(contrast).last().frame)
    }

    @Test
    fun `dragging a key in the lane is live and one undo step`() = runTest(dispatcher) {
        val h = harness(project(plainClip(animatedContrast)))
        h.select("c1")

        h.vm.onIntent(EditorIntent.UpdateParamKey(contrast, 60, 70, 1.9))
        h.vm.onIntent(EditorIntent.UpdateParamKey(contrast, 60, 75, 1.8))
        // Provisional: the committed timeline still has the old key, the preview shows the new one.
        assertEquals(listOf(0L, 60L), h.c1.paramKeys(contrast).map { it.frame })
        assertEquals(listOf(0L, 75L), h.state.selectedClip!!.paramKeys(contrast).map { it.frame })
        h.vm.onIntent(EditorIntent.EndParamKeyEdit(commit = true))

        assertEquals(listOf(0L, 75L), h.c1.paramKeys(contrast).map { it.frame })
        assertEquals(1.8, h.c1.paramKeys(contrast).last().value, 1e-12)
        h.vm.onIntent(EditorIntent.Undo)
        assertEquals(listOf(0L, 60L), h.c1.paramKeys(contrast).map { it.frame })
        assertEquals(1.5, h.c1.paramKeys(contrast).last().value, 1e-12)
    }

    @Test
    fun `a cancelled drag changes nothing`() = runTest(dispatcher) {
        val h = harness(project(plainClip(animatedContrast)))
        h.select("c1")
        h.vm.onIntent(EditorIntent.UpdateParamKey(contrast, 60, 10, 0.1))
        h.vm.onIntent(EditorIntent.EndParamKeyEdit(commit = false))

        assertEquals(listOf(0L, 60L), h.c1.paramKeys(contrast).map { it.frame })
        assertFalse(h.state.canUndo)
        assertNull(h.state.dragPreview)
    }

    @Test
    fun `the curve of a key can be changed to a Bezier with handles`() = runTest(dispatcher) {
        val h = harness(project(plainClip(animatedContrast)))
        h.select("c1")

        h.vm.onIntent(EditorIntent.SetParamKeyShape(contrast, 0, Interpolation.BEZIER, BezierHandle(0.1, 0.8), BezierHandle(0.7, 0.1)))

        val key = h.c1.paramKeys(contrast).first()
        assertEquals(Interpolation.BEZIER, key.interpolation)
        assertEquals(BezierHandle(0.1, 0.8), key.out)
        assertEquals(BezierHandle(0.7, 0.1), key.inn)
    }

    @Test
    fun `clearing a track returns the value to its fixed one`() = runTest(dispatcher) {
        val h = harness(project(plainClip(animatedContrast)))
        h.select("c1")

        h.vm.onIntent(EditorIntent.ClearParamTrack(contrast))

        assertTrue(h.c1.params.isEmpty())
        assertEquals(1.0, h.c1.fx.effect("e1")!!.values[0], 0.0)
    }

    // endregion
}
