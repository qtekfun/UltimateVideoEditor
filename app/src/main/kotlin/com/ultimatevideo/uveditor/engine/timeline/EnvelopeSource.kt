package com.ultimatevideo.uveditor.engine.timeline

import com.ultimatevideo.uveditor.domain.beat.PeakEnvelope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Outcome of reading the loudness of a stretch of an asset's audio. */
sealed interface EnvelopeResult {
    /** [envelope] starts at [windowStartMicros] of the asset's own timeline. */
    class Found(val envelope: PeakEnvelope, val windowStartMicros: Long) : EnvelopeResult

    /** The waveform of the asset has not been extracted yet (or could not be). */
    data object NoWaveform : EnvelopeResult
}

/** The loudness envelope of [assetId] between [startMicros] and [endMicros] of its own timeline. */
fun interface EnvelopeSource {
    suspend fun envelope(assetId: String, startMicros: Long, endMicros: Long): EnvelopeResult
}

/** For previews and tests that never analyse anything. */
object NoEnvelopeSource : EnvelopeSource {
    override suspend fun envelope(assetId: String, startMicros: Long, endMicros: Long): EnvelopeResult = EnvelopeResult.NoWaveform
}

/** Reads the envelope from the waveform cache the timeline already fills; nothing is decoded again. */
class WaveformEnvelopeSource(
    private val cache: WaveformCache,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : EnvelopeSource {
    override suspend fun envelope(assetId: String, startMicros: Long, endMicros: Long): EnvelopeResult = withContext(dispatcher) {
        val window = PeaksFile.readWindow(cache.fileFor(assetId), startMicros, endMicros) ?: return@withContext EnvelopeResult.NoWaveform
        EnvelopeResult.Found(window.envelope, window.startMicros)
    }
}
