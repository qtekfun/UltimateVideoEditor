package com.ultimatevideo.uveditor.proxy

import com.ultimatevideo.uveditor.data.model.MediaAssetDto

/** What a source file is opened for. Only the first two may use a proxy. */
enum class MediaPurpose {
    /** Showing and scrubbing the picture while editing. */
    PREVIEW,

    /** Timeline thumbnails of video. */
    THUMBNAIL,

    /** Waveforms, beats, loudness, probing: they read the audio or the exact original, never a proxy. */
    ANALYSIS,

    /** Rendering the exported movie: always the original. */
    EXPORT,
}

/** The file to open for an asset. [proxyAssetId] is set when it is a proxy, so a failure can be traced back. */
data class ResolvedSource(val uri: String, val proxyAssetId: String? = null, val originalUri: String = uri) {
    val isProxy: Boolean get() = proxyAssetId != null
}

/**
 * Chooses between an asset's original and its proxy. Pure: the lookups are injected. A proxy is used only
 * while editing (preview and thumbnails), only when the project switch is on, only for decoded video and
 * only when it is READY and its file exists; everything else, export included, gets the original.
 */
class ProxyPlanner(
    private val entryOf: (MediaAssetDto) -> ProxyEntry?,
    private val fileUriOf: (ProxyEntry) -> String?,
) {
    fun resolve(asset: MediaAssetDto, purpose: MediaPurpose, useProxies: Boolean): ResolvedSource {
        val original = ResolvedSource(asset.uri)
        if (purpose == MediaPurpose.EXPORT || purpose == MediaPurpose.ANALYSIS) return original
        if (!useProxies || !asset.canHaveProxy()) return original
        val entry = entryOf(asset) ?: return original
        if (entry.state != ProxyState.READY) return original
        val proxyUri = fileUriOf(entry) ?: return original
        return ResolvedSource(proxyUri, proxyAssetId = asset.id, originalUri = asset.uri)
    }
}
