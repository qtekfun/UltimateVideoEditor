package com.qtekfun.ultimatevideoeditor.domain

/** A 3D colour lookup table: [size]^3 RGB entries, red varying fastest, then green, then blue (the .cube order). */
class CubeLut(val size: Int, val title: String?, val data: FloatArray) {
    init {
        require(size in MIN_SIZE..MAX_SIZE) { "LUT size must be $MIN_SIZE..$MAX_SIZE, got $size" }
        require(data.size == size * size * size * 3) { "LUT of size $size needs ${size * size * size * 3} values, got ${data.size}" }
    }

    /** Trilinear lookup of an input colour (each channel 0..1, clamped): the CPU reference of the shader. */
    fun sample(r: Float, g: Float, b: Float): FloatArray {
        val max = (size - 1).toFloat()
        val fr = r.coerceIn(0f, 1f) * max
        val fg = g.coerceIn(0f, 1f) * max
        val fb = b.coerceIn(0f, 1f) * max
        val r0 = minOf(fr.toInt(), size - 2)
        val g0 = minOf(fg.toInt(), size - 2)
        val b0 = minOf(fb.toInt(), size - 2)
        val tr = fr - r0
        val tg = fg - g0
        val tb = fb - b0
        val out = FloatArray(3)
        for (c in 0 until 3) {
            fun at(ri: Int, gi: Int, bi: Int) = data[((bi * size + gi) * size + ri) * 3 + c]
            val c00 = at(r0, g0, b0) * (1 - tr) + at(r0 + 1, g0, b0) * tr
            val c10 = at(r0, g0 + 1, b0) * (1 - tr) + at(r0 + 1, g0 + 1, b0) * tr
            val c01 = at(r0, g0, b0 + 1) * (1 - tr) + at(r0 + 1, g0, b0 + 1) * tr
            val c11 = at(r0, g0 + 1, b0 + 1) * (1 - tr) + at(r0 + 1, g0 + 1, b0 + 1) * tr
            out[c] = (c00 * (1 - tg) + c10 * tg) * (1 - tb) + (c01 * (1 - tg) + c11 * tg) * tb
        }
        return out
    }

    companion object {
        const val MIN_SIZE = 2
        const val MAX_SIZE = 65
    }
}

/** A .cube file that cannot be used, with the line it went wrong on when that is known. */
class LutParseException(message: String, val line: Int? = null) : Exception(if (line != null) "line $line: $message" else message)

/**
 * Parser for the Adobe/Resolve `.cube` 3D LUT format. Supported: `TITLE`, `LUT_3D_SIZE` (2..65, so the
 * usual 17, 33 and 65), the default `DOMAIN_MIN 0 0 0` / `DOMAIN_MAX 1 1 1`, comments and blank lines.
 * 1D LUTs and non-default domains are rejected with a clear message rather than rendered wrongly.
 */
object CubeParser {
    fun parse(text: String): CubeLut {
        var size = 0
        var title: String? = null
        var values = FloatArray(0)
        var count = 0
        var lineNumber = 0
        for (raw in text.lineSequence()) {
            lineNumber++
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            val first = line.substringBefore(' ').substringBefore('\t')
            when {
                first.equals("TITLE", ignoreCase = true) -> title = line.substring(first.length).trim().trim('"')
                first.equals("LUT_3D_SIZE", ignoreCase = true) -> {
                    if (size != 0) throw LutParseException("LUT_3D_SIZE appears twice", lineNumber)
                    size = line.substring(first.length).trim().toIntOrNull()
                        ?: throw LutParseException("LUT_3D_SIZE needs a whole number", lineNumber)
                    if (size !in CubeLut.MIN_SIZE..CubeLut.MAX_SIZE) {
                        throw LutParseException("LUT_3D_SIZE must be between ${CubeLut.MIN_SIZE} and ${CubeLut.MAX_SIZE}, got $size", lineNumber)
                    }
                    values = FloatArray(size * size * size * 3)
                }
                first.equals("LUT_1D_SIZE", ignoreCase = true) -> throw LutParseException("1D LUTs are not supported, use a 3D .cube", lineNumber)
                first.equals("DOMAIN_MIN", ignoreCase = true) -> requireDomain(line, first, 0f, lineNumber)
                first.equals("DOMAIN_MAX", ignoreCase = true) -> requireDomain(line, first, 1f, lineNumber)
                first[0].isLetter() -> throw LutParseException("unknown keyword '$first'", lineNumber)
                else -> {
                    if (size == 0) throw LutParseException("data before LUT_3D_SIZE", lineNumber)
                    val parts = line.split(' ', '\t').filter { it.isNotEmpty() }
                    if (parts.size != 3) throw LutParseException("expected 3 numbers, got ${parts.size}", lineNumber)
                    if (count >= size * size * size) throw LutParseException("more entries than $size^3", lineNumber)
                    for (c in 0 until 3) {
                        val v = parts[c].toFloatOrNull() ?: throw LutParseException("'${parts[c]}' is not a number", lineNumber)
                        if (!v.isFinite()) throw LutParseException("value is not finite", lineNumber)
                        values[count * 3 + c] = v
                    }
                    count++
                }
            }
        }
        if (size == 0) throw LutParseException("no LUT_3D_SIZE found")
        if (count != size * size * size) throw LutParseException("expected ${size * size * size} entries, found $count")
        return CubeLut(size, title, values)
    }

    private fun requireDomain(line: String, keyword: String, expected: Float, lineNumber: Int) {
        val nums = line.substring(keyword.length).trim().split(' ', '\t').filter { it.isNotEmpty() }.map { it.toFloatOrNull() }
        if (nums.size != 3 || nums.any { it == null }) throw LutParseException("$keyword needs three numbers", lineNumber)
        if (nums.any { it != expected }) throw LutParseException("only the default 0..1 input domain is supported", lineNumber)
    }
}

/** The table as the direct, native-order float buffer the preview and export engines take. */
fun CubeLut.toDirectBuffer(): java.nio.ByteBuffer {
    val buffer = java.nio.ByteBuffer.allocateDirect(data.size * Float.SIZE_BYTES).order(java.nio.ByteOrder.nativeOrder())
    buffer.asFloatBuffer().put(data)
    return buffer
}

/** Library keys of the LUTs the clips of this timeline use. */
fun Timeline.lutKeys(): Set<Int> = effectLutKeys(tracks.flatMap { it.clips }.flatMap { it.fx.effects })

/** Library keys of the LUT effects among [effects]. */
fun effectLutKeys(effects: List<Effect>): Set<Int> =
    effects.filter { it.type == EffectType.LUT }.mapNotNull { it.values.firstOrNull()?.toInt() }.toSet()
