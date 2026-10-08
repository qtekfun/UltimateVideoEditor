package com.qtekfun.ultimatevideoeditor.data.interchange

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SmpteTimecodeTest {
    @Test
    fun `non drop frame counts the nominal rate`() {
        val tc = SmpteTimecode(25, 1)
        assertEquals("00:00:00:00", tc.format(0))
        assertEquals("00:00:01:00", tc.format(25))
        assertEquals("00:00:00:24", tc.format(24))
        assertEquals("01:00:00:00", tc.format(25L * 3600))
    }

    @Test
    fun `23_976 is written at 24 fps non drop`() {
        val tc = SmpteTimecode(24000, 1001)
        assertEquals(24, tc.nominal)
        assertFalse(tc.dropFrame)
        assertEquals("00:00:01:00", tc.format(24))
    }

    @Test
    fun `29_97 drop frame skips frame numbers at each minute except every tenth`() {
        val tc = SmpteTimecode(30000, 1001, dropFrame = true)
        assertTrue(tc.dropFrame)
        assertEquals("00:00:59;29", tc.format(1799))
        assertEquals("00:01:00;02", tc.format(1800))
        assertEquals("00:01:59;29", tc.format(3597))
        assertEquals("00:02:00;02", tc.format(3598))
        // The tenth minute keeps its frame numbers.
        assertEquals("00:09:59;29", tc.format(17981))
        assertEquals("00:10:00;00", tc.format(17982))
        assertEquals("01:00:00;00", tc.format(107892))
    }

    @Test
    fun `59_94 drop frame skips four numbers`() {
        val tc = SmpteTimecode(60000, 1001, dropFrame = true)
        assertEquals("00:00:59;59", tc.format(3599))
        assertEquals("00:01:00;04", tc.format(3600))
        assertEquals("00:10:00;00", tc.format(35964))
    }

    @Test
    fun `drop frame is ignored for other rates`() {
        assertFalse(SmpteTimecode(30, 1, dropFrame = true).dropFrame)
        assertFalse(SmpteTimecode(24000, 1001, dropFrame = true).dropFrame)
        assertTrue(SmpteTimecode.isNtsc(30000, 1001))
        assertFalse(SmpteTimecode.isNtsc(24000, 1001))
    }

    @Test
    fun `a negative frame is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { SmpteTimecode(30, 1).format(-1) }
    }
}
