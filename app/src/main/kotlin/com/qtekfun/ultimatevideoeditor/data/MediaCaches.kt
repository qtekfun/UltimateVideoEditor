package com.qtekfun.ultimatevideoeditor.data

import java.io.File

/** Derived data kept per library file (waveform peaks, thumbnail tiles) that must go when the file is replaced. */
interface MediaCaches {
    /** Forgets everything derived from the file behind [assetId]. Never throws for a missing cache. */
    fun invalidate(assetId: String)

    object None : MediaCaches {
        override fun invalidate(assetId: String) = Unit
    }
}

/**
 * Deletes `waveforms/<assetId>.peaks` and `thumbnails/<assetId>/` of a project directory. The thumbnail
 * tiles are also tied to the media size, but the waveform file is not, so a replacement of the same
 * length would otherwise keep drawing the old file's waveform.
 */
class ProjectDirMediaCaches(private val projectDir: File) : MediaCaches {
    override fun invalidate(assetId: String) {
        val safe = safeName(assetId)
        File(File(projectDir, "waveforms"), "$safe.peaks").delete()
        File(File(projectDir, "thumbnails"), safe).deleteRecursively()
    }

    // Must match WaveformCache and ThumbnailCache, which never let an id leave their directory.
    private fun safeName(assetId: String): String =
        assetId.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("")
}
