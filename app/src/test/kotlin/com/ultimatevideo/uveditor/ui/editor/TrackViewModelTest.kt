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
import com.ultimatevideo.uveditor.domain.Clip
import com.ultimatevideo.uveditor.domain.FrameIndex
import com.ultimatevideo.uveditor.domain.FrameRate
import com.ultimatevideo.uveditor.domain.MotionTrack
import com.ultimatevideo.uveditor.domain.TrackFrame
import com.ultimatevideo.uveditor.domain.TrackPath
import com.ultimatevideo.uveditor.engine.timeline.HitKind
import com.ultimatevideo.uveditor.engine.timeline.TimelineHit
import com.ultimatevideo.uveditor.engine.track.MotionTracker
import com.ultimatevideo.uveditor.engine.track.TrackOutcome
import com.ultimatevideo.uveditor.engine.track.TrackStatus
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.abs

@OptIn(ExperimentalCoroutinesApi::class)
class TrackViewModelTest {
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

    private class FakeTracker : MotionTracker {
        val analysed = mutableSetOf<String>()
        val forgotten = mutableListOf<String>()
        var cancels = 0
        var analyses = 0
        var aspect: Double? = 16.0 / 9.0
        var outcome: TrackOutcome = TrackOutcome.Done
        /** When set, an analysis waits for it. */
        var gate: CompletableDeferred<TrackOutcome>? = null
        val seen = mutableListOf<MotionTrack>()

        override suspend fun frameAspect(asset: MediaAssetDto): Double? = aspect

        override fun statusOf(asset: MediaAssetDto, clip: Clip, track: MotionTrack, fps: FrameRate): TrackStatus =
            if (track.id in analysed) TrackStatus.Ready(lost = 0, frames = 300) else TrackStatus.NotAnalysed

        override suspend fun analyse(asset: MediaAssetDto, clip: Clip, track: MotionTrack, fps: FrameRate, onProgress: (Float) -> Unit): TrackOutcome {
            analyses++
            seen += track
            onProgress(0.5f)
            val result = gate?.await() ?: outcome
            if (result == TrackOutcome.Done) {
                onProgress(1f)
                analysed += track.id
            }
            return result
        }

        override fun cancel() {
            cancels++
        }

        override fun load(asset: MediaAssetDto, track: MotionTrack, fps: FrameRate): TrackPath? =
            if (track.id in analysed) TrackPath(16.0 / 9.0, (0L..299L).map { TrackFrame(it, 0.2 + it * 0.001, 0.5, 0.1, 0.1, 1.0, false) }) else null

        override fun forget(asset: MediaAssetDto, track: MotionTrack) {
            forgotten += track.id
            analysed -= track.id
        }
    }

    private val asset = MediaAssetDto("a1", "content://m/a1", durationFrames = 900, nativeFpsNum = 30, nativeFpsDen = 1, colorSpace = "Rec709-SDR")

    private fun project() = ProjectDto(
        id = "p1",
        name = "Test",
        settings = ProjectSettingsDto(1920, 1080, 30, 1, "Rec709-SDR"),
        mediaLibrary = listOf(asset),
        tracks = listOf(
            TrackDto("t1", "title", 0, listOf(ClipDto("t", null, 0, 0, 60, title = TitleDto(text = "Hi")))),
            TrackDto("v1", "video", 1, listOf(ClipDto("c1", "a1", 0, 0, 300), ClipDto("c2", "a1", 300, 0, 150))),
            TrackDto("a1", "audio", 2, listOf(ClipDto("m", "a1", 0, 0, 100))),
        ),
    )

    private class Harness(val vm: EditorViewModel, val tracker: FakeTracker, val effects: MutableList<EditorEffect>) {
        val state get() = vm.state.value

