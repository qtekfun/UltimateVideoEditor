package com.ultimatevideo.uveditor.ui.export

import com.ultimatevideo.uveditor.data.interchange.BundleItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BundleProgressTrackerTest {
    private var now = 10_000L
    private val tracker = BundleProgressTracker({ now })
    private val mib = 1024L * 1024

    @Test
    fun `percent is bytes done over the total up front`() {
        tracker.begin(1000, 2)
        tracker.item(BundleItemKind.MEDIA, "a.mov", 1)
        now += 300
        val p = tracker.bytes(250)

        assertEquals(25, p?.percent)
        assertEquals(1000L, p?.totalBytes)
        assertEquals(2, p?.mediaCount)
        assertEquals("a.mov", p?.itemName)
    }

    @Test
    fun `an empty total is 0 percent, not a division by zero`() {
        tracker.begin(0, 0)

        assertEquals(0, tracker.snapshot().percent)
    }

    @Test
    fun `done beyond the total never shows over 100`() {
        tracker.begin(100, 1)
        now += 300
        val p = tracker.bytes(500)

        assertEquals(100, p?.percent)
    }

    @Test
    fun `snapshots are throttled to about four a second but a new entry always shows`() {
        tracker.begin(100 * mib, 3)
        tracker.item(BundleItemKind.MEDIA, "a", 1)
        var published = 0
        repeat(100) { // 100 chunks in one second: one every 10 ms
            now += 10
            if (tracker.bytes(256 * 1024) != null) published++
        }

        assertTrue("published $published", published in 3..5)
        assertNotNull(tracker.item(BundleItemKind.MEDIA, "b", 2))
    }

    @Test
    fun `the last bytes are published even inside the throttle window`() {
        tracker.begin(1000, 1)
        now += 300
        tracker.bytes(500)
        now += 10

        assertEquals(100, tracker.bytes(500)?.percent)
    }

    @Test
    fun `no time left in the first seconds, then a steady estimate`() {
        tracker.begin(100 * mib, 1)
        tracker.item(BundleItemKind.MEDIA, "a", 1)
        // 10 MiB per second.
        repeat(4) {
            now += 250
            tracker.bytes(2_621_440) // 2.5 MiB
        }
        assertNull("after 1 s nothing is promised yet", tracker.snapshot().remainingMs)
        repeat(24) {
            now += 250
            tracker.bytes(2_621_440)
        }
        val p = tracker.snapshot()

        // 7 s in at 10 MiB/s: 70 MiB done, 30 MiB left, about 3 s.
        val left = p.remainingMs
        assertNotNull(left)
        assertTrue("left $left", left!! in 2_500..3_500)
        val rate = p.bytesPerSecond
        assertTrue("rate $rate", rate != null && rate > 9.0 * mib && rate < 11.0 * mib)
    }

    @Test
    fun `the estimate follows a change of speed smoothly`() {
        tracker.begin(1000 * mib, 1)
        repeat(20) {
            now += 500
            tracker.bytes(5 * mib) // 10 MiB/s
        }
        val fast = tracker.snapshot().bytesPerSecond ?: 0.0
        repeat(4) {
            now += 500
            tracker.bytes(1 * mib) // 2 MiB/s
        }
        val after = tracker.snapshot().bytesPerSecond ?: 0.0

        assertTrue("slowed down but not at once: $fast -> $after", after < fast && after > 2.0 * mib)
    }

    @Test
    fun `a stall makes the time left unknown rather than wrong`() {
        tracker.begin(100 * mib, 1)
        repeat(10) {
            now += 500
            tracker.bytes(mib)
        }
        assertNotNull(tracker.snapshot().remainingMs)

        now += BundleProgressTracker.STALL_MS + 1

        assertNull(tracker.snapshot().remainingMs)
        assertNull(tracker.snapshot().bytesPerSecond)
    }

    @Test
    fun `finished bytes leave nothing to wait for`() {
        tracker.begin(10 * mib, 1)
        repeat(10) {
            now += 500
            tracker.bytes(mib)
        }

        assertEquals(0L, tracker.snapshot().remainingMs)
        assertEquals(100, tracker.snapshot().percent)
    }
}
