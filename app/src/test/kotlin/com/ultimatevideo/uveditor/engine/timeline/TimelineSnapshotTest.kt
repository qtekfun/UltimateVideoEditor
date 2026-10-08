package com.ultimatevideo.uveditor.engine.timeline

import java.io.File
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineSnapshotTest {

    /** What is left of the buffer before the sound-shaping count (version 9) that closes every snapshot; these tests have none. */
    private fun java.nio.ByteBuffer.legacyRemaining() = remaining() - TimelineSnapshot.SHAPING_TRAILER_BYTES

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
                TimelineSnapshot.TRAILER_BYTES + TimelineSnapshot.KEYFRAME_TRAILER_BYTES + TimelineSnapshot.RETIME_TRAILER_BYTES +
                TimelineSnapshot.MARKER_TRAILER_BYTES + TimelineSnapshot.LABEL_TRAILER_BYTES,
            buffer.legacyRemaining(),
        )
    }

    @Test
    fun `lane flags ride in the high bits of the track word`() {
        val snapshot = TimelineSnapshot(
            30, 1,
            listOf(SnapshotTrackType.VIDEO, SnapshotTrackType.AUDIO, SnapshotTrackType.AUDIO, SnapshotTrackType.AUDIO),
            listOf(clip(1)),
            trackFlags = listOf(0, TimelineSnapshot.TRACK_MUTED, TimelineSnapshot.TRACK_SOLO, TimelineSnapshot.TRACK_MUTED or TimelineSnapshot.TRACK_SOLO),
        )
        val b = snapshot.encode()
        val first = TimelineSnapshot.HEADER_BYTES
        assertEquals(0, b.getInt(first))
        assertEquals(1 or (1 shl 8), b.getInt(first + 4))
        assertEquals(1 or (1 shl 9), b.getInt(first + 8))
        assertEquals(1 or (3 shl 8), b.getInt(first + 12))
        // The type is still the low byte, and without flags the words are the plain types.
        val plain = TimelineSnapshot(30, 1, listOf(SnapshotTrackType.AUDIO, SnapshotTrackType.TITLE), listOf(clip(1))).encode()
        assertEquals(1, plain.getInt(TimelineSnapshot.HEADER_BYTES))
        assertEquals(2, plain.getInt(TimelineSnapshot.HEADER_BYTES + 4))
    }

    @Test
    fun `lane flags need one entry per track and known bits`() {
        val tracks = listOf(SnapshotTrackType.VIDEO, SnapshotTrackType.AUDIO)
        assertThrows(IllegalArgumentException::class.java) { TimelineSnapshot(30, 1, tracks, listOf(clip(1)), trackFlags = listOf(0)) }
        assertThrows(IllegalArgumentException::class.java) { TimelineSnapshot(30, 1, tracks, listOf(clip(1)), trackFlags = listOf(0, 4)) }
        assertThrows(IllegalArgumentException::class.java) { TimelineSnapshot(30, 1, tracks, listOf(clip(1)), trackFlags = listOf(0, -1)) }
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
    fun `selection flags carry selected and primary separately in version 6`() {
        val snapshot = TimelineSnapshot(
            30, 1,
            listOf(SnapshotTrackType.VIDEO),
            listOf(
                clip(key = 1, selected = true).copy(primary = true),
                clip(key = 2, start = 100, selected = true),
                clip(key = 3, start = 200),
                clip(key = 4, start = 300, selected = true).copy(hasFx = true, missing = true, primary = true),
            ),
        )
        val b = snapshot.encode()
        assertEquals(9, TimelineSnapshot.VERSION)
        val first = TimelineSnapshot.HEADER_BYTES + TimelineSnapshot.TRACK_BYTES
        fun flags(index: Int) = b.getInt(first + index * TimelineSnapshot.CLIP_BYTES + 52)
        assertEquals(0b1001, flags(0)) // selected + primary
        assertEquals(0b0001, flags(1)) // selected only: an outline in the softer colour
        assertEquals(0, flags(2))
        assertEquals(0b1111, flags(3)) // every bit together
    }

    @Test
    fun `empty timeline encodes to a header only`() {
        val buffer = TimelineSnapshot(30, 1, emptyList(), emptyList()).encode()
        assertEquals(
            TimelineSnapshot.HEADER_BYTES + TimelineSnapshot.TRAILER_BYTES + TimelineSnapshot.KEYFRAME_TRAILER_BYTES + TimelineSnapshot.RETIME_TRAILER_BYTES +
                TimelineSnapshot.MARKER_TRAILER_BYTES + TimelineSnapshot.LABEL_TRAILER_BYTES,
            buffer.legacyRemaining(),
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
            trailer + TimelineSnapshot.TRAILER_BYTES + 2 * TimelineSnapshot.TRANSITION_BYTES + TimelineSnapshot.KEYFRAME_TRAILER_BYTES + TimelineSnapshot.RETIME_TRAILER_BYTES +
                TimelineSnapshot.MARKER_TRAILER_BYTES + TimelineSnapshot.LABEL_TRAILER_BYTES,
            b.legacyRemaining(),
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
        assertEquals(keyframes + TimelineSnapshot.RETIME_TRAILER_BYTES + 2 * TimelineSnapshot.RETIME_BYTES + TimelineSnapshot.MARKER_TRAILER_BYTES + TimelineSnapshot.LABEL_TRAILER_BYTES, b.legacyRemaining())
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
        assertEquals(
            keys + TimelineSnapshot.KEYFRAME_TRAILER_BYTES + 2 * TimelineSnapshot.KEYFRAME_BYTES + TimelineSnapshot.RETIME_TRAILER_BYTES +
                TimelineSnapshot.MARKER_TRAILER_BYTES + TimelineSnapshot.LABEL_TRAILER_BYTES,
            b.legacyRemaining(),
        )
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
    fun `marker colour and note travel in the last wire word`() {
        val snapshot = TimelineSnapshot(
            30, 1,
            listOf(SnapshotTrackType.VIDEO),
            listOf(clip(1)),
            markers = listOf(
                SnapshotMarker(30),
                SnapshotMarker(60, colorCode = 3),
                SnapshotMarker(90, beat = true, colorCode = 6, hasNote = true),
                SnapshotMarker(120, hasNote = true),
            ),
        )
        val b = snapshot.encode()
        val first = b.legacyRemaining() - TimelineSnapshot.LABEL_TRAILER_BYTES - 4 * TimelineSnapshot.MARKER_BYTES
        assertEquals(0, b.getInt(first + 12))
        assertEquals(3, b.getInt(first + 16 + 12))
        assertEquals(6 or 8, b.getInt(first + 32 + 12))
        assertEquals(8, b.getInt(first + 48 + 12))
    }

    @Test
    fun `a marker colour code outside 0 to 6 is refused`() {
        assertThrows(IllegalArgumentException::class.java) { SnapshotMarker(10, colorCode = 7) }
        assertThrows(IllegalArgumentException::class.java) { SnapshotMarker(10, colorCode = -1) }
    }

    @Test
    fun `markers come before the label trailer with frame and beat flag`() {
        val snapshot = TimelineSnapshot(
            30, 1,
            listOf(SnapshotTrackType.VIDEO),
            listOf(clip(1)),
            markers = listOf(SnapshotMarker(30), SnapshotMarker(90, beat = true)),
        )
        val b = snapshot.encode()
        val markers = b.legacyRemaining() - TimelineSnapshot.LABEL_TRAILER_BYTES - TimelineSnapshot.MARKER_TRAILER_BYTES -
            2 * TimelineSnapshot.MARKER_BYTES
        assertEquals(TimelineSnapshot.VERSION, b.getInt(4))
        assertEquals(2, b.getInt(markers))
        assertEquals(30L, b.getLong(markers + 4))
        assertEquals(0, b.getInt(markers + 12))
        assertEquals(90L, b.getLong(markers + 20))
        assertEquals(1, b.getInt(markers + 28))
        assertEquals(0, b.getInt(markers + TimelineSnapshot.MARKER_TRAILER_BYTES + 2 * TimelineSnapshot.MARKER_BYTES))
        assertEquals(
            markers + TimelineSnapshot.MARKER_TRAILER_BYTES + 2 * TimelineSnapshot.MARKER_BYTES + TimelineSnapshot.LABEL_TRAILER_BYTES,
            b.legacyRemaining(),
        )
    }

    @Test
    fun `labels are the last section with the key, the length and the text padded to four bytes`() {
        val snapshot = TimelineSnapshot(
            30, 1,
            listOf(SnapshotTrackType.TITLE),
            listOf(clip(1), clip(2, start = 100)),
            labels = listOf(SnapshotLabel(1, "HI"), SnapshotLabel(2, "LOWER THIRD")),
        )
        val b = snapshot.encode()
        val labels = b.legacyRemaining() - TimelineSnapshot.LABEL_TRAILER_BYTES -
            (TimelineSnapshot.LABEL_FIXED_BYTES + 4) - (TimelineSnapshot.LABEL_FIXED_BYTES + 12)
        assertEquals(2, b.getInt(labels))
        assertEquals(1L, b.getLong(labels + 4))
        assertEquals(2, b.getInt(labels + 12))
        assertEquals('H'.code.toByte(), b.get(labels + 16))
        assertEquals('I'.code.toByte(), b.get(labels + 17))
        assertEquals(0.toByte(), b.get(labels + 18))
        val second = labels + 4 + TimelineSnapshot.LABEL_FIXED_BYTES + 4
        assertEquals(2L, b.getLong(second))
        assertEquals(11, b.getInt(second + 8))
        assertEquals('L'.code.toByte(), b.get(second + 12))
        assertEquals(second + TimelineSnapshot.LABEL_FIXED_BYTES + 12, b.legacyRemaining())
        assertEquals(4, TimelineSnapshot.paddedLength("HI"))
        assertEquals(0, TimelineSnapshot.paddedLength(""))
        assertEquals(24, TimelineSnapshot.paddedLength("A".repeat(24)))
    }

    @Test
    fun `a label is UTF-8 text of at most 96 bytes for a clip that exists`() {
        val tracks = listOf(SnapshotTrackType.TITLE)
        SnapshotLabel(1, "Café ☕ 🎬 日本語") // accents, symbols, emoji and other scripts are fine
        SnapshotLabel(1, "A".repeat(96))
        assertThrows(IllegalArgumentException::class.java) { SnapshotLabel(1, "A".repeat(97)) }
        assertThrows(IllegalArgumentException::class.java) { SnapshotLabel(1, "語".repeat(33)) } // 99 bytes
        assertThrows(IllegalArgumentException::class.java) { SnapshotLabel(1, "two\nlines") }
        assertThrows(IllegalArgumentException::class.java) { SnapshotLabel(1, "broken \uD83C") } // an unpaired surrogate
        assertThrows(IllegalArgumentException::class.java) { TimelineSnapshot(30, 1, tracks, listOf(clip(1)), labels = listOf(SnapshotLabel(2, "A"))) }
        assertThrows(IllegalArgumentException::class.java) {
            TimelineSnapshot(30, 1, tracks, listOf(clip(1)), labels = listOf(SnapshotLabel(1, "A"), SnapshotLabel(1, "B")))
        }
    }

    @Test
    fun `a label travels as UTF-8 and its length is the byte count`() {
        val text = "Café ☕" // C a f (3) + é (2) + space (1) + ☕ (3) = 9 bytes
        val snapshot = TimelineSnapshot(30, 1, listOf(SnapshotTrackType.TITLE), listOf(clip(1)), labels = listOf(SnapshotLabel(1, text)))
        val b = snapshot.encode()
        val labels = b.legacyRemaining() - TimelineSnapshot.LABEL_TRAILER_BYTES - (TimelineSnapshot.LABEL_FIXED_BYTES + 12)
        assertEquals(1, b.getInt(labels))
        assertEquals(9, b.getInt(labels + 12))
        val bytes = ByteArray(9) { b.get(labels + 16 + it) }
        assertEquals(text, String(bytes, Charsets.UTF_8))
        assertEquals(12, TimelineSnapshot.paddedLength(text))
        assertEquals(12, TimelineSnapshot.paddedLength("Café ☕!!!")) // exactly 12 bytes
        assertEquals(16, TimelineSnapshot.paddedLength("Café ☕!!!!"))
    }

    @Test
    fun `the clip kind rides in bits 4 to 6 of the clip flags`() {
        val snapshot = TimelineSnapshot(
            30, 1, listOf(SnapshotTrackType.VIDEO),
            listOf(
                clip(key = 1, selected = true).copy(primary = true, kind = SnapshotClipKind.STICKER),
                clip(key = 2, start = 100).copy(kind = SnapshotClipKind.MULTICAM),
                clip(key = 3, start = 200),
            ),
        )
        val b = snapshot.encode()
        val first = TimelineSnapshot.HEADER_BYTES + TimelineSnapshot.TRACK_BYTES
        fun flags(index: Int) = b.getInt(first + index * TimelineSnapshot.CLIP_BYTES + 52)
        assertEquals(0b0010_1001, flags(0)) // sticker (2) in bits 4..6, selected + primary
        assertEquals(0b0011_0000, flags(1)) // multicam (3)
        assertEquals(0, flags(2))
    }

    @Test
    fun `detached audio and the link mark ride in bits 7 and 8 of the clip flags`() {
        val snapshot = TimelineSnapshot(
            30, 1, listOf(SnapshotTrackType.VIDEO),
            listOf(
                clip(key = 1).copy(audioDetached = true, linked = true),
                clip(key = 2, start = 100).copy(linked = true),
                clip(key = 3, start = 200, selected = true).copy(audioDetached = true, kind = SnapshotClipKind.STICKER),
                clip(key = 4, start = 300),
            ),
        )
        val b = snapshot.encode()
        val first = TimelineSnapshot.HEADER_BYTES + TimelineSnapshot.TRACK_BYTES
        fun flags(index: Int) = b.getInt(first + index * TimelineSnapshot.CLIP_BYTES + 52)
        assertEquals(0b1_1000_0000, flags(0))
        assertEquals(0b1_0000_0000, flags(1))
        assertEquals(0b0010_0000 or 0b1000_0000 or 1, flags(2)) // sticker, detached, selected: the older bits are untouched
        assertEquals(0, flags(3))
        // The wire version did not change: native reads the bits as zero from older data.
        assertEquals(9, b.getInt(4))
    }

    @Test
    fun `a marker name is a label under the marker key and rides in the label section`() {
        val snapshot = TimelineSnapshot(
            30, 1,
            listOf(SnapshotTrackType.VIDEO),
            listOf(clip(1)),
            markers = listOf(SnapshotMarker(30), SnapshotMarker(90)),
            labels = listOf(SnapshotLabel(SnapshotLabel.markerKey(1), "DROP")),
        )
        val b = snapshot.encode()
        val labels = b.legacyRemaining() - TimelineSnapshot.LABEL_TRAILER_BYTES - (TimelineSnapshot.LABEL_FIXED_BYTES + 4)
        assertEquals(1, b.getInt(labels))
        assertEquals(SnapshotLabel.markerKey(1), b.getLong(labels + 4))
        assertEquals(1, SnapshotLabel.markerIndexOf(SnapshotLabel.markerKey(1)))
        assertEquals(null, SnapshotLabel.markerIndexOf(5))
    }

    @Test
    fun `a marker label needs a marker at that index`() {
        assertThrows(IllegalArgumentException::class.java) {
            TimelineSnapshot(30, 1, listOf(SnapshotTrackType.VIDEO), listOf(clip(1)), markers = listOf(SnapshotMarker(30)), labels = listOf(SnapshotLabel(SnapshotLabel.markerKey(1), "A")))
        }
    }

    @Test
    fun `a marker before frame zero is rejected before reaching native code`() {
        assertThrows(IllegalArgumentException::class.java) {
            TimelineSnapshot(30, 1, emptyList(), emptyList(), markers = listOf(SnapshotMarker(-1)))
        }
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

    @Test
    fun `sound shaping closes the snapshot with the fades, flags, gain and curve points`() {
        val snapshot = TimelineSnapshot(
            30, 1,
            listOf(SnapshotTrackType.AUDIO),
            listOf(clip(1), clip(2, start = 100)),
            shaping = listOf(
                SnapshotShaping(2, fadeInFrames = 12, fadeOutFrames = 30, fadeShape = 2, editable = true, baseDb = -3f),
                SnapshotShaping(1, points = listOf(SnapshotCurvePoint(0, 0f), SnapshotCurvePoint(40, -12.5f))),
            ),
        )
        val b = snapshot.encode()
        assertEquals(TimelineSnapshot.VERSION, b.getInt(4))
        val end = b.remaining()
        val firstEntry = end - (2 * TimelineSnapshot.SHAPING_BYTES + 2 * TimelineSnapshot.SHAPING_POINT_BYTES)
        assertEquals(2, b.getInt(firstEntry - TimelineSnapshot.SHAPING_TRAILER_BYTES))
        assertEquals(2L, b.getLong(firstEntry))
        assertEquals(12, b.getInt(firstEntry + 8))
        assertEquals(30, b.getInt(firstEntry + 12))
        assertEquals(2 or 4, b.getInt(firstEntry + 16)) // shape 2, editable
        assertEquals(0, b.getInt(firstEntry + 20))
        assertEquals(-3f, b.getFloat(firstEntry + 24), 0f)
        val second = firstEntry + TimelineSnapshot.SHAPING_BYTES
        assertEquals(1L, b.getLong(second))
        assertEquals(0, b.getInt(second + 16))
        assertEquals(2, b.getInt(second + 20))
        assertEquals(40L, b.getLong(second + TimelineSnapshot.SHAPING_BYTES + TimelineSnapshot.SHAPING_POINT_BYTES))
        assertEquals(-12.5f, b.getFloat(second + TimelineSnapshot.SHAPING_BYTES + TimelineSnapshot.SHAPING_POINT_BYTES + 8), 0f)
    }

    @Test
    fun `sound shaping is checked against the clips and its own ordering`() {
        val tracks = listOf(SnapshotTrackType.AUDIO)
        assertThrows(IllegalArgumentException::class.java) { TimelineSnapshot(30, 1, tracks, listOf(clip(1)), shaping = listOf(SnapshotShaping(9))) }
        assertThrows(IllegalArgumentException::class.java) {
            TimelineSnapshot(30, 1, tracks, listOf(clip(1)), shaping = listOf(SnapshotShaping(1), SnapshotShaping(1)))
        }
        assertThrows(IllegalArgumentException::class.java) { SnapshotShaping(1, fadeInFrames = -1) }
        assertThrows(IllegalArgumentException::class.java) {
            SnapshotShaping(1, points = listOf(SnapshotCurvePoint(5, 0f), SnapshotCurvePoint(5, 1f)))
        }
        assertThrows(IllegalArgumentException::class.java) { SnapshotShaping(1, points = listOf(SnapshotCurvePoint(-1, 0f))) }
    }
}
