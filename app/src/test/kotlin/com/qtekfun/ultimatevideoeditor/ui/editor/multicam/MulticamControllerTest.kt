package com.qtekfun.ultimatevideoeditor.ui.editor.multicam

import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto
import com.qtekfun.ultimatevideoeditor.domain.EditCommand
import com.qtekfun.ultimatevideoeditor.domain.EditHistory
import com.qtekfun.ultimatevideoeditor.domain.EditResult
import com.qtekfun.ultimatevideoeditor.domain.FrameIndex
import com.qtekfun.ultimatevideoeditor.domain.FrameRate
import com.qtekfun.ultimatevideoeditor.domain.Timeline
import com.qtekfun.ultimatevideoeditor.domain.TrackType
import com.qtekfun.ultimatevideoeditor.domain.beat.PeakEnvelope
import com.qtekfun.ultimatevideoeditor.domain.clip
import com.qtekfun.ultimatevideoeditor.domain.multicam.AngleCut
import com.qtekfun.ultimatevideoeditor.domain.multicam.AngleFeed
import com.qtekfun.ultimatevideoeditor.domain.multicam.MulticamOps
import com.qtekfun.ultimatevideoeditor.domain.timeline
import com.qtekfun.ultimatevideoeditor.domain.track
import com.qtekfun.ultimatevideoeditor.engine.multicam.AngleEnvelopeSource
import com.qtekfun.ultimatevideoeditor.engine.multicam.MulticamServices
import com.qtekfun.ultimatevideoeditor.ui.editor.EditorState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

@OptIn(ExperimentalCoroutinesApi::class)
class MulticamControllerTest {
    private fun speechLike(seconds: Int, seed: Long): FloatArray {
        val random = Random(seed)
        val out = FloatArray(seconds * 100) { 0.02f + random.nextFloat() * 0.02f }
        var t = 1.0
        while (t < seconds - 1) {
            val length = 0.1 + random.nextDouble() * 0.6
            val level = 0.2f + random.nextFloat() * 0.8f
            for (i in (t * 100).toInt() until minOf(out.size, ((t + length) * 100).toInt())) out[i] = level
            t += length + 0.2 + random.nextDouble() * 1.5
        }
        return out
    }

    private val scene = speechLike(80, 5)

    /** A recorder that started [later] bins after the first one, hearing the same scene. */
    private fun heard(later: Int, bins: Int, seed: Long): FloatArray {
        val random = Random(seed)
        return FloatArray(bins) { m -> (if (m + later in scene.indices) scene[m + later] * 0.6f else 0f) + random.nextFloat() * 0.04f }
    }

    private fun asset(id: String, frames: Long = 1500, audio: Boolean = true) =
        MediaAssetDto(id, "content://m/$id", frames, 30, 1, "Rec709-SDR", hasAudio = audio, displayName = "$id.mp4")

    private val envelopes = mapOf(
        "a" to PeakEnvelope(100.0, scene.copyOf(5000)),
        "b" to PeakEnvelope(100.0, heard(later = 300, bins = 4000, seed = 6)), // started 3.00 s = 90 frames after a
        "c" to PeakEnvelope(100.0, speechLike(30, 99)), // unrelated
    )

    private class Fixture(scope: TestScope, services: MulticamServices, assets: List<MediaAssetDto>, timeline: Timeline, playhead: Long = 0) {
        var editor = EditorState(
            isLoading = false, fps = FrameRate(30, 1), assets = assets, timeline = timeline, playhead = FrameIndex(playhead),
        )
        var history = EditHistory(timeline)
        val messages = mutableListOf<String>()
        private var ids = 0
        val controller = MulticamController(
            object : MulticamController.Host {
                override val editor: EditorState get() = this@Fixture.editor
                override fun update(change: (MulticamUiState) -> MulticamUiState) {
                    this@Fixture.editor = this@Fixture.editor.copy(multicam = change(this@Fixture.editor.multicam))
                }
                override fun execute(command: EditCommand): Boolean = when (val r = history.execute(command)) {
                    is EditResult.Success -> {
                        history = r.value
                        this@Fixture.editor = this@Fixture.editor.copy(timeline = history.timeline)
                        true
                    }
                    is EditResult.Failure -> false.also { messages += "failed: ${r.error}" }
                }
                override fun message(text: String) {
                    messages += text
                }
                override fun newId(): String = "id${ids++}"
                override fun assetLengthFrames(assetId: String): Long? = this@Fixture.editor.assets.firstOrNull { it.id == assetId }?.durationFrames
            },
            services,
            scope,
            StandardTestDispatcher(scope.testScheduler),
        )

