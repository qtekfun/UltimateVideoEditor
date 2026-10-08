package com.qtekfun.ultimatevideoeditor.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeZonesTest {

    @Test
    fun `the safe rectangle sits inside the canvas`() {
        val canvas = CanvasRect(100f, 50f, 540f, 960f)
        for (platform in SafeZonePlatform.entries) {
            val safe = platform.safeRect(canvas)
            assertTrue(platform.label, safe.x >= canvas.x && safe.y >= canvas.y)
            assertTrue(platform.label, safe.x + safe.width <= canvas.x + canvas.width + 0.001f)
            assertTrue(platform.label, safe.y + safe.height <= canvas.y + canvas.height + 0.001f)
            assertTrue(platform.label, safe.width > 0f && safe.height > 0f)
        }
    }

    @Test
    fun `tiktok covers a quarter of the bottom and a bit of the top`() {
        val safe = SafeZonePlatform.TIKTOK.safeRect(CanvasRect(0f, 0f, 1080f, 1920f))

        assertEquals(1920f * 0.07f, safe.y, 0.01f)
        assertEquals(1920f * (1.0f - 0.07f - 0.23f), safe.height, 0.01f)
        assertEquals(1080f * 0.06f, safe.x, 0.01f)
        assertEquals(1080f * (1.0f - 0.06f - 0.12f), safe.width, 0.01f)
    }

    @Test
    fun `the fractions leave a usable area on every platform`() {
        for (platform in SafeZonePlatform.entries) {
            assertTrue(platform.label, platform.top + platform.bottom < 0.6)
            assertTrue(platform.label, platform.left + platform.right < 0.4)
        }
    }
}
