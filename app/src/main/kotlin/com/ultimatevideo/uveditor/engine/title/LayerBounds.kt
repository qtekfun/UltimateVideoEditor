package com.ultimatevideo.uveditor.engine.title

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/**
 * Where a drawn layer lies relative to the canvas centre, in pixels: its [centerX]/[centerY], its
 * [width] x [height] after scaling, its clockwise [rotationDegrees], and [reach], the extra margin a
 * shadow or an outline adds around it.
 */
data class LayerFootprint(
    val centerX: Double,
    val centerY: Double,
    val width: Double,
    val height: Double,
    val rotationDegrees: Double = 0.0,
    val reach: Double = 0.0,
)

/**
 * Sizing of a multilayer title's bitmap. The compositor draws a title bitmap centred on the canvas, so
 * the bitmap must be symmetric around the canvas centre: it is as wide as twice the farthest any
 * layer reaches horizontally, and likewise vertically. That keeps it as small as the content allows
 * (a lower third is a thin strip, not a full frame) without moving anything. Pure maths, unit-tested.
 */
object LayerBounds {

    /** Half the width and height of the axis-aligned box around a [width] x [height] rectangle turned by [degrees]. */
    fun rotatedHalfExtent(width: Double, height: Double, degrees: Double): Pair<Double, Double> {
        val radians = Math.toRadians(degrees)
        val c = abs(cos(radians))
        val s = abs(sin(radians))
        return (width * c + height * s) / 2.0 to (width * s + height * c) / 2.0
    }

    /**
     * Half extents, in whole pixels, of the symmetric bitmap that holds every footprint, capped at
     * [maxHalfX] and [maxHalfY] (a layer pushed far off the canvas is cut at the cap). Never below 1,
     * so an empty title still has a valid, empty bitmap.
     */
    fun halfExtents(footprints: List<LayerFootprint>, maxHalfX: Int, maxHalfY: Int): Pair<Int, Int> {
        require(maxHalfX >= 1 && maxHalfY >= 1) { "caps must be positive" }
        var reachX = 0.0
        var reachY = 0.0
        for (f in footprints) {
            val (hx, hy) = rotatedHalfExtent(f.width, f.height, f.rotationDegrees)
            reachX = maxOf(reachX, abs(f.centerX) + hx + f.reach)
            reachY = maxOf(reachY, abs(f.centerY) + hy + f.reach)
        }
        return ceil(reachX).toInt().coerceIn(1, maxHalfX) to ceil(reachY).toInt().coerceIn(1, maxHalfY)
    }
}
