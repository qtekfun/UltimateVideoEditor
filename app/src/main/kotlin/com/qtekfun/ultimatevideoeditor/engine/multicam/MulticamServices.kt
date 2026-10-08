package com.qtekfun.ultimatevideoeditor.engine.multicam

import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto
import com.qtekfun.ultimatevideoeditor.domain.beat.PeakEnvelope
import com.qtekfun.ultimatevideoeditor.engine.timeline.PeaksFile
import com.qtekfun.ultimatevideoeditor.engine.timeline.WaveformCache
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The loudness envelope of a library file's audio, for syncing angles; null while its waveform is not extracted. */
fun interface AngleEnvelopeSource {
    suspend fun envelope(assetId: String): PeakEnvelope?
}

/** Reads the envelope from the waveform cache the timeline already fills, so no audio is decoded again. */
class WaveformEnvelopeSource(
    private val cache: WaveformCache,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AngleEnvelopeSource {
    override suspend fun envelope(assetId: String): PeakEnvelope? = withContext(dispatcher) {
        PeaksFile.readWindow(cache.fileFor(assetId), 0L, Long.MAX_VALUE)?.envelope
    }
}

/**
 * What the multicam editor needs from the engine: [envelopes] to sync angles, [hasProxy] to know which angles
 * can be shown from their small proxy copy in the viewer, and [maxDecoders] (the device's hardware decoder
 * limit) to keep the viewer inside its decoder budget.
 */
class MulticamServices(
    val envelopes: AngleEnvelopeSource,
    val hasProxy: (MediaAssetDto) -> Boolean,
    val maxDecoders: Int,
) {
    companion object {
        /** For previews and tests: nothing to sync with, no proxies, a single decoder. */
        val None = MulticamServices(envelopes = { null }, hasProxy = { false }, maxDecoders = 1)
    }
}
