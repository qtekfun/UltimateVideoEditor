package com.ultimatevideo.uveditor.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyTargetsTest {
    @Test
    fun `4K becomes 720p with the same aspect ratio`() {
        val target = ProxyTargets.plan(SourceInfo(3840, 2160), 30, 1, 720)!!

        assertEquals(1280, target.width)
        assertEquals(720, target.height)
    }

    @Test
    fun `a portrait video keeps its orientation`() {
        val target = ProxyTargets.plan(SourceInfo(2160, 3840), 30, 1, 720)!!

        assertEquals(720, target.width)
        assertEquals(1280, target.height)
    }

    @Test
    fun `the container rotation decides which side is short`() {
        // Stored landscape, displayed portrait (a phone held upright).
        val target = ProxyTargets.plan(SourceInfo(3840, 2160, rotationDegrees = 90), 30, 1, 720)!!

        assertEquals(720, target.width)
        assertEquals(1280, target.height)
    }

    @Test
    fun `1080p can be proxied at 720p`() {
        val target = ProxyTargets.plan(SourceInfo(1920, 1080), 30, 1, 720)!!

        assertEquals(1280, target.width)
        assertEquals(720, target.height)
    }

    @Test
    fun `a source at or below the target is not proxied`() {
        assertNull(ProxyTargets.plan(SourceInfo(1280, 720), 30, 1, 720))
        assertNull(ProxyTargets.plan(SourceInfo(720, 480), 30, 1, 720))
        assertNull(ProxyTargets.plan(SourceInfo(0, 0), 30, 1, 720))
    }

    @Test
    fun `the proxy is never larger than the source and its sides are even`() {
        for (info in listOf(SourceInfo(3996, 2160), SourceInfo(1921, 1081), SourceInfo(4000, 3000), SourceInfo(2561, 1441))) {
            for (short in ProxyTargets.choices) {
                val target = ProxyTargets.plan(info, 25, 1, short) ?: continue
                assertEquals("even width for $info", 0, target.width % 2)
                assertEquals("even height for $info", 0, target.height % 2)
                assertTrue(target.width <= info.displayWidth && target.height <= info.displayHeight)
            }
        }
    }

    @Test
    fun `4000 by 3000 at 720 is 960 by 720`() {
        val target = ProxyTargets.plan(SourceInfo(4000, 3000), 30, 1, 720)!!

        assertEquals(960, target.width)
        assertEquals(720, target.height)
    }

    @Test
    fun `bitrate follows size and rate within sane limits`() {
        val normal = ProxyTargets.plan(SourceInfo(3840, 2160), 30, 1, 720)!!
        assertEquals(4_147_200, normal.bitrate)

        val huge = ProxyTargets.plan(SourceInfo(7680, 4320), 240, 1, 1080)!!
        assertEquals(40_000_000, huge.bitrate)

        val tiny = ProxyTargets.plan(SourceInfo(1920, 1080), 1, 10, 720)!!
        assertEquals(2_000_000, tiny.bitrate)
    }

    @Test
    fun `fractional frame rates are handled`() {
        assertNotNull(ProxyTargets.plan(SourceInfo(3840, 2160), 60000, 1001, 720))
    }
}
