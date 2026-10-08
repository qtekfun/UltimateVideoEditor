package com.qtekfun.ultimatevideoeditor.ui.export

/** What the export dialog shows besides the bar. Null fields mean "not known yet" (too few samples, or stalled). */
data class ExportEstimate(
    val remainingMs: Long? = null,
    /** Output frames written per second of wall clock. */
    val framesPerSecond: Double? = null,
    /** Seconds of movie produced per second of wall clock (1.0 is real time). */
    val speedFactor: Double? = null,
    /** Progress has not moved for a while; the remaining time is unknown rather than wrong. */
    val stalled: Boolean = false,
)

/**
 * Smooths the export's progress into a time-left estimate. Progress arrives in bursts, so the rate is an
 * exponential moving average over samples at least [MIN_SAMPLE_GAP_MS] apart, and nothing is shown until
 * [MIN_ELAPSED_MS] have passed and at least [MIN_SAMPLES] rates were folded in, which keeps the first seconds
 * from flashing absurd numbers. Pure and clock-injected so it can be tested exactly.
 */
class ExportEstimator(
    private val totalFrames: Long,
    private val movieSeconds: Double,
    private val startedAtMs: Long,
) {
    private var lastSampleMs = startedAtMs
    private var lastSamplePermille = 0
    private var lastMoveMs = startedAtMs
    private var lastPermille = 0
    private var emaPermillePerMs: Double? = null
    private var samples = 0

    /** Records [permille] (0..1000) seen at [nowMs] and returns the estimate for that moment. */
    fun onProgress(permille: Int, nowMs: Long): ExportEstimate {
        val p = permille.coerceIn(0, 1000)
        if (p > lastPermille) {
            lastPermille = p
            lastMoveMs = nowMs
        }
        val gap = nowMs - lastSampleMs
        if (gap >= MIN_SAMPLE_GAP_MS) {
            val rate = (lastPermille - lastSamplePermille).toDouble() / gap
            emaPermillePerMs = emaPermillePerMs?.let { ALPHA * rate + (1 - ALPHA) * it } ?: rate
            samples++
            lastSampleMs = nowMs
            lastSamplePermille = lastPermille
        }
        return estimate(nowMs)
    }

    /** The estimate at [nowMs] without new progress, e.g. from a ticking clock. */
    fun estimate(nowMs: Long): ExportEstimate {
        if (lastPermille >= 1000) return ExportEstimate(remainingMs = 0)
        val elapsed = nowMs - startedAtMs
        if (nowMs - lastMoveMs > STALL_MS && lastPermille > 0) return ExportEstimate(stalled = true)
        val rate = emaPermillePerMs
        if (rate == null || rate <= 0.0 || samples < MIN_SAMPLES || elapsed < MIN_ELAPSED_MS) return ExportEstimate()
        val remaining = ((1000 - lastPermille) / rate).toLong().coerceIn(0, MAX_REMAINING_MS)
        val doneFraction = lastPermille / 1000.0
        val fps = totalFrames * doneFraction * 1000.0 / elapsed
        val speed = movieSeconds * doneFraction * 1000.0 / elapsed
        return ExportEstimate(
            remainingMs = remaining,
            framesPerSecond = fps.takeIf { it.isFinite() },
            speedFactor = speed.takeIf { it.isFinite() },
        )
    }

    companion object {
        const val MIN_SAMPLE_GAP_MS = 500L
        const val MIN_SAMPLES = 3
        const val MIN_ELAPSED_MS = 2_000L
        const val STALL_MS = 8_000L
        const val MAX_REMAINING_MS = 24L * 60 * 60 * 1000
        private const val ALPHA = 0.3
    }
}

/** "1:05", "12 s", "1:02:03": a short duration for the dialog, rounded so it does not flicker. */
fun formatDuration(ms: Long): String {
    val seconds = when {
        ms >= 120_000 -> (ms / 1000 + 2) / 5 * 5 // above two minutes, steps of 5 s
        else -> (ms + 500) / 1000
    }
    val h = seconds / 3600
    val m = seconds % 3600 / 60
    val s = seconds % 60
    return when {
        h > 0 -> "%d:%02d:%02d".format(h, m, s)
        m > 0 -> "%d:%02d".format(m, s)
        else -> "$s s"
    }
}