        fun select(clipId: String) {
            val ordered = state.visibleTimeline.tracks.flatMap { it.clips }.map { it.id }
            val key = vm.snapshotOf(state).clips[ordered.indexOf(clipId)].clipKey
            vm.onIntent(EditorIntent.TapTimeline(TimelineHit(HitKind.CLIP, 0, key, 0)))
            vm.onIntent(EditorIntent.RefreshTrack)  // the inspector's section asks for this when the selection changes
        }

        fun clip(id: String) = checkNotNull(state.timeline.trackOfClip(id)?.clip(id))

        fun messages(): List<String> = effects.filterIsInstance<EditorEffect.ShowMessage>().map { it.text }
    }

    private fun TestScope.harness(tracker: FakeTracker = FakeTracker()): Harness {
        var counter = 0
        val vm = EditorViewModel(
            "p1", FakeStore(project()), NoImporter(), idGenerator = { "n${counter++}" },
            stabDispatcher = dispatcher, motionTracker = tracker, trackDispatcher = dispatcher,
        )
        val effects = mutableListOf<EditorEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { effects += it } }
        advanceUntilIdle()
        return Harness(vm, tracker, effects)
    }

    /** Picks the middle of the picture of c1 at the playhead (the clip is untransformed, so that is the canvas centre). */
    private fun TestScope.pickCentre(h: Harness) {
        h.select("c1")
        h.vm.onIntent(EditorIntent.BeginTrackPick)
        advanceUntilIdle()
        h.vm.onIntent(EditorIntent.PickTrackTarget(0.0, 0.0, null, null))
        advanceUntilIdle()
    }

    @Test
    fun `only a video clip can be tracked`() = runTest(dispatcher) {
        val h = harness()
        h.select("m")
        h.vm.onIntent(EditorIntent.BeginTrackPick)
        advanceUntilIdle()
        assertFalse(h.state.track.picking)
        assertFalse(h.state.track.canTrack)
        assertTrue(h.messages().isNotEmpty())

        h.select("t")
        h.vm.onIntent(EditorIntent.BeginTrackPick)
        advanceUntilIdle()
        assertFalse(h.state.track.picking)
    }

    @Test
    fun `picking waits for the preview tap and needs the playhead on the clip`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")
        advanceUntilIdle()
        assertTrue(h.state.track.canTrack)
        h.vm.onIntent(EditorIntent.BeginTrackPick)
        assertTrue(h.state.track.picking)
        h.vm.onIntent(EditorIntent.CancelTrackPick)
        assertFalse(h.state.track.picking)

        h.vm.onIntent(EditorIntent.SetPlayhead(350))  // on c2, not on c1
        h.vm.onIntent(EditorIntent.BeginTrackPick)
        assertFalse(h.state.track.picking)
        assertTrue(h.messages().any { it.contains("playhead") })
    }

    @Test
    fun `a tap makes a target at the playhead's source frame, is one undo step and starts the analysis`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")
        h.vm.onIntent(EditorIntent.SetPlayhead(90))
        pickCentre(h)

        val motion = h.state.timeline.motionTracks.single()
        assertEquals("c1", motion.clipId)
        assertEquals("Track 1", motion.name)
        assertEquals(90L, motion.seed.sourceFrame)
        assertEquals(0.5, motion.seed.cx, 1e-9)
        assertEquals(0.5, motion.seed.cy, 1e-9)
        assertEquals(0.12, motion.seed.h, 1e-9)
        assertEquals(0.12 / (16.0 / 9.0), motion.seed.w, 1e-9)  // a square in pixels
        assertEquals(1, h.tracker.analyses)
        assertFalse(h.state.track.picking)
        assertNull(h.state.track.progress)
        assertEquals(motion.id, h.state.track.activeId)
        assertEquals(TrackStatus.Ready(0, 300), h.state.track.items.single().status)
        assertTrue(h.state.track.overlay.isNotEmpty())

        h.vm.onIntent(EditorIntent.Undo)
        advanceUntilIdle()
        assertTrue(h.state.timeline.motionTracks.isEmpty())
        assertTrue(h.state.track.items.isEmpty())
        assertFalse(h.state.canUndo)
    }

    @Test
    fun `a dragged box sets the size of the target and the box size chip changes a tap`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")
        h.vm.onIntent(EditorIntent.BeginTrackPick)
        h.vm.onIntent(EditorIntent.SetTrackBox(0.2))
        assertEquals(0.2, h.state.track.boxSide, 1e-9)
        h.vm.onIntent(EditorIntent.PickTrackTarget(0.0, 0.0, null, null))
        advanceUntilIdle()
        assertEquals(0.2, h.state.timeline.motionTracks.single().seed.h, 1e-9)

        // A 384 x 216 canvas-pixel box on a 1920x1080 picture is a fifth of each side.
        h.vm.onIntent(EditorIntent.BeginTrackPick)
        h.vm.onIntent(EditorIntent.PickTrackTarget(200.0, -100.0, 384.0, 216.0))
        advanceUntilIdle()
        val box = h.state.timeline.motionTracks.last()
        assertEquals(0.2, box.seed.w, 1e-9)
        assertEquals(0.2, box.seed.h, 1e-9)
        assertEquals(0.5 + 200.0 / 1920.0, box.seed.cx, 1e-9)
        assertEquals(0.5 - 100.0 / 1080.0, box.seed.cy, 1e-9)
        assertEquals("Track 2", box.name)
    }

    @Test
    fun `a pick outside the picture is refused`() = runTest(dispatcher) {
        val h = harness()
        h.select("c1")
        h.vm.onIntent(EditorIntent.BeginTrackPick)
        h.vm.onIntent(EditorIntent.PickTrackTarget(5000.0, 0.0, null, null))
        advanceUntilIdle()
        assertTrue(h.state.timeline.motionTracks.isEmpty())
        assertTrue(h.messages().any { it.contains("picture") })
        assertEquals(0, h.tracker.analyses)
    }

    @Test
    fun `a failed analysis tells the user and keeps the target for another try`() = runTest(dispatcher) {
        val tracker = FakeTracker().apply { outcome = TrackOutcome.Failed("The target could not be followed in this clip") }
        val h = harness(tracker)
        pickCentre(h)
        assertEquals(1, h.state.timeline.motionTracks.size)
        assertTrue(h.messages().contains("The target could not be followed in this clip"))
        assertNull(h.state.track.progress)
        assertEquals(TrackStatus.NotAnalysed, h.state.track.items.single().status)

        tracker.outcome = TrackOutcome.Done
        h.vm.onIntent(EditorIntent.ReanalyseTrack(h.state.timeline.motionTracks.single().id))
        advanceUntilIdle()
        assertEquals(2, tracker.analyses)
        assertTrue(h.state.track.items.single().status is TrackStatus.Ready)
    }

    @Test
    fun `progress is shown while analysing and cancel reaches the tracker`() = runTest(dispatcher) {
        val tracker = FakeTracker().apply { gate = CompletableDeferred() }
        val h = harness(tracker)
        pickCentre(h)
        assertEquals(0.5f, h.state.track.progress)
        assertNotNull(h.state.track.analysingId)
        h.vm.onIntent(EditorIntent.CancelTrack)
        assertEquals(1, tracker.cancels)
        tracker.gate?.complete(TrackOutcome.Cancelled)
        advanceUntilIdle()
        assertNull(h.state.track.progress)
        assertNull(h.state.track.analysingId)
        assertTrue(h.messages().any { it.contains("cancelled") })
    }

    @Test
    fun `another clip follows the track with position keyframes in one undo step`() = runTest(dispatcher) {
        val h = harness()
        pickCentre(h)
        val motion = h.state.timeline.motionTracks.single()
        val before = h.clip("t")
        assertTrue(before.keyframes.isEmpty())

        h.select("t")
        advanceUntilIdle()
        assertTrue(h.state.track.followable.single().ready)
        h.vm.onIntent(EditorIntent.FollowTrack(motion.id))
        advanceUntilIdle()

        val after = h.clip("t")
        assertTrue(after.keyframes.size >= 2)
        // The title sits on the target: cx = 0.2 + frame / 1000 of a 1920-wide frame, from the canvas centre.
        val first = after.keyframes.first().transform
        assertTrue("x was ${first.positionX}", abs(first.positionX - (0.2 - 0.5) * 1920.0) < 1.0)
        assertTrue(abs(first.positionY) < 1.0)
        assertTrue(h.messages().any { it.contains("follows Track 1") })

        h.vm.onIntent(EditorIntent.Undo)
        advanceUntilIdle()
        assertEquals(before, h.clip("t"))
        assertEquals(1, h.state.timeline.motionTracks.size)  // undoing the follow keeps the target
    }

    @Test
    fun `following needs an analysed path, another clip and a visual clip`() = runTest(dispatcher) {
        val tracker = FakeTracker().apply { outcome = TrackOutcome.Failed("nope") }
        val h = harness(tracker)
        pickCentre(h)
        val motion = h.state.timeline.motionTracks.single()

        h.select("t")
        advanceUntilIdle()
        assertFalse(h.state.track.followable.single().ready)
        h.vm.onIntent(EditorIntent.FollowTrack(motion.id))
        advanceUntilIdle()
        assertTrue(h.clip("t").keyframes.isEmpty())
        assertTrue(h.messages().any { it.contains("Analyse") })

        tracker.analysed += motion.id
        h.select("c1")
        h.vm.onIntent(EditorIntent.FollowTrack(motion.id))
        advanceUntilIdle()
        assertTrue(h.clip("c1").keyframes.isEmpty())
        assertTrue(h.messages().any { it.contains("own track") })

        h.select("m")
        advanceUntilIdle()
        assertTrue(h.state.track.followable.isEmpty())  // an audio clip has nothing to move
        h.vm.onIntent(EditorIntent.FollowTrack(motion.id))
        advanceUntilIdle()
        assertTrue(h.clip("m").keyframes.isEmpty())
    }

    @Test
    fun `removing a target forgets its analysis and undo brings the target back`() = runTest(dispatcher) {
        val h = harness()
        pickCentre(h)
        val motion = h.state.timeline.motionTracks.single()
        h.vm.onIntent(EditorIntent.RemoveMotionTrack(motion.id))
        advanceUntilIdle()
        assertTrue(h.state.timeline.motionTracks.isEmpty())
        assertEquals(listOf(motion.id), h.tracker.forgotten)
        assertNull(h.state.track.activeId)
        assertTrue(h.state.track.overlay.isEmpty())

        h.vm.onIntent(EditorIntent.Undo)
        advanceUntilIdle()
        assertEquals(listOf(motion.id), h.state.timeline.motionTracks.map { it.id })
    }

    @Test
    fun `the path can be shown and hidden on the preview`() = runTest(dispatcher) {
        val h = harness()
        pickCentre(h)
        val id = h.state.timeline.motionTracks.single().id
        h.vm.onIntent(EditorIntent.ShowTrack(null))
        advanceUntilIdle()
        assertNull(h.state.track.activeId)
        assertTrue(h.state.track.overlay.isEmpty())
        h.vm.onIntent(EditorIntent.ShowTrack(id))
        advanceUntilIdle()
        assertEquals(id, h.state.track.activeId)
        assertEquals(300, h.state.track.overlay.size)  // one canvas point per frame of the tracked clip
    }

    @Test
    fun `deleting the tracked clip removes its target from the project`() = runTest(dispatcher) {
        val h = harness()
        pickCentre(h)
        h.select("c1")
        h.vm.onIntent(EditorIntent.RippleDeleteSelected)
        advanceUntilIdle()
        assertTrue(h.state.timeline.motionTracks.isEmpty())
    }
}
