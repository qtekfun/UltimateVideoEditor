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
            TimelineSnapshot.HEADER_BYTES + 2 * TimelineSnapshot.TRACK_BYTES + 2 * TimelineSnapshot.CLIP_BYTES +
                TimelineSnapshot.TRAILER_BYTES + TimelineSnapshot.KEYFRAME_TRAILER_BYTES + TimelineSnapshot.RETIME_TRAILER_BYTES,
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
        assertEquals(
            TimelineSnapshot.HEADER_BYTES + TimelineSnapshot.TRAILER_BYTES + TimelineSnapshot.KEYFRAME_TRAILER_BYTES + TimelineSnapshot.RETIME_TRAILER_BYTES,
            buffer.remaining(),
        )
    }

    @Test
    fun `transitions follow the clips with their own count`() {
        val snapshot = TimelineSnapshot(
            30, 1,
            listOf(SnapshotTrackType.VIDEO, SnapshotTrackType.VIDEO),
            listOf(clip(1), clip(2, track = 1)),
            listOf(SnapshotTransition(0, 100, 5, 6), SnapshotTransition(1, 40, 2, 3)),
        )

        val b = snapshot.encode()

        val trailer = TimelineSnapshot.HEADER_BYTES + 2 * TimelineSnapshot.TRACK_BYTES + 2 * TimelineSnapshot.CLIP_BYTES
        assertEquals(
            trailer + TimelineSnapshot.TRAILER_BYTES + 2 * TimelineSnapshot.TRANSITION_BYTES + TimelineSnapshot.KEYFRAME_TRAILER_BYTES + TimelineSnapshot.RETIME_TRAILER_BYTES,
            b.remaining(),
        )
        assertEquals(2, b.getInt(trailer))
        assertEquals(0, b.getInt(trailer + 4))
        assertEquals(100L, b.getLong(trailer + 12))
        assertEquals(5L, b.getLong(trailer + 20))
        assertEquals(6L, b.getLong(trailer + 28))
        assertEquals(1, b.getInt(trailer + 36))
        assertEquals(3L, b.getLong(trailer + 60))
    }

    @Test
    fun `invalid transitions are rejected before reaching native code`() {
        val tracks = listOf(SnapshotTrackType.VIDEO)
        assertThrows(IllegalArgumentException::class.java) {
            TimelineSnapshot(30, 1, tracks, emptyList(), listOf(SnapshotTransition(1, 0, 1, 1)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TimelineSnapshot(30, 1, tracks, emptyList(), listOf(SnapshotTransition(0, 10, -1, 1)))
        }
    }

    @Test
    fun `retimes follow the keyframes with their own count`() {
        val snapshot = TimelineSnapshot(
            30, 1,
            listOf(SnapshotTrackType.VIDEO),
            listOf(clip(key = 7), clip(key = 9, start = 100), clip(key = 11, start = 200)),
            keyframes = listOf(SnapshotKeyframe(7, 3)),
            retimes = listOf(SnapshotRetime(9, 200, reverse = true), SnapshotRetime(11, 1, freeze = true)),
        )
        val b = snapshot.encode()
        val keyframes = TimelineSnapshot.HEADER_BYTES + TimelineSnapshot.TRACK_BYTES + 3 * TimelineSnapshot.CLIP_BYTES +
            TimelineSnapshot.TRAILER_BYTES + TimelineSnapshot.KEYFRAME_TRAILER_BYTES + TimelineSnapshot.KEYFRAME_BYTES
        assertEquals(keyframes + TimelineSnapshot.RETIME_TRAILER_BYTES + 2 * TimelineSnapshot.RETIME_BYTES, b.remaining())
        assertEquals(TimelineSnapshot.VERSION, b.getInt(4))
        assertEquals(2, b.getInt(keyframes))
        assertEquals(9L, b.getLong(keyframes + 4))
        assertEquals(200L, b.getLong(keyframes + 12))
        assertEquals(1, b.getInt(keyframes + 20)) // reverse
        assertEquals(11L, b.getLong(keyframes + 28))
        assertEquals(1L, b.getLong(keyframes + 36))
        assertEquals(2, b.getInt(keyframes + 44)) // freeze
    }

    @Test
    fun `retimes of missing clips or empty spans are rejected`() {
        val tracks = listOf(SnapshotTrackType.VIDEO)
        assertThrows(IllegalArgumentException::class.java) {
            TimelineSnapshot(30, 1, tracks, listOf(clip(1)), retimes = listOf(SnapshotRetime(2, 10)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TimelineSnapshot(30, 1, tracks, listOf(clip(1)), retimes = listOf(SnapshotRetime(1, 0)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TimelineSnapshot(30, 1, tracks, listOf(clip(1)), retimes = listOf(SnapshotRetime(1, 5), SnapshotRetime(1, 6)))
        }
    }

    @Test
    fun `keyframe markers follow the transitions with their own count`() {
        val snapshot = TimelineSnapshot(
            30, 1,
            listOf(SnapshotTrackType.VIDEO),
            listOf(clip(key = 7), clip(key = 9, start = 100)),
            keyframes = listOf(SnapshotKeyframe(7, 0), SnapshotKeyframe(9, 42)),
        )
        val b = snapshot.encode()
        val keys = TimelineSnapshot.HEADER_BYTES + TimelineSnapshot.TRACK_BYTES + 2 * TimelineSnapshot.CLIP_BYTES +
            TimelineSnapshot.TRAILER_BYTES
        assertEquals(keys + TimelineSnapshot.KEYFRAME_TRAILER_BYTES + 2 * TimelineSnapshot.KEYFRAME_BYTES + TimelineSnapshot.RETIME_TRAILER_BYTES, b.remaining())
        assertEquals(2, b.getInt(keys))
        assertEquals(7L, b.getLong(keys + 4))
        assertEquals(0L, b.getLong(keys + 12))
        assertEquals(9L, b.getLong(keys + 20))
        assertEquals(42L, b.getLong(keys + 28))
    }

    @Test
    fun `keyframes of unknown clips or before their clip are rejected`() {
        val tracks = listOf(SnapshotTrackType.VIDEO)
        assertThrows(IllegalArgumentException::class.java) {
            TimelineSnapshot(30, 1, tracks, listOf(clip(key = 7)), keyframes = listOf(SnapshotKeyframe(8, 0)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TimelineSnapshot(30, 1, tracks, listOf(clip(key = 7)), keyframes = listOf(SnapshotKeyframe(7, -1)))
        }
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
