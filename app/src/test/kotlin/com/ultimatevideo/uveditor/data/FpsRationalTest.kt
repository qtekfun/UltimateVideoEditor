package com.ultimatevideo.uveditor.data

import org.junit.Assert.assertEquals
import org.junit.Test

class FpsRationalTest {
    @Test
    fun `ntsc rates map to exact rationals`() {
        assertEquals(24000 to 1001, FpsRational.fromFloat(23.976))
        assertEquals(30000 to 1001, FpsRational.fromFloat(29.97003))
        assertEquals(60000 to 1001, FpsRational.fromFloat(59.94))
    }

    @Test
    fun `integer rates stay integer`() {
        assertEquals(25 to 1, FpsRational.fromFloat(25.0))
        assertEquals(60 to 1, FpsRational.fromFloat(60.0))
        assertEquals(24 to 1, FpsRational.fromFloat(24.0))
    }

    @Test
    fun `invalid input falls back to the default`() {
        assertEquals(30 to 1, FpsRational.fromFloat(0.0))
        assertEquals(30 to 1, FpsRational.fromFloat(Double.NaN))
    }
}
