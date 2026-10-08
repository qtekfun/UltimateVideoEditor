package com.qtekfun.ultimatevideoeditor.ui.editor

import com.qtekfun.ultimatevideoeditor.data.MediaImportException
import com.qtekfun.ultimatevideoeditor.data.MediaImporter
import com.qtekfun.ultimatevideoeditor.data.ProbedMedia
import com.qtekfun.ultimatevideoeditor.data.ProjectError
import com.qtekfun.ultimatevideoeditor.data.ProjectStore
import com.qtekfun.ultimatevideoeditor.data.model.ClipDto
import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto
import com.qtekfun.ultimatevideoeditor.data.model.ProjectDto
import com.qtekfun.ultimatevideoeditor.data.model.ProjectSettingsDto
import com.qtekfun.ultimatevideoeditor.data.model.TrackDto
import com.qtekfun.ultimatevideoeditor.data.model.ParamKeyDto
import com.qtekfun.ultimatevideoeditor.data.model.ParamTrackDto
import com.qtekfun.ultimatevideoeditor.domain.Clip
import com.qtekfun.ultimatevideoeditor.domain.FadeShape
import com.qtekfun.ultimatevideoeditor.data.model.ClipAudioDto
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
class AudioShapeViewModelTest {

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

    private val asset = MediaAssetDto("a1", "content://m/a1", durationFrames = 400, nativeFpsNum = 30, nativeFpsDen = 1, colorSpace = "Rec709-SDR")

    private fun project(music: ClipDto, video: ClipDto = ClipDto("v", "a1", 0, 0, 100)) = ProjectDto(
        id = "p1",
        name = "Test",
        settings = ProjectSettingsDto(1920, 1080, 30, 1, "Rec709-SDR"),
        mediaLibrary = listOf(asset),
        tracks = listOf(
            TrackDto("v1", "video", 0, listOf(video)),
            TrackDto("a1", "audio", 1, listOf(music)),
        ),
    )

    private fun music(params: List<ParamTrackDto> = emptyList(), audio: ClipAudioDto? = null, gainDb: Double = 0.0) =
        ClipDto("m", "a1", 0, 0, 200, gainDb = gainDb, audio = audio, params = params)

    private val curve = listOf(ParamTrackDto(ParamIds.GAIN_DB, listOf(ParamKeyDto(0, 0.0), ParamKeyDto(80, -10.0), ParamKeyDto(199, 0.0))))

    private class Harness(val vm: EditorViewModel) {
        val state get() = vm.state.value
        val m: Clip get() = checkNotNull(state.timeline.track("a1")!!.clip("m"))
        val key: Long get() = vm.clipKey("m")

        fun select(clipId: String) {
            vm.onIntent(EditorIntent.TapTimeline(TimelineHit(HitKind.CLIP, 0, vm.clipKey(clipId), 0)))
        }

        fun hit(kind: HitKind, frame: Long, db: Double? = null, index: Int = -1) =
            TimelineHit(kind, 1, key, frame, index, db?.let { Math.round(it * 10) })
    }

