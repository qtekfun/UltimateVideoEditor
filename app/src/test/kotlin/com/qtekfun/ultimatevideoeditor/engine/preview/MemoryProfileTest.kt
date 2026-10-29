@file:Suppress("DEPRECATION")

package com.qtekfun.ultimatevideoeditor.engine.preview

import android.content.ComponentCallbacks2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryProfileTest {
    private val gib = 1L shl 30

    @Test
    fun `a small or flagged low-RAM device gets a small cache and two decoders`() {
        assertEquals(MemoryProfile(96L shl 20, 2), MemoryProfiles.forRam(3 * gib, lowRam = false))
        assertEquals(MemoryProfile(96L shl 20, 2), MemoryProfiles.forRam(12 * gib, lowRam = true))
    }

    @Test
    fun `mid-range and high-end devices step up but never reach the old fixed gigabyte`() {
        assertEquals(MemoryProfile(256L shl 20, 3), MemoryProfiles.forRam(6 * gib, lowRam = false))
        val flagship = MemoryProfiles.forRam(16 * gib, lowRam = false)
        assertEquals(4, flagship.maxDecoders)
        assertTrue(flagship.cacheBudgetBytes < gib)
    }

    @Test
    fun `pressure steps the cache down and never below the floor`() {
        val base = MemoryProfiles.forRam(12 * gib, lowRam = false)
        assertEquals(base.cacheBudgetBytes, MemoryProfiles.underPressure(base, Pressure.NONE))
        assertEquals(base.cacheBudgetBytes / 2, MemoryProfiles.underPressure(base, Pressure.MODERATE))
        assertEquals(base.cacheBudgetBytes / 4, MemoryProfiles.underPressure(base, Pressure.SEVERE))
        val small = MemoryProfiles.forRam(2 * gib, lowRam = true)
        assertEquals(MemoryProfiles.MIN_PRESSURE_CACHE_BYTES, MemoryProfiles.underPressure(small, Pressure.SEVERE))
        assertTrue(MemoryProfiles.underPressure(small, Pressure.MODERATE) >= MemoryProfiles.MIN_PRESSURE_CACHE_BYTES)
    }

    @Test
    fun `free memory below a fifth is moderate and below a tenth or flagged by the system is severe`() {
        val total = 11 * gib
        assertEquals(Pressure.NONE, MemoryProfiles.pressureOf(total / 4, total, systemLow = false))
        assertEquals(Pressure.MODERATE, MemoryProfiles.pressureOf(total * 15 / 100, total, systemLow = false))
        assertEquals(Pressure.SEVERE, MemoryProfiles.pressureOf(total / 20, total, systemLow = false))
        assertEquals(Pressure.SEVERE, MemoryProfiles.pressureOf(total / 2, total, systemLow = true))
    }

    @Test
    fun `trim levels map to pressure`() {
        assertEquals(Pressure.NONE, MemoryProfiles.pressureOfTrim(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN))
        assertEquals(Pressure.MODERATE, MemoryProfiles.pressureOfTrim(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW))
        assertEquals(Pressure.SEVERE, MemoryProfiles.pressureOfTrim(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL))
        assertEquals(Pressure.SEVERE, MemoryProfiles.pressureOfTrim(ComponentCallbacks2.TRIM_MEMORY_COMPLETE))
    }
}
