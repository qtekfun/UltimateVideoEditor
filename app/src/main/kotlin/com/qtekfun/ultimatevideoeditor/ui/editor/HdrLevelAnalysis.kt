package com.qtekfun.ultimatevideoeditor.ui.editor

import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto
import com.qtekfun.ultimatevideoeditor.domain.HdrLevels
import com.qtekfun.ultimatevideoeditor.domain.SourceColorSpace
import java.io.File

/**
 * Measures how bright the diffuse content of an HDR file is (see `domain/HdrLevel`). Implemented on top of the platform decoder by
 * `engine/hdr/MediaCodecHdrLevelAnalyzer`, and by fakes in the tests.
 */
interface HdrLevelAnalyzer {
    /**
     * The diffuse level of [asset] read as [space], relative to 203 nit, or null when it cannot be measured (an unreadable file, a
     * decoder that fails). Reads the original file, never a proxy. Cancelling the caller stops it.
     */
    suspend fun diffuseLevel(asset: MediaAssetDto, space: SourceColorSpace): Double?

    companion object {
        /** An analyser that measures nothing: every clip is shown as recorded. */
        val None: HdrLevelAnalyzer = object : HdrLevelAnalyzer {
            override suspend fun diffuseLevel(asset: MediaAssetDto, space: SourceColorSpace): Double? = null
        }
    }
}

/** Diffuse levels kept per file and colour space, so a file is analysed once and the result survives restarts and other projects. */
interface HdrLevelCache {
    fun get(key: String): Double?

    fun put(key: String, level: Double)

    companion object {
        /** A cache that remembers nothing. */
        val None: HdrLevelCache = object : HdrLevelCache {
            override fun get(key: String): Double? = null

            override fun put(key: String, level: Double) = Unit
        }

        /** The key of [asset] read as [space]: the file, its length (a replaced file changes it) and how it is read. */
        fun keyOf(asset: MediaAssetDto, space: SourceColorSpace): String = HdrLevels.keyOf(asset.uri, asset.durationFrames, space)
    }
}

/** The cache the app uses: one file under [filesDir] shared by every project (the key names the file, not the project). */
fun hdrLevelCacheIn(filesDir: File): HdrLevelCache = FileHdrLevelCache(File(filesDir, HDR_LEVEL_CACHE_PATH))

internal const val HDR_LEVEL_CACHE_PATH = "hdr/levels.json"

/** [HdrLevelCache] on disk; a damaged file reads as empty (the levels are measured again). */
class FileHdrLevelCache(file: File) : HdrLevelCache {
    private val values = FileDoubleMap(file)

    override fun get(key: String): Double? = values.get(key)

    override fun put(key: String, level: Double) = values.put(key, level)
}
