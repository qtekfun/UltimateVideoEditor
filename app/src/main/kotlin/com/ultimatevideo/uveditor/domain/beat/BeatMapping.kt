package com.ultimatevideo.uveditor.domain.beat

import com.ultimatevideo.uveditor.domain.Clip
import com.ultimatevideo.uveditor.domain.FrameIndex
import com.ultimatevideo.uveditor.domain.FrameRate
import com.ultimatevideo.uveditor.domain.Marker
import com.ultimatevideo.uveditor.domain.MarkerKind
import com.ultimatevideo.uveditor.domain.isFreeze
import com.ultimatevideo.uveditor.domain.retime
import kotlin.math.abs

/**
 * Places beats found in a source onto the timeline for one clip, in whole project frames.
 * A clip's source range is expressed in project frames, so a beat at source time T sits at source
 * frame round(T * fps); the clip's own speed, reverse and trim then decide where (and whether) it lands.
 */
object BeatMapping {

    /**
     * Beat markers inside [clip], one per beat that falls in its source range. Beats are matched to
     * the nearest clip frame, so on a sped-up clip several beats may share a frame and only one is kept.
     * Clips without audible media (titles, photos, stickers, freeze frames) get none.
     */
    fun markersFor(clip: Clip, beatsMicros: List<Long>, fps: FrameRate, idFor: (index: Int) -> String): List<Marker> {
        if (!clip.hasMedia || clip.assetId == null || clip.isFreeze) return emptyList()
        val duration = clip.durationFrames
        if (duration <= 0) return emptyList()
        val retime = clip.retime
        val source = LongArray(duration.toInt()) { retime.sourceFrameAt(it.toLong()) }
        val ascending = source.first() <= source.last()
        val lowest = minOf(source.first(), source.last())
        val highest = maxOf(source.first(), source.last())
        val halfFrameMicros = 500_000L * fps.den / fps.num
        val result = mutableListOf<Marker>()
        val seen = HashSet<Long>()
        for ((index, micros) in beatsMicros.withIndex()) {
            val wanted = fps.microsToFrames(micros + halfFrameMicros)
            if (wanted < lowest || wanted > highest) continue
            val at = closest(source, wanted, ascending)
            val frame = clip.timelineStart.value + at
            if (seen.add(frame)) result += Marker(idFor(index), FrameIndex(frame), MarkerKind.BEAT)
        }
        return result
    }

    /** Index of the entry of the monotonic [source] closest to [wanted], the earliest on a tie. */
    private fun closest(source: LongArray, wanted: Long, ascending: Boolean): Int {
        var low = 0
        var high = source.size - 1
        while (low < high) {
            val mid = (low + high) ushr 1
            val reached = if (ascending) source[mid] >= wanted else source[mid] <= wanted
            if (reached) high = mid else low = mid + 1
        }
        val before = (low - 1).coerceAtLeast(0)
        return if (abs(source[before] - wanted) < abs(source[low] - wanted)) before else low
    }
}
