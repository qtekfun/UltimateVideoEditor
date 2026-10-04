package com.ultimatevideo.uveditor.ui.editor

import com.ultimatevideo.uveditor.data.MediaImportException
import com.ultimatevideo.uveditor.data.MediaImporter
import com.ultimatevideo.uveditor.data.ProbedMedia
import com.ultimatevideo.uveditor.data.ProjectError
import com.ultimatevideo.uveditor.data.ProjectStore
import com.ultimatevideo.uveditor.data.model.ClipDto
import com.ultimatevideo.uveditor.data.model.MediaAssetDto
import com.ultimatevideo.uveditor.data.model.ProjectDto
import com.ultimatevideo.uveditor.data.model.ProjectSettingsDto
import com.ultimatevideo.uveditor.data.model.TitleDto
import com.ultimatevideo.uveditor.data.model.TrackDto
import com.ultimatevideo.uveditor.data.model.TransitionDto
import com.ultimatevideo.uveditor.domain.FrameIndex
import com.ultimatevideo.uveditor.domain.TitleAlignment
import com.ultimatevideo.uveditor.domain.TitleContent
import com.ultimatevideo.uveditor.domain.TrackType
import com.ultimatevideo.uveditor.engine.timeline.HitKind
import com.ultimatevideo.uveditor.engine.timeline.TimelineHit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
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
class TitleTransitionViewModelTest {

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
        val saved = mutableListOf<ProjectDto>()

        override suspend fun load(id: String): ProjectDto = project ?: throw ProjectError.NotFound(id)

