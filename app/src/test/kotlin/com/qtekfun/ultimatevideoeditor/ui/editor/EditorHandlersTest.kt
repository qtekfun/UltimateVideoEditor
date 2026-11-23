package com.qtekfun.ultimatevideoeditor.ui.editor

import com.qtekfun.ultimatevideoeditor.domain.Timeline
import com.qtekfun.ultimatevideoeditor.domain.Track
import com.qtekfun.ultimatevideoeditor.domain.TrackType
import com.qtekfun.ultimatevideoeditor.ui.editor.project.syncedTo
import com.qtekfun.ultimatevideoeditor.ui.editor.timeline.LaneHandler
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Feature handlers and the pure reducers that came out of the ViewModel run without it. */
class EditorHandlersTest {
    private val base = Timeline(listOf(Track("track-v1", TrackType.VIDEO), Track("track-a1", TrackType.AUDIO)))

    @Test
    fun addingAVideoLaneGoesOnTopAndBecomesTheActiveLane() {
        val host = FakeEditorHost(base, TestScope())
        LaneHandler(host).addTrack(TrackType.VIDEO)

        assertEquals(listOf("track-v2", "track-v1", "track-a1"), host.history.timeline.tracks.map { it.id })
        assertEquals("track-v2", host.state.value.selectedTrackId)
        assertTrue(host.state.value.canUndo)
        assertEquals(1, host.saves)
    }

    @Test
    fun addingAnAudioLaneGoesAtTheBottom() {
        val host = FakeEditorHost(base, TestScope())
        LaneHandler(host).addTrack(TrackType.AUDIO)

        assertEquals(listOf("track-v1", "track-a1", "track-a2"), host.history.timeline.tracks.map { it.id })
    }

    @Test
    fun movingALaneWithoutASelectionSaysSoAndChangesNothing() {
        val host = FakeEditorHost(base, TestScope())
        LaneHandler(host).moveSelectedTrack(1)

        assertTrue(host.effects.single() is EditorEffect.ShowMessage)
        assertEquals(base, host.history.timeline)
        assertEquals(0, host.saves)
    }

    @Test
    fun syncingToAnOlderTimelineDropsTheSelectionOfAClipThatIsGone() {
        val state = EditorState(selectedClipId = "gone", selectedClipIds = setOf("gone"), selectedTrackId = "missing-lane")

        val synced = state.syncedTo(base, canUndo = false, canRedo = true)

        assertEquals(base, synced.timeline)
        assertNull(synced.selectedClipId)
        assertTrue(synced.selectedClipIds.isEmpty())
        // The lane that vanished falls back to the first video lane.
        assertEquals("track-v1", synced.selectedTrackId)
        assertTrue(synced.canRedo)
    }
}