        fun send(intent: MulticamIntent) = controller.handle(intent)
        val ui: MulticamUiState get() = editor.multicam
    }

    private val services = MulticamServices({ id -> envelopes[id] }, hasProxy = { it.id == "b" }, maxDecoders = 3)
    private fun base() = timeline(track("v1", clip("base-1", 0, 200)), track("a1", type = TrackType.AUDIO))

    private fun TestScope.fixture(vararg assets: MediaAssetDto, playhead: Long = 200) =
        Fixture(this, services, assets.toList(), base(), playhead)

    @Test
    fun `toggling assets builds the draft, refuses pictures and silent files, and caps at six angles`() = runTest {
        val f = fixture(asset("a"), asset("b"), asset("silent", audio = false), asset("pic").copy(isImage = true, hasAudio = false))
        f.send(MulticamIntent.ToggleAsset("a"))
        f.send(MulticamIntent.ToggleAsset("b"))
        assertEquals(listOf("a", "b"), f.ui.draft.assetIds)
        f.send(MulticamIntent.ToggleAsset("silent"))
        f.send(MulticamIntent.ToggleAsset("pic"))
        assertEquals(listOf("a", "b"), f.ui.draft.assetIds)
        assertEquals(2, f.messages.size)
        f.send(MulticamIntent.ToggleAsset("a"))
        assertEquals(listOf("b"), f.ui.draft.assetIds)
        val many = (1..7).map { asset("x$it") }
        val g = fixture(*many.toTypedArray())
        many.forEach { g.send(MulticamIntent.ToggleAsset(it.id)) }
        assertEquals(6, g.ui.draft.assetIds.size)
    }

    @Test
    fun `sync finds the offset of the second angle by its sound and marks an unrelated one as unsure`() = runTest {
        val f = fixture(asset("a"), asset("b"), asset("c"))
        listOf("a", "b", "c").forEach { f.send(MulticamIntent.ToggleAsset(it)) }
        f.send(MulticamIntent.Sync)
        assertTrue(f.ui.syncing)
        advanceUntilIdle()
        assertFalse(f.ui.syncing)
        assertEquals(SyncOutcome.Reference, f.ui.draft.outcomes["a"])
        val b = f.ui.draft.outcomes["b"] as SyncOutcome.Found
        assertTrue(b.confident)
        assertEquals(90L, b.offsetFrames)
        assertEquals(90L, f.ui.draft.offsetOf("b"))
        val c = f.ui.draft.outcomes["c"]
        assertTrue(c is SyncOutcome.Failed || (c is SyncOutcome.Found && !c.confident))
        assertEquals(0L, f.ui.draft.offsetOf("c")) // a doubtful match is not applied
        assertTrue(f.messages.any { it.contains("could not be lined up") })
    }

    @Test
    fun `an angle without a waveform reports why instead of guessing`() = runTest {
        val f = Fixture(this, MulticamServices({ null }, { false }, 1), listOf(asset("a"), asset("b")), base())
        f.send(MulticamIntent.ToggleAsset("a"))
        f.send(MulticamIntent.ToggleAsset("b"))
        f.send(MulticamIntent.Sync)
        advanceUntilIdle()
        assertTrue(f.ui.draft.outcomes["b"] is SyncOutcome.Failed)
    }

    @Test
    fun `create needs two angles`() = runTest {
        val f = fixture(asset("a"))
        f.send(MulticamIntent.ToggleAsset("a"))
        f.send(MulticamIntent.Create)
        assertTrue(f.editor.timeline.multicams.isEmpty())
        assertTrue(f.messages.last().contains("at least 2"))
    }

