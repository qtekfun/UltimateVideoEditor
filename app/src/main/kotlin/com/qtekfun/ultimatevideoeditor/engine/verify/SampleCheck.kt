package com.qtekfun.ultimatevideoeditor.engine.verify

/**
 * Looks at the stored bytes of the first and the last samples without decoding them. A video sample of H.264 / HEVC in
 * an MP4 is a chain of length-prefixed NAL units that must add up to the sample's size exactly; zeroed, overwritten or
 * truncated data does not. Audio samples must not be all zero. Cheap: the tail of a 4K export is a few tens of MB.
 */
object SampleCheck {
    fun checkVideo(source: ByteSource, track: Mp4Track, head: Int, tail: Int): List<Finding> {
        val count = track.count
        if (count == 0) return emptyList()
        val indices = (0 until minOf(head, count)) + (maxOf(0, count - tail) until count)
        var firstBadTail = -1
        var bad = 0
        var firstBad = -1
        var unreadable = 0
        for (i in indices.distinct()) {
            val size = track.sizes[i]
            if (size <= 0 || track.offsets[i] + size > source.size) {
                // Already reported by the container check; remember where it starts for the tail count.
                if (firstBad < 0) firstBad = i
                if (i >= count - tail && firstBadTail < 0) firstBadTail = i
                continue
            }
            val bytes = source.read(track.offsets[i], size)
            if (bytes.size != size) {
                unreadable++
                continue
            }
            if (!wellFormedNalSample(bytes, track.nalLengthSize)) {
                bad++
                if (firstBad < 0) firstBad = i
                if (i >= count - tail && firstBadTail < 0) firstBadTail = i
            }
        }
        val findings = mutableListOf<Finding>()
        if (bad > 0) {
            findings += Finding(
                VerifyCheck.SAMPLE_DATA,
                "$bad of the checked frames hold data that is not valid video (first at frame ${firstBad + 1} of $count)",
                tailFrames = if (firstBadTail >= 0) (count - firstBadTail).toLong() else 0,
            )
        }
        if (unreadable > 0) findings += Finding(VerifyCheck.SAMPLE_DATA, "$unreadable frames could not be read back from the file")
        return findings
    }

    fun checkAudio(source: ByteSource, track: Mp4Track, tail: Int): List<Finding> {
        val count = track.count
        var zero = 0
        for (i in maxOf(0, count - tail) until count) {
            val size = track.sizes[i]
            if (size < MIN_AAC_BYTES || track.offsets[i] + size > source.size) continue
            val bytes = source.read(track.offsets[i], size)
            if (bytes.size == size && bytes.all { it == 0.toByte() }) zero++
        }
        return if (zero > 0) listOf(Finding(VerifyCheck.AUDIO, "$zero of the last audio samples are blank data")) else emptyList()
    }

    /** True when [bytes] is exactly a chain of NAL units, each with a [lengthSize]-byte big-endian length. */
    fun wellFormedNalSample(bytes: ByteArray, lengthSize: Int): Boolean {
        if (lengthSize !in 1..4) return false
        var p = 0
        while (p < bytes.size) {
            if (p + lengthSize > bytes.size) return false
            var length = 0L
            for (k in 0 until lengthSize) length = (length shl 8) or (bytes[p + k].toLong() and 0xFF)
            p += lengthSize
            if (length <= 0 || p + length > bytes.size) return false
            if (bytes[p].toInt() and 0x80 != 0) return false // forbidden_zero_bit
            p += length.toInt()
        }
        return bytes.isNotEmpty()
    }

    private const val MIN_AAC_BYTES = 16
}
