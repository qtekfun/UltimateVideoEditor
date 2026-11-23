package com.qtekfun.ultimatevideoeditor.ui.editor

import com.qtekfun.ultimatevideoeditor.engine.audio.AudioSnapshot
import com.qtekfun.ultimatevideoeditor.engine.audio.AudioTrackSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MutedSnapshotTest {
    @Test
    fun `every track is muted and nothing else changes`() {
        val snapshot = AudioSnapshot(30, 1, emptyList(), listOf(AudioTrackSpec(1, gainDb = -3f), AudioTrackSpec(2, muted = true)), null)
        val silent = snapshot.withAllTracksMuted()
        assertTrue(silent.tracks.all { it.muted })
        assertEquals(listOf(-3f, 0f), silent.tracks.map { it.gainDb })
        assertEquals(listOf(1L, 2L), silent.tracks.map { it.trackKey })
        assertEquals(snapshot.clips, silent.clips)
    }
}
