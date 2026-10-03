package com.ultimatevideo.uveditor.engine.timeline

import com.ultimatevideo.uveditor.domain.beat.BeatDetector
import com.ultimatevideo.uveditor.domain.beat.BeatGrid
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Outcome of looking for beats in a stretch of an asset's audio. */
sealed interface BeatResult {
    /** [grid] times are relative to [windowStartMicros], the start of the stretch that was analysed. */
    class Found(val grid: BeatGrid, val windowStartMicros: Long) : BeatResult

    /** The waveform of the asset has not been extracted yet (or could not be), so there is nothing to analyse. */
    data object NoWaveform : BeatResult

    /** The audio is there but has no clear pulse (speech, ambience, silence) or is too short. */
    data object NoBeat : BeatResult
}

/** Finds the beats of [assetId] between [startMicros] and [endMicros] of its own timeline. */
fun interface BeatSource {
    suspend fun analyze(assetId: String, startMicros: Long, endMicros: Long): BeatResult
}

/** For previews and tests that never analyse anything. */
object NoBeatSource : BeatSource {
    override suspend fun analyze(assetId: String, startMicros: Long, endMicros: Long): BeatResult = BeatResult.NoWaveform
}

/**
 * Reads the loudness envelope from the waveform cache the timeline already fills and runs
 * [BeatDetector] on it, off the main thread. Nothing is decoded again.
 */
class WaveformBeatSource(
    private val cache: WaveformCache,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : BeatSource {
    override suspend fun analyze(assetId: String, startMicros: Long, endMicros: Long): BeatResult = withContext(dispatcher) {
        val window = PeaksFile.readWindow(cache.fileFor(assetId), startMicros, endMicros) ?: return@withContext BeatResult.NoWaveform
        val grid = BeatDetector.analyze(window.envelope) ?: return@withContext BeatResult.NoBeat
        BeatResult.Found(grid, window.startMicros)
    }
}
