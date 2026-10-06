package com.ultimatevideo.uveditor.data

/** Random access to the bytes of a file; [read] returns null when that range cannot be read in full. */
internal interface ByteSource {
    val size: Long
    fun read(offset: Long, length: Int): ByteArray?
}

/** An uncompressed soundtrack found in a QuickTime/MP4 file. */
internal data class PcmSoundTrack(val durationMicros: Long)

/**
 * Finds a linear-PCM sound track in a QuickTime/MP4 file.
 *
 * Why: Android's `MediaExtractor` does not list uncompressed audio in a `.mov` (iPhone footage with an 'lpcm' soundtrack opens
 * as video only), so the probe would call such a file silent and the editor and exporter would leave its audio out. The
 * native engine reads these tracks itself (`audio/mov_pcm.h`); this scan only tells the probe that the track exists. It keeps to
 * the box structure (moov, trak, mdia, hdlr 'soun', stsd entry type), so it reads the header boxes and nothing of the samples.
 */
internal object MovAudioScan {
    private val pcmTypes = setOf("lpcm", "sowt", "twos", "raw ", "in24", "in32", "fl32", "fl64")
    private const val MAX_MOOV_BYTES = 256L * 1024 * 1024

    fun find(source: ByteSource): PcmSoundTrack? {
        val moov = readMoov(source) ?: return null
        var found: PcmSoundTrack? = null
        children(moov, 0, moov.size) { type, start, end ->
            if (type != "trak" || found != null) return@children
            found = soundTrack(moov, start, end)
        }
        return found
    }

    private fun readMoov(source: ByteSource): ByteArray? {
        var pos = 0L
        while (pos + 8 <= source.size) {
            val header = source.read(pos, 8) ?: return null
            var size = u32(header, 0)
            val type = fourcc(header, 4)
            var headerSize = 8L
            if (size == 1L) {
                val big = source.read(pos + 8, 8) ?: return null
                size = u64(big, 0)
                headerSize = 16
            } else if (size == 0L) {
                size = source.size - pos
            }
            if (size < headerSize) return null
            if (type == "moov") {
                val payload = minOf(size, source.size - pos) - headerSize
                if (payload <= 0 || payload > MAX_MOOV_BYTES) return null
                return source.read(pos + headerSize, payload.toInt())
            }
            pos += size
        }
        return null
    }

    private fun soundTrack(b: ByteArray, start: Int, end: Int): PcmSoundTrack? {
        val mdia = child(b, start, end, "mdia") ?: return null
        val hdlr = child(b, mdia.first, mdia.second, "hdlr") ?: return null
        // version/flags, pre-defined, then the handler type.
        if (hdlr.second - hdlr.first < 12 || fourcc(b, hdlr.first + 8) != "soun") return null
        val minf = child(b, mdia.first, mdia.second, "minf") ?: return null
        val stbl = child(b, minf.first, minf.second, "stbl") ?: return null
        val stsd = child(b, stbl.first, stbl.second, "stsd") ?: return null
        // version/flags, entry count, then the first entry: size (4), type (4).
        if (stsd.second - stsd.first < 16 || u32(b, stsd.first + 4) < 1) return null
        if (fourcc(b, stsd.first + 12) !in pcmTypes) return null
        return PcmSoundTrack(durationMicros(b, mdia))
    }

    private fun durationMicros(b: ByteArray, mdia: Pair<Int, Int>): Long {
        val mdhd = child(b, mdia.first, mdia.second, "mdhd") ?: return 0
        val version = b[mdhd.first].toInt()
        val (scale, duration) = if (version == 1) {
            if (mdhd.second - mdhd.first < 32) return 0
            u32(b, mdhd.first + 20) to u64(b, mdhd.first + 24)
        } else {
            if (mdhd.second - mdhd.first < 20) return 0
            u32(b, mdhd.first + 12) to u32(b, mdhd.first + 16)
        }
        return if (scale <= 0L) 0 else duration * 1_000_000L / scale
    }

    private fun child(b: ByteArray, start: Int, end: Int, wanted: String): Pair<Int, Int>? {
        var result: Pair<Int, Int>? = null
        children(b, start, end) { type, s, e -> if (result == null && type == wanted) result = s to e }
        return result
    }

    private inline fun children(b: ByteArray, start: Int, end: Int, visit: (type: String, payloadStart: Int, payloadEnd: Int) -> Unit) {
        var pos = start
        while (end - pos >= 8) {
            var size = u32(b, pos)
            val type = fourcc(b, pos + 4)
            var header = 8
            if (size == 1L) {
                if (end - pos < 16) return
                size = u64(b, pos + 8)
                header = 16
            } else if (size == 0L) {
                size = (end - pos).toLong()
            }
            if (size < header || size > end - pos) return
            visit(type, pos + header, pos + size.toInt())
            pos += size.toInt()
        }
    }

    private fun u32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)

    private fun u64(b: ByteArray, at: Int): Long = (u32(b, at) shl 32) or u32(b, at + 4)

    private fun fourcc(b: ByteArray, at: Int): String = String(CharArray(4) { (b[at + it].toInt() and 0xFF).toChar() })
}
