package com.ultimatevideo.uveditor.engine.verify

import kotlin.random.Random

/** Synthetic frames for the comparison tests: a picture that changes with the frame number, like the QA clip. */
object SyntheticSignatures {
    private fun cell(frame: Long, i: Int, plane: Int): Int {
        // Smooth in space, different for every frame: a moving gradient plus a per-frame level, 0.15..0.85 of the range.
        val x = i % SignatureFormat.W
        val y = i / SignatureFormat.W
        val v = 0.5 + 0.28 * Math.sin((x * 0.35) + frame * 0.9 + plane) + 0.2 * Math.cos((y * 0.45) - frame * 0.37 + plane * 2)
        return (v.coerceIn(0.05, 0.95) * SignatureFormat.SCALE).toInt()
    }

    fun frame(index: Long, ptsUs: Long = 0): FrameSignature =
        FrameSignature(index, ptsUs, IntArray(SignatureFormat.CELLS) { cell(index, it, 0) }, IntArray(SignatureFormat.CELLS) { cell(index, it, 1) }, IntArray(SignatureFormat.CELLS) { cell(index, it, 2) })

    /** The same frame as a lossy encoder returns it: every cell off by up to [noise] of the range (plus a small gain error). */
    fun lossy(index: Long, noise: Double = 0.012, seed: Int = 7): FrameSignature {
        val rnd = Random(seed + index.toInt())
        val base = frame(index)
        fun jitter(a: IntArray) = IntArray(a.size) { (a[it] + (rnd.nextDouble(-noise, noise) * SignatureFormat.SCALE).toInt()).coerceIn(0, SignatureFormat.SCALE) }
        return FrameSignature(index, 0, jitter(base.y), jitter(base.cb), jitter(base.cr))
    }

    fun flat(index: Long, luma: Double = 0.0, chroma: Double = 0.5): FrameSignature = FrameSignature(
        index, 0,
        IntArray(SignatureFormat.CELLS) { (luma * SignatureFormat.SCALE).toInt() },
        IntArray(SignatureFormat.CELLS) { (chroma * SignatureFormat.SCALE).toInt() },
        IntArray(SignatureFormat.CELLS) { (chroma * SignatureFormat.SCALE).toInt() },
    )

    /** What the exporter records: the first and the last frame. */
    fun probes(total: Long, @Suppress("UNUSED_PARAMETER") fps: Int = 30): List<FrameSignature> =
        listOf(0L, total - 1).distinct().map { frame(it) }
}
