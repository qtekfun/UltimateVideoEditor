package com.qtekfun.ultimatevideoeditor.engine.still

import java.util.TreeMap

/**
 * An animated picture read frame by frame (GIF or WebP), with no Android classes so it runs on the JVM in tests.
 * [render] gives the composited canvas after frame `index`.
 */
interface AnimatedPicture {
    val width: Int
    val height: Int
    val frameCount: Int

    /** Delay of every frame as the file states it, in milliseconds. */
    val rawDelaysMs: List<Int>

    /** The composited picture after frame [index] (ARGB, straight alpha); the array is a copy the caller owns. */
    fun render(index: Int): IntArray

    /** Frames drawn since the picture was opened (a replay after a backward seek counts again): what a seek costs. */
    val framesDrawn: Long
}

/**
 * Canvas states kept every [interval] frames while an animation plays forward, so asking for an earlier frame
 * replays from the nearest snapshot at or before it instead of from the start. The interval grows with the canvas
 * size so the snapshots together stay within [budgetBytes].
 */
internal class CanvasSnapshots(frameCount: Int, canvasPixels: Int, budgetBytes: Long = DEFAULT_BUDGET_BYTES) {
    class Snapshot(val canvas: IntArray, val previous: IntArray?)

    val interval: Int = run {
        val perSnapshot = maxOf(1L, canvasPixels.toLong() * BYTES_PER_PIXEL)
        val affordable = maxOf(1L, budgetBytes / perSnapshot)
        // At least MIN_INTERVAL so tiny canvases do not keep a snapshot of every frame.
        maxOf(MIN_INTERVAL.toLong(), (frameCount + affordable - 1) / affordable).toInt()
    }

    private val states = TreeMap<Int, Snapshot>()

    /** Remembers the state after frame [index] if that index falls on the interval and is not stored yet. */
    fun offer(index: Int, canvas: IntArray, previous: IntArray?) {
        if (index % interval == 0 && !states.containsKey(index)) states[index] = Snapshot(canvas.copyOf(), previous?.copyOf())
    }

    /** The newest snapshot at or before frame [index], with its frame number, or null when there is none. */
    fun floor(index: Int): Pair<Int, Snapshot>? = states.floorEntry(index)?.let { it.key to it.value }

    val count: Int get() = states.size

    companion object {
        const val DEFAULT_BUDGET_BYTES = 32L * 1024 * 1024
        const val MIN_INTERVAL = 8
        private const val BYTES_PER_PIXEL = 4
    }
}
