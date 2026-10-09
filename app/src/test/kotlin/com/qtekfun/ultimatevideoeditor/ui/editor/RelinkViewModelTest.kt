package com.qtekfun.ultimatevideoeditor.ui.editor

import com.qtekfun.ultimatevideoeditor.data.MediaCaches
import com.qtekfun.ultimatevideoeditor.data.MediaImportException
import com.qtekfun.ultimatevideoeditor.data.MediaImporter
import com.qtekfun.ultimatevideoeditor.data.MediaProblem
import com.qtekfun.ultimatevideoeditor.data.ProbedMedia
import com.qtekfun.ultimatevideoeditor.data.ProjectError
import com.qtekfun.ultimatevideoeditor.data.ProjectStore
import com.qtekfun.ultimatevideoeditor.data.model.ClipDto
import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto
import com.qtekfun.ultimatevideoeditor.data.model.ProjectDto
import com.qtekfun.ultimatevideoeditor.data.model.ProjectSettingsDto
import com.qtekfun.ultimatevideoeditor.data.model.TrackDto
import com.qtekfun.ultimatevideoeditor.engine.timeline.HitKind
import com.qtekfun.ultimatevideoeditor.engine.timeline.TimelineHit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import com.qtekfun.ultimatevideoeditor.ui.text.english

@OptIn(ExperimentalCoroutinesApi::class)
class RelinkViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeStore(var project: ProjectDto) : ProjectStore {
        val saved = mutableListOf<ProjectDto>()
        var failSave = false

        override suspend fun load(id: String): ProjectDto = project

