package com.ultimatevideo.uveditor.ui.export

import com.ultimatevideo.uveditor.data.interchange.BundleItemKind

/**
 * Turns the writer's byte counts into [BundleProgress] snapshots. The writer reports every 256 KB chunk, far more often than a
 * screen needs, so [bytes] only returns a snapshot at most every [publishEveryMs] (about four a second); a new entry is always
 * returned. The speed is an exponential moving average over samples at least [MIN_SAMPLE_GAP_MS] apart, and no time left is
 * shown until [MIN_ELAPSED_MS] have passed and [MIN_SAMPLES] speeds were folded in, so the first seconds do not flash absurd
 * numbers; when nothing moved for [STALL_MS] it is unknown rather than wrong. Pure and clock-injected so it is tested exactly.
 */
class BundleProgressTracker(
    private val clock: () -> Long,
    private val publishEveryMs: Long = DEFAULT_PUBLISH_MS,
) {
    private var startedAt = 0L
    private var total = 0L
    private var done = 0L
    private var mediaCount = 0
    private var kind = BundleItemKind.PROJECT
    private var itemName = ""
    private var mediaIndex = 0
    private var lastPublishAt = Long.MIN_VALUE
    private var lastSampleAt = 0L
    private var lastSampleDone = 0L
    private var lastMoveAt = 0L
    private var ema: Double? = null // bytes per millisecond
    private var samples = 0

    fun begin(totalBytes: Long, mediaFiles: Int) {
        startedAt = clock()
        total = totalBytes.coerceAtLeast(0)
        mediaCount = mediaFiles
        done = 0
        lastSampleAt = startedAt
        lastMoveAt = startedAt
        lastSampleDone = 0
        lastPublishAt = Long.MIN_VALUE
        ema = null
        samples = 0
    }

    /** A new entry starts; always worth showing. */
    fun item(kind: BundleItemKind, name: String, mediaIndex: Int): BundleProgress {
        this.kind = kind
        this.itemName = name
        this.mediaIndex = mediaIndex
        return publish(clock())
    }

    /** [count] more bytes were written; a snapshot when one is due, null when it is too soon since the last. */
    fun bytes(count: Long): BundleProgress? {
        val now = clock()
        if (count > 0) {
            done += count
            lastMoveAt = now
        }
        sample(now)
        if (lastPublishAt != Long.MIN_VALUE && now - lastPublishAt < publishEveryMs && done < total) return null
        return publish(now)
    }

    /** The state at this moment, whatever the throttle says. */
    fun snapshot(): BundleProgress = build(clock())

    private fun publish(now: Long): BundleProgress {
        lastPublishAt = now
        return build(now)
    }

    private fun sample(now: Long) {
        val gap = now - lastSampleAt
        if (gap < MIN_SAMPLE_GAP_MS) return
        val rate = (done - lastSampleDone).toDouble() / gap
        ema = ema?.let { ALPHA * rate + (1 - ALPHA) * it } ?: rate
        samples++
        lastSampleAt = now
        lastSampleDone = done
    }

    private fun build(now: Long): BundleProgress {
        val elapsed = now - startedAt
        val stalled = done > 0 && now - lastMoveAt > STALL_MS
        val rate = ema?.takeIf { it > 0.0 && samples >= MIN_SAMPLES && elapsed >= MIN_ELAPSED_MS && !stalled }
        val left = rate?.let { if (total > done) ((total - done) / it).toLong().coerceIn(0, MAX_REMAINING_MS) else 0L }
        return BundleProgress(
            kind = kind,
            itemName = itemName,
            mediaIndex = mediaIndex,
            mediaCount = mediaCount,
            doneBytes = done,
            totalBytes = total,
            bytesPerSecond = rate?.let { it * 1000.0 },
            remainingMs = left,
        )
    }

    companion object {
        const val DEFAULT_PUBLISH_MS = 250L
        const val MIN_SAMPLE_GAP_MS = 500L
        const val MIN_SAMPLES = 3
        const val MIN_ELAPSED_MS = 2_000L
        const val STALL_MS = 8_000L
        const val MAX_REMAINING_MS = 24L * 60 * 60 * 1000
        private const val ALPHA = 0.3
    }
}