        override suspend fun save(project: ProjectDto) {
            saved += project
            this.project = project
        }
    }

    private class NoProbe : MediaImporter {
        override suspend fun import(uri: String): ProbedMedia = throw MediaImportException("not probed")
    }

    private val settings = ProjectSettingsDto(1920, 1080, 30, 1, "Rec709-SDR")
    private val asset = MediaAssetDto("a1", "content://m/a1", durationFrames = 300, nativeFpsNum = 30, nativeFpsDen = 1, colorSpace = "Rec709-SDR")

    /** c1 reads source 0..100, c2 reads 50..150: c2 has 50 frames of media before its in point. */
    private fun project(transitions: List<TransitionDto> = emptyList(), withHandles: Boolean = true) = ProjectDto(
        id = "p1",
        name = "Test",
        settings = settings,
        mediaLibrary = listOf(asset),
        tracks = listOf(
            TrackDto(
                "v1", "video", 0,
                listOf(
                    ClipDto("c1", "a1", 0, 0, 100),
                    ClipDto("c2", "a1", 100, if (withHandles) 50 else 0, if (withHandles) 150 else 100),
                ),
            ),
            TrackDto("a1", "audio", 1),
        ),
        transitions = transitions,
    )

    private class Harness(val vm: EditorViewModel, val store: FakeStore, val effects: MutableList<EditorEffect>) {
        val state get() = vm.state.value

        fun select(clipId: String) {
            val ordered = state.visibleTimeline.tracks.flatMap { it.clips }.map { it.id }
            val key = vm.snapshotOf(state).clips[ordered.indexOf(clipId)].clipKey
            val track = state.visibleTimeline.tracks.indexOfFirst { t -> t.clips.any { it.id == clipId } }
            vm.onIntent(EditorIntent.TapTimeline(TimelineHit(HitKind.CLIP, track, key, 0)))
        }

        fun messages() = effects.filterIsInstance<EditorEffect.ShowMessage>().map { it.text }
    }

    private fun TestScope.harness(project: ProjectDto = project()): Harness {
        var counter = 0
        val store = FakeStore(project)
        val vm = EditorViewModel("p1", store, NoProbe(), idGenerator = { "n${counter++}" })
        val effects = mutableListOf<EditorEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { effects += it } }
        advanceUntilIdle()
        return Harness(vm, store, effects)
    }

    // --- titles --------------------------------------------------------------------------------

    @Test
    fun `adding a title makes a title track on top and selects the new clip`() = runTest(dispatcher) {
        val h = harness()
        h.vm.onIntent(EditorIntent.SetPlayhead(40))

        h.vm.onIntent(EditorIntent.AddTitle)

        val timeline = h.state.timeline
        assertEquals(TrackType.TITLE, timeline.tracks.first().type)
        val clip = timeline.tracks.first().clips.single()
        assertEquals(FrameIndex(40), clip.timelineStart)
        assertEquals(90, clip.durationFrames) // three seconds at 30 fps
        assertEquals("Title", clip.title?.text)
        assertEquals(clip.id, h.state.selectedClipId)
        assertTrue(h.state.inspectorOpen)
        assertTrue(timeline.invariantViolations().isEmpty())
    }

    @Test
    fun `a second title reuses the title track`() = runTest(dispatcher) {
        val h = harness()
        h.vm.onIntent(EditorIntent.AddTitle)
        h.vm.onIntent(EditorIntent.SetPlayhead(200))

        h.vm.onIntent(EditorIntent.AddTitle)

        assertEquals(1, h.state.timeline.tracks.count { it.type == TrackType.TITLE })
        assertEquals(2, h.state.timeline.tracks.first().clips.size)
    }

    @Test
    fun `title edits show live and become one undo step`() = runTest(dispatcher) {
        val h = harness()
        h.vm.onIntent(EditorIntent.AddTitle)
        val original = h.state.selectedTitle!!

        h.vm.onIntent(EditorIntent.UpdateTitle(original.copy(text = "H")))
        h.vm.onIntent(EditorIntent.UpdateTitle(original.copy(text = "Hello")))

        assertEquals("Hello", h.state.selectedTitle?.text) // shown live
        assertEquals("Title", h.state.timeline.tracks.first().clips.single().title?.text) // not committed yet
        h.vm.onIntent(EditorIntent.EndTitleEdit(commit = true))
        assertEquals("Hello", h.state.timeline.tracks.first().clips.single().title?.text)

        h.vm.onIntent(EditorIntent.Undo)
        assertEquals("Title", h.state.timeline.tracks.first().clips.single().title?.text)
        assertNull(h.state.dragPreview)
    }

    @Test
    fun `style changes are kept`() = runTest(dispatcher) {
        val h = harness()
        h.vm.onIntent(EditorIntent.AddTitle)
        val styled = TitleContent("Big", sizeFraction = 0.2, colorArgb = 0xFFFF0000.toInt(), alignment = TitleAlignment.LEFT, bold = true)

        h.vm.onIntent(EditorIntent.UpdateTitle(styled))
        h.vm.onIntent(EditorIntent.EndTitleEdit(commit = true))

        assertEquals(styled, h.state.timeline.tracks.first().clips.single().title)
    }

    @Test
    fun `any other action makes a pending title edit final`() = runTest(dispatcher) {
        val h = harness()
        h.vm.onIntent(EditorIntent.AddTitle)
        val original = h.state.selectedTitle!!

        h.vm.onIntent(EditorIntent.UpdateTitle(original.copy(text = "Typed")))
        h.vm.onIntent(EditorIntent.SetPlayhead(10)) // e.g. the user taps the ruler without leaving the field

        assertEquals("Typed", h.state.timeline.tracks.first().clips.single().title?.text)
        assertNull(h.state.dragPreview)
    }

    @Test
    fun `an empty title text is not applied and cancelling restores the original`() = runTest(dispatcher) {
        val h = harness()
        h.vm.onIntent(EditorIntent.AddTitle)
        val original = h.state.selectedTitle!!

        h.vm.onIntent(EditorIntent.UpdateTitle(original.copy(text = "  ")))
        assertEquals("Title", h.state.selectedTitle?.text)

        h.vm.onIntent(EditorIntent.UpdateTitle(original.copy(text = "Changed")))
        h.vm.onIntent(EditorIntent.EndTitleEdit(commit = false))
        assertEquals("Title", h.state.selectedTitle?.text)
    }

    @Test
    fun `title edits do nothing when a media clip is selected`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")

        h.vm.onIntent(EditorIntent.UpdateTitle(TitleContent("x")))

        assertNull(h.state.dragPreview)
        assertNull(h.state.selectedTitle)
    }

    @Test
    fun `a title can be adjusted and moved like a video clip`() = runTest(dispatcher) {
        val h = harness()
        h.vm.onIntent(EditorIntent.AddTitle)

        assertNotNull(h.state.selectedVisualClip)
        h.vm.onIntent(EditorIntent.SetPlayhead(10))
        assertTrue(h.state.selectedClipVisible)
        h.vm.onIntent(EditorIntent.UpdateTransform(h.state.selectedClip!!.transform.copy(positionY = 300.0)))
        h.vm.onIntent(EditorIntent.EndAppearanceEdit(commit = true))

        assertEquals(300.0, h.state.timeline.tracks.first().clips.single().transform.positionY, 0.0)
    }

    @Test
    fun `the last title track can be removed once empty`() = runTest(dispatcher) {
        val h = harness()
        h.vm.onIntent(EditorIntent.AddTitle)
        h.vm.onIntent(EditorIntent.RippleDeleteSelected)
        val titleTrack = h.state.timeline.tracks.first { it.type == TrackType.TITLE }
        h.vm.onIntent(EditorIntent.TapTimeline(TimelineHit(HitKind.EMPTY_TRACK, 0, -1, 0)))

        h.vm.onIntent(EditorIntent.RemoveSelectedTrack)

        assertTrue(titleTrack.clips.isEmpty())
        assertTrue(h.state.timeline.tracks.none { it.type == TrackType.TITLE })
    }

    // --- transitions ---------------------------------------------------------------------------

    @Test
    fun `adding a transition uses one second by default`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")

        h.vm.onIntent(EditorIntent.AddTransition)

        val transition = h.state.timeline.transitions.single()
        assertEquals("c1", transition.fromClipId)
        assertEquals("c2", transition.toClipId)
        assertEquals(30, transition.durationFrames)
        assertEquals(transition, h.state.selectedTransition)
        assertTrue(h.state.timeline.invariantViolations().isEmpty())
    }

    @Test
    fun `selecting the clip after the cut also enables and adds the transition`() = runTest(dispatcher) {
        val h = harness()
        h.select("c2")

        assertEquals("c1", h.state.transitionCut?.from?.id)
        assertEquals("c2", h.state.transitionCut?.to?.id)
        h.vm.onIntent(EditorIntent.AddTransition)

        val transition = h.state.timeline.transitions.single()
        assertEquals("c1", transition.fromClipId)
        assertEquals("c2", transition.toClipId)
    }

    @Test
    fun `the playhead on a cut enables the transition without a selection`() = runTest(dispatcher) {
        val h = harness()
        assertNull(h.state.transitionCut)

        h.vm.onIntent(EditorIntent.SetPlayhead(100))
        assertEquals("c1", h.state.transitionCut?.from?.id)
        h.vm.onIntent(EditorIntent.AddTransition)

        assertEquals("c2", h.state.timeline.transitions.single().toClipId)
    }

    @Test
    fun `the playhead only counts near a cut`() = runTest(dispatcher) {
        val h = harness()

        h.vm.onIntent(EditorIntent.SetPlayhead(100 + TRANSITION_NEAR_FRAMES))
        assertNotNull(h.state.transitionCut)
        h.vm.onIntent(EditorIntent.SetPlayhead(100 - TRANSITION_NEAR_FRAMES))
        assertNotNull(h.state.transitionCut)
        h.vm.onIntent(EditorIntent.SetPlayhead(100 + TRANSITION_NEAR_FRAMES + 1))
        assertNull(h.state.transitionCut)
        h.vm.onIntent(EditorIntent.SetPlayhead(0))
        assertNull(h.state.transitionCut)
    }

    @Test
    fun `a cut that already has a transition is not offered again`() = runTest(dispatcher) {
        val h = harness(project(listOf(TransitionDto("t1", "crossfade", "c1", "c2", 20))))
        h.select("c1")
        assertNull(h.state.transitionCut)
        h.select("c2")
        assertNull(h.state.transitionCut)
        h.vm.onIntent(EditorIntent.SetPlayhead(100))
        h.vm.onIntent(EditorIntent.AddTransition)

        assertEquals(1, h.state.timeline.transitions.size)
    }

    @Test
    fun `a transition needs two adjacent clips on the same lane`() = runTest(dispatcher) {
        val gap = project().let { p ->
            p.copy(tracks = listOf(p.tracks[0].copy(clips = listOf(ClipDto("c1", "a1", 0, 0, 100), ClipDto("c2", "a1", 120, 50, 150))), p.tracks[1]))
        }
        val h = harness(gap)
        h.select("c1")
        assertNull(h.state.transitionCut)
        h.vm.onIntent(EditorIntent.SetPlayhead(120))
        assertNull(h.state.transitionCut)

        h.vm.onIntent(EditorIntent.AddTransition)

        assertTrue(h.state.timeline.transitions.isEmpty())
        assertTrue(h.messages().any { "another clip right after" in it })
    }

    @Test
    fun `a transition is refused when there is no footage around the cut`() = runTest(dispatcher) {
        val h = harness(project(withHandles = false))
        h.select("c1")

        h.vm.onIntent(EditorIntent.AddTransition)

        assertTrue(h.state.timeline.transitions.isEmpty())
        assertTrue(h.messages().any { "not enough extra footage" in it })
    }

    @Test
    fun `a second transition on the same cut is refused`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")
        h.vm.onIntent(EditorIntent.AddTransition)

        h.vm.onIntent(EditorIntent.AddTransition)

        assertEquals(1, h.state.timeline.transitions.size)
        assertTrue(h.messages().any { "already have a transition" in it })
    }

    @Test
    fun `the length can be changed within the room there is and removed`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")
        h.vm.onIntent(EditorIntent.AddTransition)

        h.vm.onIntent(EditorIntent.SetTransitionDuration(60))
        assertEquals(60, h.state.timeline.transitions.single().durationFrames)

        val limit = h.vm.transitionLimit(h.state.timeline.transitions.single())
        assertTrue(limit >= 60)
        h.vm.onIntent(EditorIntent.SetTransitionDuration(limit + 1))
        assertEquals(60, h.state.timeline.transitions.single().durationFrames)
        assertTrue(h.messages().any { "not possible" in it })

        h.vm.onIntent(EditorIntent.RemoveTransition)
        assertTrue(h.state.timeline.transitions.isEmpty())
    }

    @Test
    fun `the limit comes from the media left around the cut`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")
        h.vm.onIntent(EditorIntent.AddTransition)

        // c2 has 50 frames before its in point: the incoming half cannot exceed that, so 100 or 101 in total.
        val limit = h.vm.transitionLimit(h.state.timeline.transitions.single())

        assertTrue("limit $limit", limit in 100L..101L)
    }

    @Test
    fun `undo and redo bring a transition back and forth`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")
        h.vm.onIntent(EditorIntent.AddTransition)

        h.vm.onIntent(EditorIntent.Undo)
        assertTrue(h.state.timeline.transitions.isEmpty())
        h.vm.onIntent(EditorIntent.Redo)
        assertEquals(1, h.state.timeline.transitions.size)
    }

    @Test
    fun `splitting the outgoing clip keeps the transition on the cut`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")
        h.vm.onIntent(EditorIntent.AddTransition)
        h.vm.onIntent(EditorIntent.SetPlayhead(50))

        h.vm.onIntent(EditorIntent.SplitAtPlayhead)

        val transition = h.state.timeline.transitions.single()
        assertEquals("c2", transition.toClipId)
        assertEquals("c1~n1", transition.fromClipId)
    }

    @Test
    fun `transitions saved in the project load and are written back`() = runTest(dispatcher) {
        val h = harness(project(transitions = listOf(TransitionDto("t1", "crossfade", "c1", "c2", 20))))

        assertEquals(20, h.state.timeline.transitions.single().durationFrames)

        h.select("c1")
        h.vm.onIntent(EditorIntent.SetTransitionDuration(24))
        advanceTimeBy(600)

        assertEquals(24, h.store.saved.last().transitions.single().durationFrames)
        assertEquals("t1", h.store.saved.last().transitions.single().id)
    }

    @Test
    fun `titles are saved with their text and style`() = runTest(dispatcher) {
        val h = harness()
        h.vm.onIntent(EditorIntent.AddTitle)
        val content = TitleContent("Saved", 0.1, 0xFF00FF00.toInt(), TitleAlignment.RIGHT, true)
        h.vm.onIntent(EditorIntent.UpdateTitle(content))
        h.vm.onIntent(EditorIntent.EndTitleEdit(commit = true))
        advanceTimeBy(600)

        val saved = h.store.saved.last().tracks.first { it.type == "title" }.clips.single().title
        assertEquals(TitleDto("Saved", 0.1, "#FF00FF00", "right", true), saved)
    }

    @Test
    fun `the timeline snapshot carries the transitions to the canvas`() = runTest(dispatcher) {
        val h = harness(project(transitions = listOf(TransitionDto("t1", "crossfade", "c1", "c2", 21))))

        val snapshot = h.vm.snapshotOf(h.state)

        val t = snapshot.transitions.single()
        assertEquals(0, t.trackIndex)
        assertEquals(100L, t.cutFrame)
        assertEquals(10L, t.preFrames)
        assertEquals(11L, t.postFrames)
        assertFalse(snapshot.clips.isEmpty())
    }
}