        override suspend fun save(project: ProjectDto) {
            if (failSave) throw ProjectError.Io("disk full", java.io.IOException("full"))
            saved += project
            this.project = project
        }
    }

    /** Knows the files in [media]; any other uri fails with the problem registered in [problems] (default: not found). */
    private class FakeImporter(val media: MutableMap<String, ProbedMedia>) : MediaImporter {
        val problems = mutableMapOf<String, MediaProblem>()

        override suspend fun import(uri: String): ProbedMedia =
            media[uri] ?: throw MediaImportException("cannot open $uri", problem = problems[uri] ?: MediaProblem.UNREADABLE)
    }

    private class RecordingCaches : MediaCaches {
        val invalidated = mutableListOf<String>()

        override fun invalidate(assetId: String) {
            invalidated += assetId
        }
    }

    private val settings = ProjectSettingsDto(1920, 1080, 30, 1, "Rec709-SDR")
    private fun asset(id: String) =
        MediaAssetDto(id, "content://m/$id", durationFrames = 300, nativeFpsNum = 30, nativeFpsDen = 1, colorSpace = "Rec709-SDR")

    private fun clipDto(id: String, asset: String, start: Long) =
        ClipDto(id, asset, timelineStartFrame = start, sourceInFrame = 0, sourceOutFrame = 100)

    // Base v1: c1 (a1), c2 (a2). Overlay v2: x (a1). a1 is the file that goes missing.
    private val project = ProjectDto(
        id = "p1",
        name = "Test",
        settings = settings,
        mediaLibrary = listOf(asset("a1"), asset("a2")),
        tracks = listOf(
            TrackDto("v2", "video", 0, listOf(clipDto("x", "a1", 0))),
            TrackDto("v1", "video", 1, listOf(clipDto("c1", "a1", 0), clipDto("c2", "a2", 100))),
            TrackDto("a1", "audio", 2),
        ),
    )

    private fun video(seconds: Long = 30, audio: Boolean = true, name: String? = null) =
        ProbedMedia(seconds * 1_000_000, 30, 1, "Rec709-SDR", hasVideo = true, hasAudio = audio, displayName = name)

    private class Harness(
        val vm: EditorViewModel,
        val store: FakeStore,
        val importer: FakeImporter,
        val caches: RecordingCaches,
        val effects: MutableList<EditorEffect>,
    ) {
        val state get() = vm.state.value
        val messages get() = effects.filterIsInstance<EditorEffect.ShowMessage>().map { it.text.english() }
    }

    private fun TestScope.harness(
        known: Map<String, ProbedMedia> = mapOf("content://m/a2" to video()),
        problems: Map<String, MediaProblem> = emptyMap(),
    ): Harness {
        val store = FakeStore(project)
        val importer = FakeImporter(known.toMutableMap()).also { it.problems += problems }
        val caches = RecordingCaches()
        val vm = EditorViewModel("p1", store, importer, idGenerator = { "n1" }, mediaCaches = caches)
        val effects = mutableListOf<EditorEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { effects += it } }
        advanceUntilIdle()
        return Harness(vm, store, importer, caches, effects)
    }

    // region detection

    @Test
    fun `unreadable files are flagged at load and the rest of the library is playable`() = runTest(dispatcher) {
        val h = harness()

        assertEquals(mapOf("a1" to MediaProblem.UNREADABLE), h.state.missingMedia)
        assertEquals(listOf("a2"), h.state.playableAssets.map { it.id })
        val summary = h.state.missingAssets.single()
        assertEquals("a1", summary.assetId)
        assertEquals(2, summary.clipCount)
    }

    @Test
    fun `the number of missing files is saved so the project list can flag the project`() = runTest(dispatcher) {
        val h = harness()
        h.importer.media["content://new/a1"] = video()

        // Opening alone writes nothing; the first save after an edit carries the count.
        assertTrue(h.store.saved.isEmpty())
        h.vm.onIntent(EditorIntent.TapTimeline(TimelineHit(HitKind.CLIP, 1, h.vm.clipKey("c2"), 120)))
        h.vm.onIntent(EditorIntent.SetPlayhead(150))
        h.vm.onIntent(EditorIntent.SplitAtPlayhead)
        advanceTimeBy(600)
        assertEquals(1, h.store.saved.last().missingMedia)

        h.vm.onIntent(EditorIntent.RelinkAsset("a1", "content://new/a1"))
        runCurrent()
        advanceTimeBy(600)
        assertEquals(0, h.store.saved.last().missingMedia)
    }

    @Test
    fun `the reason a file cannot be used is kept`() = runTest(dispatcher) {
        val h = harness(problems = mapOf("content://m/a1" to MediaProblem.PERMISSION_LOST))

        assertEquals(MediaProblem.PERMISSION_LOST, h.state.missingMedia["a1"])
    }

    @Test
    fun `nothing is flagged when every file opens`() = runTest(dispatcher) {
        val h = harness(known = mapOf("content://m/a1" to video(), "content://m/a2" to video()))

        assertTrue(h.state.missingMedia.isEmpty())
        assertEquals(2, h.state.playableAssets.size)
    }

    @Test
    fun `clips of missing media are marked for the canvas and the others are not`() = runTest(dispatcher) {
        val h = harness()

        val flags = h.vm.snapshotOf(h.state).clips.associate { it.clipKey to it.missing }

        assertEquals(true, flags[h.vm.clipKey("c1")])
        assertEquals(true, flags[h.vm.clipKey("x")])
        assertEquals(false, flags[h.vm.clipKey("c2")])
    }

    @Test
    fun `the project stays editable while media is missing`() = runTest(dispatcher) {
        val h = harness()

        h.vm.onIntent(EditorIntent.TapTimeline(TimelineHit(HitKind.CLIP, 1, h.vm.clipKey("c2"), 120)))
        h.vm.onIntent(EditorIntent.SetPlayhead(150))
        h.vm.onIntent(EditorIntent.SplitAtPlayhead)

        assertEquals(3, h.state.timeline.track("v1")!!.clips.size)
        assertTrue(h.state.canUndo)
    }

    @Test
    fun `a missing library entry cannot be placed on the timeline`() = runTest(dispatcher) {
        val h = harness()

        h.vm.onIntent(EditorIntent.AddAsset("a1"))

        assertEquals(2, h.state.timeline.track("v1")!!.clips.size)
        assertTrue(h.messages.single().contains("missing"))
    }

    // endregion

    // region relink

    @Test
    fun `asking to relink opens the picker for that file`() = runTest(dispatcher) {
        val h = harness()

        h.vm.onIntent(EditorIntent.RequestRelink("a1"))

        assertEquals(listOf<EditorEffect>(EditorEffect.LaunchRelinkPicker("a1")), h.effects)
    }

    @Test
    fun `the relink list opens and closes`() = runTest(dispatcher) {
        val h = harness()

        h.vm.onIntent(EditorIntent.ShowRelink)
        assertTrue(h.state.relinkOpen)
        h.vm.onIntent(EditorIntent.HideRelink)
        assertFalse(h.state.relinkOpen)
    }

    @Test
    fun `relinking points the asset at the new file clears the flag and saves`() = runTest(dispatcher) {
        val h = harness()
        h.importer.media["content://new/a1"] = video(seconds = 60, name = "intro_v2.mp4")
        val keyBefore = h.vm.assetKey("a1")
        h.vm.onIntent(EditorIntent.ShowRelink)

        h.vm.onIntent(EditorIntent.RelinkAsset("a1", "content://new/a1"))
        runCurrent()

        val relinked = h.state.assets.first { it.id == "a1" }
        assertEquals("content://new/a1", relinked.uri)
        assertEquals(1800, relinked.durationFrames)
        assertEquals("intro_v2.mp4", relinked.displayName)
        assertTrue(h.state.missingMedia.isEmpty())
        assertFalse(h.state.relinkOpen)
        assertEquals(listOf("a1"), h.caches.invalidated)
        // Native caches are keyed by asset key: the new file must not inherit the old one's.
        assertNotEquals(keyBefore, h.vm.assetKey("a1"))
        advanceTimeBy(600)
        assertEquals("content://new/a1", h.store.saved.last().mediaLibrary.first { it.id == "a1" }.uri)
        assertEquals("Relinked intro_v2.mp4", h.messages.last())
    }

    @Test
    fun `relinking is a saved library change and not an undo step`() = runTest(dispatcher) {
        val h = harness()
        h.importer.media["content://new/a1"] = video()

        h.vm.onIntent(EditorIntent.RelinkAsset("a1", "content://new/a1"))
        runCurrent()

        assertFalse(h.state.canUndo)
        // Clips keep their place and source range.
        assertEquals(listOf("c1", "c2"), h.state.timeline.track("v1")!!.clips.map { it.id })
    }

    @Test
    fun `a relinked asset is playable again`() = runTest(dispatcher) {
        val h = harness()
        h.importer.media["content://new/a1"] = video()

        h.vm.onIntent(EditorIntent.RelinkAsset("a1", "content://new/a1"))
        runCurrent()

        assertEquals(setOf("a1", "a2"), h.state.playableAssets.map { it.id }.toSet())
        assertEquals(false, h.vm.snapshotOf(h.state).clips.first { it.clipKey == h.vm.clipKey("c1") }.missing)
    }

    @Test
    fun `a replacement without video is refused and nothing changes`() = runTest(dispatcher) {
        val h = harness()
        h.importer.media["content://new/a1"] = ProbedMedia(30_000_000, 30, 1, "Rec709-SDR", hasVideo = false, hasAudio = true)

        h.vm.onIntent(EditorIntent.RelinkAsset("a1", "content://new/a1"))
        runCurrent()

        assertEquals("content://m/a1", h.state.assets.first { it.id == "a1" }.uri)
        assertTrue("a1" in h.state.missingMedia)
        assertTrue(h.caches.invalidated.isEmpty())
        assertTrue(h.messages.single().contains("no video"))
        advanceTimeBy(600)
        assertTrue(h.store.saved.isEmpty())
    }

    @Test
    fun `a file already in the project cannot replace another`() = runTest(dispatcher) {
        val h = harness()

        h.vm.onIntent(EditorIntent.RelinkAsset("a1", "content://m/a2"))
        runCurrent()

        assertTrue("a1" in h.state.missingMedia)
        assertTrue(h.messages.single().contains("already in the project"))
    }

    @Test
    fun `a file that cannot be opened is reported and nothing changes`() = runTest(dispatcher) {
        val h = harness()

        h.vm.onIntent(EditorIntent.RelinkAsset("a1", "content://nowhere"))
        runCurrent()

        assertTrue("a1" in h.state.missingMedia)
        assertTrue(h.messages.single().contains("cannot open"))
    }

    @Test
    fun `a shorter replacement is accepted with a warning`() = runTest(dispatcher) {
        val h = harness()
        // The clips read up to source frame 100 (3.3 s); this file is 2 s long.
        h.importer.media["content://new/a1"] = video(seconds = 2)

        h.vm.onIntent(EditorIntent.RelinkAsset("a1", "content://new/a1"))
        runCurrent()

        assertTrue(h.state.missingMedia.isEmpty())
        assertTrue(h.messages.last().contains("shorter"))
    }

    @Test
    fun `relinking an unknown asset says so`() = runTest(dispatcher) {
        val h = harness()

        h.vm.onIntent(EditorIntent.RelinkAsset("nope", "content://new/x"))
        runCurrent()

        assertTrue(h.messages.single().contains("no longer in the project"))
    }

    @Test
    fun `relinking one file leaves the other missing files flagged`() = runTest(dispatcher) {
        val h = harness(known = emptyMap())
        h.importer.media["content://new/a1"] = video()

        h.vm.onIntent(EditorIntent.RelinkAsset("a1", "content://new/a1"))
        runCurrent()

        assertEquals(setOf("a2"), h.state.missingMedia.keys)
    }

    // endregion

    // region saving

    @Test
    fun `a failed autosave stays visible and is retried`() = runTest(dispatcher) {
        val h = harness()
        h.store.failSave = true
        h.vm.onIntent(EditorIntent.TapTimeline(TimelineHit(HitKind.CLIP, 1, h.vm.clipKey("c2"), 120)))
        h.vm.onIntent(EditorIntent.SetPlayhead(150))
        h.vm.onIntent(EditorIntent.SplitAtPlayhead)

        advanceTimeBy(600)
        assertTrue(h.state.saveError!!.contains("disk full"))
        assertEquals(1, h.messages.count { it.startsWith("Could not save") })

        // The disk comes back: the retry saves without any further edit.
        h.store.failSave = false
        advanceTimeBy(5_100)
        assertNull(h.state.saveError)
        assertEquals(1, h.store.saved.size)
    }

    @Test
    fun `leaving is refused while the save is failing and offered a choice`() = runTest(dispatcher) {
        val h = harness()
        h.store.failSave = true
        h.vm.onIntent(EditorIntent.TapTimeline(TimelineHit(HitKind.CLIP, 1, h.vm.clipKey("c2"), 120)))
        h.vm.onIntent(EditorIntent.SetPlayhead(150))
        h.vm.onIntent(EditorIntent.SplitAtPlayhead)

        h.vm.onIntent(EditorIntent.Back)
        runCurrent()

        assertTrue(h.state.leaveBlockedBySave)
        assertFalse(h.effects.contains(EditorEffect.Close))

        h.vm.onIntent(EditorIntent.LeaveWithoutSaving)
        assertTrue(h.effects.contains(EditorEffect.Close))
    }

    @Test
    fun `retrying after the disk recovers saves and lets the user leave`() = runTest(dispatcher) {
        val h = harness()
        h.store.failSave = true
        h.vm.onIntent(EditorIntent.TapTimeline(TimelineHit(HitKind.CLIP, 1, h.vm.clipKey("c2"), 120)))
        h.vm.onIntent(EditorIntent.SetPlayhead(150))
        h.vm.onIntent(EditorIntent.SplitAtPlayhead)
        h.vm.onIntent(EditorIntent.Back)
        runCurrent()
        assertTrue(h.state.leaveBlockedBySave)

        h.store.failSave = false
        h.vm.onIntent(EditorIntent.RetrySave)
        runCurrent()

        assertNull(h.state.saveError)
        assertFalse(h.state.leaveBlockedBySave)
        assertEquals(1, h.store.saved.size)

        h.vm.onIntent(EditorIntent.Back)
        runCurrent()
        assertTrue(h.effects.contains(EditorEffect.Close))
    }

    @Test
    fun `leaving with everything saved closes at once`() = runTest(dispatcher) {
        val h = harness()

        h.vm.onIntent(EditorIntent.Back)
        runCurrent()

        assertEquals(listOf<EditorEffect>(EditorEffect.Close), h.effects)
        assertFalse(h.state.leaveBlockedBySave)
    }

    // endregion
}
