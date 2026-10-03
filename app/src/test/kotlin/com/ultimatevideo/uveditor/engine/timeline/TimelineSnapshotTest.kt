package com.ultimatevideo.uveditor.engine.timeline

import java.io.File
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineSnapshotTest {

    private fun clip(
        key: Long = 1,
        track: Int = 0,
        start: Long = 0,
        duration: Long = 100,
        selected: Boolean = false,
    ) = SnapshotClip(key, track, 5, start, duration, 12, 30, 1, selected)

    @Test
    fun `encoded size matches the documented layout`() {
        val snapshot = TimelineSnapshot(
            30000, 1001,
            listOf(SnapshotTrackType.VIDEO, SnapshotTrackType.AUDIO),
            listOf(clip(1), clip(2, track = 1)),
        )
        val buffer = snapshot.encode()
        assertTrue(buffer.isDirect)
        assertEquals(ByteOrder.LITTLE_ENDIAN, buffer.order())
        assertEquals(
            TimelineSnapshot.HEADER_BYTES + 2 * TimelineSnapshot.TRACK_BYTES + 2 * TimelineSnapshot.CLIP_BYTES,
            buffer.remaining(),
        )
    }

    @Test
    fun `fields land at their wire offsets`() {
        val snapshot = TimelineSnapshot(
            30000, 1001,
            listOf(SnapshotTrackType.VIDEO, SnapshotTrackType.TITLE),
            listOf(clip(key = 77, track = 1, start = 500, duration = 250, selected = true)),
        )
        val b = snapshot.encode()
        assertEquals(TimelineSnapshot.MAGIC, b.getInt(0))
        assertEquals(TimelineSnapshot.VERSION, b.getInt(4))
        assertEquals(30000, b.getInt(8))
        assertEquals(1001, b.getInt(12))
        assertEquals(2, b.getInt(16))
        assertEquals(1, b.getInt(20))
        assertEquals(0, b.getInt(24))
        assertEquals(2, b.getInt(28))
        val c = TimelineSnapshot.HEADER_BYTES + 2 * TimelineSnapshot.TRACK_BYTES
        assertEquals(77L, b.getLong(c))
        assertEquals(1, b.getInt(c + 8))
        assertEquals(5L, b.getLong(c + 12))
        assertEquals(500L, b.getLong(c + 20))
        assertEquals(250L, b.getLong(c + 28))
        assertEquals(12L, b.getLong(c + 36))
        assertEquals(30, b.getInt(c + 44))
        assertEquals(1, b.getInt(c + 48))
        assertEquals(1, b.getInt(c + 52))
    }

    @Test
    fun `empty timeline encodes to a header only`() {
        val buffer = TimelineSnapshot(30, 1, emptyList(), emptyList()).encode()
        assertEquals(TimelineSnapshot.HEADER_BYTES, buffer.remaining())
    }

    @Test
    fun `invalid clips are rejected before reaching native code`() {
        val tracks = listOf(SnapshotTrackType.VIDEO)
        assertThrows(IllegalArgumentException::class.java) { TimelineSnapshot(30, 1, tracks, listOf(clip(track = 1))) }
        assertThrows(IllegalArgumentException::class.java) { TimelineSnapshot(30, 1, tracks, listOf(clip(duration = 0))) }
        assertThrows(IllegalArgumentException::class.java) { TimelineSnapshot(30, 1, tracks, listOf(clip(start = -1))) }
        assertThrows(IllegalArgumentException::class.java) { TimelineSnapshot(0, 1, tracks, emptyList()) }
    }

    @Test
    fun `waveform cache path is confined to the waveforms directory`() {
        val root = kotlin.io.path.createTempDirectory("uv-wave").toFile()
        try {
            val cache = WaveformCache(root)
            assertEquals(File(File(root, "waveforms"), "asset-1.peaks"), cache.fileFor("asset-1"))
            val evil = cache.fileFor("../../etc/passwd")
            assertEquals(File(root, "waveforms").canonicalPath, evil.canonicalFile.parent)
            assertThrows(IllegalArgumentException::class.java) { cache.fileFor(" ") }
        } finally {
            root.deleteRecursively()
        }
    }
}
