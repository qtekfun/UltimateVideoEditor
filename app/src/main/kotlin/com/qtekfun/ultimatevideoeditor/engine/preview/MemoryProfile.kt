@file:Suppress("DEPRECATION") // the RUNNING_* trim levels are still sent on the oldest supported Android versions

package com.qtekfun.ultimatevideoeditor.engine.preview

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context

/**
 * How much memory the preview may hold on this device. The decoded-frame cache and the decoders pin graphic
 * memory that the system takes back by closing the app, so a flagship with many other apps open needs a limit as
 * much as a 3 GB phone does. Pure: [MemoryProfiles.of] reads the device, [MemoryProfiles.forRam] does the sums.
 */
data class MemoryProfile(
    /** Budget of the decoded-frame cache. */
    val cacheBudgetBytes: Long,
    /** Upper bound on open preview decoders, on top of what the codecs report. */
    val maxDecoders: Int,
)

enum class Pressure { NONE, MODERATE, SEVERE }

object MemoryProfiles {
    private const val MIB = 1L shl 20
    private const val GIB = 1L shl 30

    /** The profile for a device with [totalRamBytes] of RAM; [lowRam] is the system's own "low RAM device" flag. */
    fun forRam(totalRamBytes: Long, lowRam: Boolean): MemoryProfile = when {
        lowRam || totalRamBytes <= 3 * GIB -> MemoryProfile(96 * MIB, 2)
        totalRamBytes <= 6 * GIB -> MemoryProfile(256 * MIB, 3)
        else -> MemoryProfile(512 * MIB, 4)
    }

    /** How short of memory the device is, from the system's own numbers; [Pressure.NONE] when there is room. */
    fun pressureOf(availBytes: Long, totalBytes: Long, systemLow: Boolean): Pressure = when {
        systemLow || availBytes < totalBytes / 10 -> Pressure.SEVERE
        availBytes < totalBytes * 18 / 100 -> Pressure.MODERATE
        else -> Pressure.NONE
    }

    /** The pressure a `ComponentCallbacks2` trim [level] stands for (newer Android versions stop sending the running-app levels). */
    fun pressureOfTrim(level: Int): Pressure = when {
        level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> Pressure.SEVERE
        level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW || level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> Pressure.MODERATE
        else -> Pressure.NONE
    }

    /** The cache budget to use under [pressure]. */
    fun underPressure(base: MemoryProfile, pressure: Pressure): Long = when (pressure) {
        Pressure.NONE -> base.cacheBudgetBytes
        Pressure.MODERATE -> maxOf(MIN_PRESSURE_CACHE_BYTES, base.cacheBudgetBytes / 2)
        Pressure.SEVERE -> if (base.cacheBudgetBytes <= MIN_PRESSURE_CACHE_BYTES) base.cacheBudgetBytes else maxOf(MIN_PRESSURE_CACHE_BYTES, base.cacheBudgetBytes / 4)
    }

    /** Keeps playing from a handful of frames: 4K frames are 12 MB each. */
    const val MIN_PRESSURE_CACHE_BYTES = 48 * MIB

    /** Reads the device's current free memory; cheap enough to call every few seconds. */
    fun pressureNow(context: Context): Pressure {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return pressureOf(info.availMem, info.totalMem, info.lowMemory)
    }

    fun of(context: Context): MemoryProfile {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return forRam(info.totalMem, am.isLowRamDevice)
    }
}
