package com.qtekfun.ultimatevideoeditor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

/** Clip of [len] frames at [start], reading the source from [srcIn]. */
fun clip(id: String, start: Long, len: Long, srcIn: Long = 0, asset: String? = "a"): Clip =
    Clip(id, asset, FrameIndex(start), FrameIndex(srcIn), FrameIndex(srcIn + len))

fun track(id: String, vararg clips: Clip, type: TrackType = TrackType.VIDEO): Track = Track(id, type, clips.toList())

fun timeline(vararg tracks: Track): Timeline = Timeline(tracks.toList())

fun f(value: Long) = FrameIndex(value)

fun <T> EditResult<T>.getOrFail(): T = when (this) {
    is EditResult.Success -> value
    is EditResult.Failure -> fail("expected success but got $error").let { throw IllegalStateException() }
}

fun EditResult<*>.errorOrFail(): EditError = when (this) {
    is EditResult.Success -> fail("expected failure but got $value").let { throw IllegalStateException() }
    is EditResult.Failure -> error
}

/** Asserts the timeline is valid and returns (id, start, end) triples for the given track. */
fun Timeline.layout(trackId: String): List<Triple<String, Long, Long>> {
    assertTrue("invariants: ${invariantViolations()}", invariantViolations().isEmpty())
    val track = checkNotNull(track(trackId))
    return track.clips.map { Triple(it.id, it.timelineStart.value, it.timelineEnd.value) }
}

fun assertLayout(timeline: Timeline, trackId: String, vararg expected: Triple<String, Long, Long>) {
    assertEquals(expected.toList(), timeline.layout(trackId))
}

fun at(id: String, start: Long, end: Long) = Triple(id, start, end)
