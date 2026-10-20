package com.qtekfun.ultimatevideoeditor.engine.hdr

import com.qtekfun.ultimatevideoeditor.domain.HdrLevel
import com.qtekfun.ultimatevideoeditor.domain.SourceColorSpace
import com.qtekfun.ultimatevideoeditor.engine.verify.FrameSignature
import com.qtekfun.ultimatevideoeditor.engine.verify.FrameSource
import com.qtekfun.ultimatevideoeditor.engine.verify.SignatureFormat

/** Reading frames of an HDR file to find its diffuse level; the maths is [HdrLevel]. Pure over a [FrameSource], so tests use a fake. */
object HdrLevelMeasure {
    /** At least this many frames must be read for the level to mean anything. */
    private const val MIN_FRAMES = 3

    /** [count] frame numbers spread over a file of [totalFrames], each in the middle of its share (never the first or last frame). */
    fun sampleFrames(totalFrames: Long, count: Int = HdrLevel.SAMPLE_FRAMES): List<Long> {
        if (totalFrames <= 0 || count <= 0) return emptyList()
        return (0 until count).map { (2L * it + 1) * totalFrames / (2L * count) }.map { it.coerceIn(0L, totalFrames - 1) }.distinct()
    }

    /** The diffuse level (relative to 203 nit) of the file behind [source] read as [space], or null if too few frames could be read. */
    fun diffuseLevel(source: FrameSource, totalFrames: Long, space: SourceColorSpace, cancel: () -> Boolean = { false }): Double? {
        if (space == SourceColorSpace.SDR) return null
        val signatures = ArrayList<FrameSignature>()
        for (frame in sampleFrames(totalFrames)) {
            if (cancel()) return null
            // Two frames of slack: a variable-rate file may have no frame exactly on the number asked for.
            val range = source.decode(frame, frame + 2, cancel)
            range.frames.firstOrNull()?.let { signatures += it.signature }
        }
        return levelOf(signatures, space)
    }

    /** The level of already-read signatures: every cell of every frame pooled, see [HdrLevel.diffuseLevel]. */
    fun levelOf(signatures: List<FrameSignature>, space: SourceColorSpace): Double? {
        if (signatures.size < MIN_FRAMES) return null
        val cells = DoubleArray(signatures.size * SignatureFormat.CELLS)
        signatures.forEachIndexed { n, s ->
            HdrLevel.cellNits(s.y, s.cb, s.cr, SignatureFormat.SCALE, space).copyInto(cells, n * SignatureFormat.CELLS)
        }
        return HdrLevel.diffuseLevel(cells)
    }
}