    @Test
    fun `create after a sync puts the clip on the base at the playhead with the overlap as its length`() = runTest {
        val f = fixture(asset("a", 1500), asset("b", 1200))
        f.send(MulticamIntent.ToggleAsset("a"))
        f.send(MulticamIntent.ToggleAsset("b"))
        f.send(MulticamIntent.Sync)
        advanceUntilIdle()
        f.send(MulticamIntent.Create)
        val group = f.editor.timeline.multicams.single()
        assertEquals(listOf(0L, 90L), group.angles.map { it.offsetFrames })
        // Shared time both cover: [90, 1500) since b starts at 90 and a ends at 1500 (b ends at 1290).
        assertEquals(90L, group.inFrame)
        assertEquals(1200L, group.lengthFrames)
        assertEquals("v1", group.videoTrackId)
        assertEquals("a1", group.audioTrackId)
        assertEquals(200L, group.startFrame)
        assertEquals(emptyList<String>(), f.editor.timeline.invariantViolations())
        assertTrue(f.ui.draft.assetIds.isEmpty())
    }

    @Test
    fun `angles that do not overlap in time are refused`() = runTest {
        val f = fixture(asset("a", 100), asset("b", 100))
        f.send(MulticamIntent.ToggleAsset("a"))
        f.send(MulticamIntent.ToggleAsset("b"))
        repeat(2) { f.send(MulticamIntent.NudgeDraft("b", 100)) } // b starts 200 frames after a ends
        f.send(MulticamIntent.Create)
        assertTrue(f.editor.timeline.multicams.isEmpty())
        assertTrue(f.messages.last().contains("hardly overlap"))
    }

    private fun TestScope.created(): Fixture {
        val f = fixture(asset("a", 1500), asset("b", 1500), asset("c", 1500))
        listOf("a", "b", "c").forEach { f.send(MulticamIntent.ToggleAsset(it)) }
        // Same start for all three (no sync): the clip covers the whole 1500 frames.
        f.send(MulticamIntent.Create)
        return f
    }

    @Test
    fun `tapping an angle cuts at the playhead frame inside the clip`() = runTest {
        val f = created()
        val group = f.editor.timeline.multicams.single()
        f.editor = f.editor.copy(playhead = FrameIndex(group.startFrame + 300), selectedClipId = group.videoClipId(0))
        f.send(MulticamIntent.CutTo(2))
        assertEquals(listOf(AngleCut(0, 0), AngleCut(300, 2)), f.editor.timeline.multicams.single().cuts)
        assertTrue(f.history.canUndo)
        assertEquals(1, f.history.undo().timeline.multicams.single().cuts.size)
    }

    @Test
    fun `recording collects taps and applies them as one undo step`() = runTest {
        val f = created()
        val group = f.editor.timeline.multicams.single()
        f.editor = f.editor.copy(selectedClipId = group.videoClipId(0))
        f.send(MulticamIntent.ToggleRecording)
        assertTrue(f.ui.recording)
        for ((frame, angle) in listOf(100L to 1, 250L to 2, 400L to 0)) {
            f.editor = f.editor.copy(playhead = FrameIndex(group.startFrame + frame))
            f.send(MulticamIntent.CutTo(angle))
        }
        // Nothing is applied until recording stops.
        assertEquals(1, f.editor.timeline.multicams.single().cuts.size)
        assertEquals(3, f.ui.pendingCuts.size)
        val before = f.history
        f.send(MulticamIntent.ToggleRecording)
        assertFalse(f.ui.recording)
        assertEquals(listOf(AngleCut(0, 0), AngleCut(100, 1), AngleCut(250, 2), AngleCut(400, 0)), f.editor.timeline.multicams.single().cuts)
        assertEquals(before.timeline, f.history.undo().timeline) // one step undoes the whole recording
    }

    @Test
    fun `stopping a recording with no taps changes nothing`() = runTest {
        val f = created()
        f.send(MulticamIntent.ToggleRecording)
        val before = f.editor.timeline
        f.send(MulticamIntent.ToggleRecording)
        assertEquals(before, f.editor.timeline)
    }

