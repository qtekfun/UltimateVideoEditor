package com.qtekfun.ultimatevideoeditor.domain

/** A position on the timeline or in a source, counted in whole frames. Never seconds or floats. */
@JvmInline
value class FrameIndex(val value: Long) : Comparable<FrameIndex> {
    operator fun plus(frames: Long): FrameIndex = FrameIndex(Math.addExact(value, frames))
    operator fun minus(frames: Long): FrameIndex = FrameIndex(Math.subtractExact(value, frames))

    /** Distance in frames from [other] to this position. */
    operator fun minus(other: FrameIndex): Long = Math.subtractExact(value, other.value)

    override fun compareTo(other: FrameIndex): Int = value.compareTo(other.value)

    override fun toString(): String = "F$value"

    companion object {
        val ZERO = FrameIndex(0)
    }
}