    private fun TestScope.harness(project: ProjectDto): Harness {
        var counter = 0
        val vm = EditorViewModel("p1", FakeStore(project), NoImporter(), idGenerator = { "n${counter++}" })
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { } }
        advanceUntilIdle()
        return Harness(vm)
    }

    @Test
    fun `dragging the fade-in circle sets the fade live and one undo step takes it back`() = runTest(dispatcher) {
        val h = harness(project(music()))
        h.select("m")

        h.vm.onIntent(AudioShapeIntent.Start(h.hit(HitKind.FADE_IN_HANDLE, 6)))
        h.vm.onIntent(AudioShapeIntent.Move(h.hit(HitKind.NONE, 20)))
        h.vm.onIntent(AudioShapeIntent.Move(h.hit(HitKind.NONE, 45)))
        assertEquals(0L, h.m.audio.fadeInFrames) // still provisional
        assertEquals(45L, h.state.visibleTimeline.track("a1")!!.clip("m")!!.audio.fadeInFrames)
        h.vm.onIntent(AudioShapeIntent.End(commit = true))

        assertEquals(45L, h.m.audio.fadeInFrames)
        h.vm.onIntent(EditorIntent.Undo)
        assertEquals(0L, h.m.audio.fadeInFrames)
        assertFalse(h.state.canUndo)
    }

    @Test
    fun `the fade-out circle and a cancelled drag`() = runTest(dispatcher) {
        val h = harness(project(music(audio = ClipAudioDto(fadeInFrames = 30))))
        h.select("m")
        h.vm.onIntent(AudioShapeIntent.Start(h.hit(HitKind.FADE_OUT_HANDLE, 194)))
        h.vm.onIntent(AudioShapeIntent.Move(h.hit(HitKind.NONE, 150)))
        h.vm.onIntent(AudioShapeIntent.End(commit = false))
        assertEquals(0L, h.m.audio.fadeOutFrames)

        h.vm.onIntent(AudioShapeIntent.Start(h.hit(HitKind.FADE_OUT_HANDLE, 194)))
        h.vm.onIntent(AudioShapeIntent.Move(h.hit(HitKind.NONE, 150)))
        h.vm.onIntent(AudioShapeIntent.End(commit = true))
        assertEquals(50L, h.m.audio.fadeOutFrames)
        assertEquals(30L, h.m.audio.fadeInFrames) // untouched
    }

    @Test
    fun `a drag on a clip that is not selected does nothing`() = runTest(dispatcher) {
        val h = harness(project(music()))
        h.select("v")
        h.vm.onIntent(AudioShapeIntent.Start(h.hit(HitKind.FADE_IN_HANDLE, 6)))
        h.vm.onIntent(AudioShapeIntent.Move(h.hit(HitKind.NONE, 45)))
        h.vm.onIntent(AudioShapeIntent.End(commit = true))
        assertEquals(0L, h.m.audio.fadeInFrames)
        assertFalse(h.state.canUndo)
    }

    @Test
    fun `a double tap on the clip adds a point and pins the fixed volume around it`() = runTest(dispatcher) {
        val h = harness(project(music(gainDb = -2.0)))
        h.select("m")

        h.vm.onIntent(AudioShapeIntent.DoubleTap(h.hit(HitKind.CLIP, 90, db = -18.0)))

        val keys = h.m.paramKeys(ParamIds.GAIN_DB)
        assertEquals(listOf(0L, 90L, 199L), keys.map { it.frame })
        assertEquals(listOf(-2.0, -18.0, -2.0), keys.map { it.value })
        // A second double tap on the dot removes it again; the pins stay.
        h.vm.onIntent(AudioShapeIntent.DoubleTap(h.hit(HitKind.VOLUME_POINT, 90, index = 1)))
        assertEquals(listOf(0L, 199L), h.m.paramKeys(ParamIds.GAIN_DB).map { it.frame })
    }

    @Test
    fun `dragging a point moves it between its neighbours as one undo step`() = runTest(dispatcher) {
        val h = harness(project(music(curve)))
        h.select("m")

        h.vm.onIntent(AudioShapeIntent.Start(h.hit(HitKind.VOLUME_POINT, 80, -10.0, index = 1)))
        h.vm.onIntent(AudioShapeIntent.Move(h.hit(HitKind.NONE, 120, -20.0)))
        h.vm.onIntent(AudioShapeIntent.Move(h.hit(HitKind.NONE, 140, -24.0)))
        h.vm.onIntent(AudioShapeIntent.End(commit = true))

        assertEquals(listOf(0L, 140L, 199L), h.m.paramKeys(ParamIds.GAIN_DB).map { it.frame })
        assertEquals(-24.0, h.m.paramKeys(ParamIds.GAIN_DB)[1].value, 1e-9)
        h.vm.onIntent(EditorIntent.Undo)
        assertEquals(listOf(0L, 80L, 199L), h.m.paramKeys(ParamIds.GAIN_DB).map { it.frame })
        assertFalse(h.state.canUndo)
    }

    @Test
    fun `the sheet button adds a point holding the volume at the playhead`() = runTest(dispatcher) {
        val h = harness(project(music(curve)))
        h.select("m")
        h.vm.onIntent(EditorIntent.SetPlayhead(40))

        h.vm.onIntent(AudioShapeIntent.AddPointAtPlayhead)

        val keys = h.m.paramKeys(ParamIds.GAIN_DB)
        assertEquals(listOf(0L, 40L, 80L, 199L), keys.map { it.frame })
        assertEquals(-5.0, keys[1].value, 1e-9) // halfway down the first slope: the gain did not jump
    }

    @Test
    fun `the canvas gets the editable fades and curve of the selected audio clip only`() = runTest(dispatcher) {
        val h = harness(project(music(curve, ClipAudioDto(fadeInFrames = 10, fadeShape = "linear"))))
        h.select("m")

        val shaping = h.vm.snapshotOf(h.state).shaping
        val entry = shaping.single()
        assertEquals(h.key, entry.clipKey)
        assertTrue(entry.editable)
        assertEquals(10L, entry.fadeInFrames)
        assertEquals(FadeShape.LINEAR.code, entry.fadeShape)
        assertEquals(listOf(0L, 80L, 199L), entry.points.map { it.frame })

        h.select("v")
        val unselected = h.vm.snapshotOf(h.state).shaping.single()
        assertFalse(unselected.editable) // still drawn, no longer grabbable
    }
}
