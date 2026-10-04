package com.ultimatevideo.uveditor.engine.timeline

import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineTextTest {

    private val direct = Executor { it.run() }

    private class FakeSink : LabelSink {
        val received = LinkedHashMap<Long, RasterLabel>()
        val order = ArrayList<Long>()
        var generation = 0
        var dropOnPut = 0 // when > 0, the sink "resets its atlas" after that many puts

        override fun putLabel(hash: Long, label: RasterLabel) {
            received[hash] = label
            order.add(hash)
            if (dropOnPut > 0 && order.size == dropOnPut) {
                generation++
                received.clear()
            }
        }

        override fun labelGeneration() = generation

        /** Hashes the "atlas" evicted to make room, handed out once by [takeEvicted]. */
        val evicted = ArrayDeque<Long>()

        fun evict(hash: Long) {
            received.remove(hash)
            evicted.addLast(hash)
        }

        override fun takeEvicted(out: LongArray): Int {
            var n = 0
            while (n < out.size && evicted.isNotEmpty()) out[n++] = evicted.removeFirst()
            return n
        }
    }

    private class CountingRasteriser : LabelRasteriser {
        val rendered = ArrayList<LabelNeed>()

        override fun render(need: LabelNeed): RasterLabel? {
            if (need.text.isBlank()) return null
            rendered.add(need)
            return RasterLabel(need.text.length * 6, 14, colour = need.text.any { it.code > 0x2000 }, pixels = ByteBuffer.allocate(4))
        }
    }

    @Test
    fun `the hash is FNV-1a over the size class and the UTF-8 bytes, the same as the native side`() {
        // These constants are also checked in host_tests.cpp (testLabelHashAndUtf8).
        assertEquals(-5088303238967312287L, LabelHash.of("0:05", 1))
        assertEquals(3868398036166957098L, LabelHash.of("Café ☕", 0))
        assertEquals(-5808588758991127739L, LabelHash.of("", 2))
        assertNotEquals(LabelHash.of("A", 0), LabelHash.of("A", 1))
        assertNotEquals(LabelHash.of("A", 0), LabelHash.of("a", 0))
    }

    @Test
    fun `labels needed by a snapshot are sized by what the clip is`() {
        val tracks = listOf(SnapshotTrackType.TITLE, SnapshotTrackType.VIDEO)
        fun clip(key: Long, track: Int, kind: SnapshotClipKind = SnapshotClipKind.DEFAULT) =
            SnapshotClip(key, track, -1, key * 100, 50, 0, 30, 1, kind = kind)
        val snapshot = TimelineSnapshot(
            30, 1, tracks,
            listOf(clip(1, 0), clip(2, 1), clip(3, 1, SnapshotClipKind.STICKER)),
            markers = listOf(SnapshotMarker(10)),
            labels = listOf(
                SnapshotLabel(1, "Title text"), SnapshotLabel(2, "interview.mp4"), SnapshotLabel(3, "Heart"),
                SnapshotLabel(SnapshotLabel.markerKey(0), "Drop"),
            ),
        )
        assertEquals(
            listOf(
                LabelNeed("Title text", TEXT_CLASS_LABEL), LabelNeed("interview.mp4", TEXT_CLASS_SMALL),
                LabelNeed("Heart", TEXT_CLASS_LABEL), LabelNeed("Drop", TEXT_CLASS_SMALL),
            ),
            snapshot.labelNeeds(),
        )
    }

    @Test
    fun `the static glyphs cover what the ruler, lane names, speed badges and timecode are made of`() {
        val small = StaticGlyphs.needs.filter { it.sizeClass == TEXT_CLASS_SMALL }.map { it.text }.toSet()
        val bold = StaticGlyphs.needs.filter { it.sizeClass == TEXT_CLASS_BOLD }.map { it.text }.toSet()
        for (ch in "0123456789:+.-x<|") assertTrue("small $ch", ch.toString() in small)
        for (ch in "0123456789:VATMS") assertTrue("bold $ch", ch.toString() in bold)
    }

    @Test
    fun `the first request sends the static glyphs and the labels, once each`() {
        val sink = FakeSink()
        val raster = CountingRasteriser()
        val pump = LabelPump(raster, sink, direct)
        pump.request(listOf(LabelNeed("Intro", 0), LabelNeed("Café ☕", 1), LabelNeed("Intro", 0)))
        assertEquals(StaticGlyphs.needs.size + 2, sink.received.size)
        assertTrue(LabelHash.of("Café ☕", 1) in sink.received)
        assertTrue(sink.received.getValue(LabelHash.of("Café ☕", 1)).colour)
        // The same snapshot again: nothing is rasterised or sent twice.
        val before = raster.rendered.size
        pump.request(listOf(LabelNeed("Intro", 0), LabelNeed("Café ☕", 1)))
        assertEquals(before, raster.rendered.size)
        // A new name sends just that one.
        pump.request(listOf(LabelNeed("Intro", 0), LabelNeed("Outro", 0)))
        assertEquals(before + 1, raster.rendered.size)
    }

    @Test
    fun `when the canvas drops its atlas everything still needed is sent again`() {
        val sink = FakeSink()
        val pump = LabelPump(CountingRasteriser(), sink, direct)
        pump.request(listOf(LabelNeed("Intro", 0)))
        val first = sink.received.size
        sink.generation++ // the canvas emptied its atlas between two snapshots
        sink.received.clear()
        pump.request(listOf(LabelNeed("Intro", 0)))
        assertEquals(first, sink.received.size)
        assertTrue(LabelHash.of("Intro", 0) in sink.received)
    }

    @Test
    fun `a bitmap the canvas evicted is sent again when it is still needed, and only that one`() {
        val sink = FakeSink()
        val pump = LabelPump(CountingRasteriser(), sink, direct)
        pump.request(listOf(LabelNeed("Intro", 0), LabelNeed("Outro", 0)))
        val sentBefore = sink.order.size
        sink.evict(LabelHash.of("Intro", 0)) // the generation does not change: only this one went
        pump.request(listOf(LabelNeed("Intro", 0), LabelNeed("Outro", 0)))
        assertEquals(sentBefore + 1, sink.order.size)
        assertEquals(LabelHash.of("Intro", 0), sink.order.last())
        assertTrue(LabelHash.of("Intro", 0) in sink.received && LabelHash.of("Outro", 0) in sink.received)
    }

    @Test
    fun `an evicted bitmap that is no longer needed is forgotten and not sent`() {
        val sink = FakeSink()
        val pump = LabelPump(CountingRasteriser(), sink, direct)
        pump.request(listOf(LabelNeed("Intro", 0)))
        val sentBefore = sink.order.size
        sink.evict(LabelHash.of("Intro", 0))
        pump.request(listOf(LabelNeed("Outro", 0)))
        assertEquals(sentBefore + 1, sink.order.size) // only Outro
        pump.request(listOf(LabelNeed("Intro", 0))) // wanted again later: sent then
        assertEquals(sentBefore + 2, sink.order.size)
    }

    @Test
    fun `more evictions than one call can carry are all collected`() {
        val sink = FakeSink()
        val pump = LabelPump(CountingRasteriser(), sink, direct)
        val needs = (1..600).map { LabelNeed("label $it", 0) }
        pump.request(needs)
        val sentBefore = sink.order.size
        for (need in needs) sink.evict(LabelHash.of(need.text, need.sizeClass))
        pump.request(needs)
        assertEquals(sentBefore + 600, sink.order.size)
    }

    @Test
    fun `a reset in the middle of sending is noticed and repaired in the same pass`() {
        val sink = FakeSink()
        sink.dropOnPut = 5 // the atlas fills up and is emptied after the fifth bitmap
        val pump = LabelPump(CountingRasteriser(), sink, direct)
        pump.request(listOf(LabelNeed("Intro", 0), LabelNeed("Outro", 0)))
        assertEquals(1, sink.generation)
        // What was sent before the reset was sent again afterwards: the label is there.
        assertTrue(LabelHash.of("Intro", 0) in sink.received)
        assertTrue(LabelHash.of("Outro", 0) in sink.received)
    }

    @Test
    fun `a text that cannot be drawn is skipped and the rest still goes through`() {
        val sink = FakeSink()
        val pump = LabelPump(CountingRasteriser(), sink, direct)
        pump.request(listOf(LabelNeed(" ", 0), LabelNeed("Ok", 0)))
        assertFalse(LabelHash.of(" ", 0) in sink.received)
        assertTrue(LabelHash.of("Ok", 0) in sink.received)
    }

    @Test
    fun `at most maxLabels labels are sent per generation but the static glyphs always are`() {
        val sink = FakeSink()
        val pump = LabelPump(CountingRasteriser(), sink, direct, maxLabels = StaticGlyphs.needs.size + 3)
        pump.request((1..50).map { LabelNeed("label $it", 0) })
        assertEquals(StaticGlyphs.needs.size + 3, sink.received.size)
        assertTrue(StaticGlyphs.needs.all { LabelHash.of(it.text, it.sizeClass) in sink.received })
    }

    @Test
    fun `requests run on the given executor and the latest one wins while it is busy`() {
        val worker = Executors.newSingleThreadExecutor()
        try {
            val sink = FakeSink()
            val gate = java.util.concurrent.CountDownLatch(1)
            val entered = java.util.concurrent.CountDownLatch(1)
            val slow = LabelRasteriser { need ->
                if (need.text == "slow") {
                    entered.countDown()
                    gate.await(5, TimeUnit.SECONDS)
                }
                RasterLabel(4, 4, false, ByteBuffer.allocate(4))
            }
            val pump = LabelPump(slow, sink, worker)
            pump.request(listOf(LabelNeed("slow", 0)))
            assertTrue(entered.await(5, TimeUnit.SECONDS)) // the worker is busy with "slow" now
            pump.request(listOf(LabelNeed("first", 0)))
            pump.request(listOf(LabelNeed("last", 0))) // replaces "first" before the worker got to it
            gate.countDown()
            worker.shutdown()
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
            synchronized(sink) {
                assertTrue(LabelHash.of("slow", 0) in sink.received)
                assertTrue(LabelHash.of("last", 0) in sink.received)
                assertFalse(LabelHash.of("first", 0) in sink.received)
            }
        } finally {
            worker.shutdownNow()
        }
    }
}