    @Test
    fun `cutting needs a multicam clip and removing a cut works at the playhead`() = runTest {
        val f = fixture(asset("a"), asset("b"))
        f.send(MulticamIntent.CutTo(1))
        assertTrue(f.messages.last().contains("multicam clip"))
        val g = created()
        val group = g.editor.timeline.multicams.single()
        g.editor = g.editor.copy(playhead = FrameIndex(group.startFrame + 500), selectedClipId = group.videoClipId(0))
        g.send(MulticamIntent.CutTo(1))
        g.editor = g.editor.copy(playhead = FrameIndex(group.startFrame + 600))
        g.send(MulticamIntent.RemoveCutHere(group.id))
        assertEquals(1, g.editor.timeline.multicams.single().cuts.size)
    }

    @Test
    fun `nudging, choosing the sound and flattening go through the history`() = runTest {
        val f = created()
        val id = f.editor.timeline.multicams.single().id
        f.send(MulticamIntent.NudgeAngle(id, 1, 2))
        assertEquals(2L, f.editor.timeline.multicams.single().angles[1].offsetFrames)
        f.send(MulticamIntent.SetAudioAngle(id, 2))
        assertEquals(2, f.editor.timeline.multicams.single().audioAngle)
        f.send(MulticamIntent.Flatten(id))
        assertTrue(f.editor.timeline.multicams.isEmpty())
        assertEquals(emptyList<String>(), f.editor.timeline.invariantViolations())
    }

    @Test
    fun `sync again updates the offsets of the angles it is confident about`() = runTest {
        val f = fixture(asset("a", 1500), asset("b", 1500))
        f.send(MulticamIntent.ToggleAsset("a"))
        f.send(MulticamIntent.ToggleAsset("b"))
        f.send(MulticamIntent.NudgeDraft("b", 100)) // wrong on purpose: the sound says 90
        f.send(MulticamIntent.Create)
        val id = f.editor.timeline.multicams.single().id
        f.send(MulticamIntent.Resync(id))
        advanceUntilIdle()
        assertEquals(90L, f.editor.timeline.multicams.single().angles[1].offsetFrames)
    }

    @Test
    fun `the group the controls act on follows the selection`() {
        val t = MulticamOps.create(base(), MulticamTestGroups.simple()).let { (it as EditResult.Success).value }
        assertEquals("g", MulticamController.groupOf(t, "mc-g-v0")?.id)
        assertEquals("g", MulticamController.groupOf(t, null)?.id) // the only one
        assertEquals("g", MulticamController.groupOf(t, "base-1")?.id) // not part of it, but there is just one
        assertNotNull(MulticamController.groupOf(t, "mc-g-a"))
    }

    @Test
    fun `the viewer budget uses proxies for the angles that have one`() = runTest {
        val f = created()
        val group = f.editor.timeline.multicams.single()
        // The services say only "b" has a proxy and three decoders exist.
        assertEquals(listOf(AngleFeed.FULL, AngleFeed.PROXY, AngleFeed.STILL), f.controller.feeds(group, 0))
        assertEquals(listOf(AngleFeed.STILL, AngleFeed.FULL, AngleFeed.STILL), f.controller.feeds(group, 1))
    }
}

/** A tiny group for tests that do not need the controller. */
internal object MulticamTestGroups {
    fun simple() = com.qtekfun.ultimatevideoeditor.domain.multicam.MulticamClip(
        id = "g", name = "Multicam",
        angles = listOf(
            com.qtekfun.ultimatevideoeditor.domain.multicam.MulticamAngle("x", "Cam A", "a", 0, 1000),
            com.qtekfun.ultimatevideoeditor.domain.multicam.MulticamAngle("y", "Cam B", "b", 0, 1000),
        ),
        audioAngle = 0, videoTrackId = "v1", audioTrackId = "a1", startFrame = 200, inFrame = 0, lengthFrames = 400,
        cuts = listOf(AngleCut(0, 0)),
    )
}
