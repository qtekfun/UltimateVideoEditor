package com.qtekfun.ultimatevideoeditor.engine.timeline

import java.io.File

/** Locates `waveforms/<assetId>.peaks` files; the native service reads and writes them. */
class WaveformCache(private val projectDir: File) {
    fun fileFor(assetId: String): File {
        require(assetId.isNotBlank()) { "assetId must not be blank" }
        // Asset ids come from project files; never let them escape the cache directory.
        val safe = assetId.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("")
        val dir = File(projectDir, "waveforms")
        check(dir.isDirectory || dir.mkdirs()) { "cannot create waveform cache at $dir" }
        return File(dir, "$safe.peaks")
    }
}
