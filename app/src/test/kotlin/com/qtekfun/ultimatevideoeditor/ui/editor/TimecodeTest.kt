package com.qtekfun.ultimatevideoeditor.ui.editor

import com.qtekfun.ultimatevideoeditor.domain.FrameRate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimecodeTest {
    @Test
    fun `formats whole seconds and frames`() {
        assertEquals("00:00:01:05", formatTimecode(35, FrameRate(30, 1)))
        assertEquals("00:01:00:00", formatTimecode(1800, FrameRate(30, 1)))
        assertEquals("01:00:00:00", formatTimecode(3600L * 30, FrameRate(30, 1)))
    }

    @Test
    fun `ntsc rates use the nominal frame rate`() {
        assertEquals("00:00:01:00", formatTimecode(30, FrameRate(30000, 1001)))
        assertEquals("00:00:00:59", formatTimecode(59, FrameRate(60000, 1001)))
    }
}

class KeyRegistryTest {
    @Test
    fun `keys are stable and reversible`() {
        val registry = KeyRegistry()

        val a = registry.keyFor("a")
        val b = registry.keyFor("b")

        assertNotEquals(a, b)
        assertEquals(a, registry.keyFor("a"))
        assertEquals("b", registry.idFor(b))
        assertNull(registry.idFor(99))
    }
}
