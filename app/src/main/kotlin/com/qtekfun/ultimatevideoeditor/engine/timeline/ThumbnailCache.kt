package com.qtekfun.ultimatevideoeditor.engine.timeline

import java.io.File

/**
 * Locates `thumbnails/<assetId>/` inside a project directory. The native service keeps one tile file
 * per zoom level in there and rebuilds them when the media changes, so the directory can be deleted
 * at any time without losing anything but time.
 */
class ThumbnailCache(private val projectDir: File) {
    fun dirFor(assetId: String): File {
        require(assetId.isNotBlank()) { "assetId must not be blank" }
        // Asset ids come from project files; never let them escape the cache directory.
        val safe = assetId.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("")
        val dir = File(File(projectDir, "thumbnails"), safe)
        check(dir.isDirectory || dir.mkdirs()) { "cannot create thumbnail cache at $dir" }
        return dir
    }
}
