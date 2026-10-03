package com.ultimatevideo.uveditor.ui.editor

import com.ultimatevideo.uveditor.domain.FrameIndex
import com.ultimatevideo.uveditor.domain.clip
import com.ultimatevideo.uveditor.domain.timeline
import com.ultimatevideo.uveditor.domain.track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChromeTest {
    private val withClip = EditorState(
        isLoading = false,
        timeline = timeline(track("v1", clip("a", 10, 50))),
        selectedClipId = "a",
    )

    @Test
    fun `a playhead tick leaves the chrome equal while the inspector is closed`() {
        val a = chromeOf(withClip.copy(playhead = FrameIndex(20)))
        val b = chromeOf(withClip.copy(playhead = FrameIndex(21)))
        assertEquals(a, b)
    }

    @Test
    fun `the chrome still learns whether the selected clip is under the playhead`() {
        assertFalse(chromeOf(withClip.copy(playhead = FrameIndex(0))).selectedClipVisible)
        assertTrue(chromeOf(withClip.copy(playhead = FrameIndex(20))).selectedClipVisible)
        assertNotEquals(
            chromeOf(withClip.copy(playhead = FrameIndex(0))),
            chromeOf(withClip.copy(playhead = FrameIndex(20))),
        )
    }

    @Test
    fun `an open inspector gets the live playhead`() {
        val a = chromeOf(withClip.copy(inspectorOpen = true, playhead = FrameIndex(20)))
        val b = chromeOf(withClip.copy(inspectorOpen = true, playhead = FrameIndex(21)))
        assertNotEquals(a, b)
        assertEquals(FrameIndex(21), b.state.playhead)
    }

    @Test
    fun `other changes still reach the chrome`() {
        val a = chromeOf(withClip.copy(isPlaying = false))
        val b = chromeOf(withClip.copy(isPlaying = true))
        assertNotEquals(a, b)
    }
}
