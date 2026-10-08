package com.qtekfun.ultimatevideoeditor.engine.verify

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** Random access to the bytes of a file. [read] returns fewer bytes than asked (possibly none) past the end. */
interface ByteSource {
    val size: Long

    fun read(position: Long, length: Int): ByteArray
}

class ArrayByteSource(private val bytes: ByteArray) : ByteSource {
    override val size: Long get() = bytes.size.toLong()

    override fun read(position: Long, length: Int): ByteArray {
        if (position >= bytes.size || position < 0) return ByteArray(0)
        val end = minOf(bytes.size.toLong(), position + length).toInt()
        return bytes.copyOfRange(position.toInt(), end)
    }
}

class ChannelByteSource(private val channel: FileChannel) : ByteSource {
    override val size: Long get() = channel.size()

    override fun read(position: Long, length: Int): ByteArray {
        if (position < 0 || position >= size) return ByteArray(0)
        val buffer = ByteBuffer.allocate(length)
        var at = position
        while (buffer.hasRemaining()) {
            val n = try {
                channel.read(buffer, at)
            } catch (e: IOException) {
                throw IOException("reading the output file at $at failed: ${e.message}", e)
            }
            if (n <= 0) break
            at += n
        }
        return buffer.array().copyOf(buffer.position())
    }
}

/** The file's index (sample tables) is missing or cannot be read. */
class Mp4FormatException(message: String) : Exception(message)

/** The sample table of one track: where every sample is, how big, and when it plays (media timescale ticks). */
class Mp4Track(
    /** "vide" or "soun". */
    val handler: String,
    val timescale: Long,
    /** The track's duration as the sum of its sample durations, in timescale ticks. */
    val durationTicks: Long,
    /** The sample entry's four-character code, e.g. "hvc1", "avc1", "mp4a". */
    val codec: String,
    /** Bytes of the length prefix of each NAL unit in a video sample (avcC / hvcC); 4 when unknown. */
    val nalLengthSize: Int,
    val sizes: IntArray,
    val offsets: LongArray,
    val decodeTicks: LongArray,
    /** Composition offset: presentation = decode + offset. Zero for streams without B-frames. */
    val compositionOffsets: LongArray,
    /** Sample indices of sync samples (key frames); empty when every sample is one. */
    val syncSamples: IntArray,
    /** Media time the track starts at (the first edit-list entry, as muxers write it for streams with B-frames); 0 when none. */
    val editStartTicks: Long = 0,
) {
    val count: Int get() = sizes.size

    fun presentationTicks(i: Int): Long = decodeTicks[i] + compositionOffsets[i] - editStartTicks

    fun presentationUs(i: Int): Long = ticksToUs(presentationTicks(i))

    fun ticksToUs(ticks: Long): Long = Math.floorDiv(ticks * 1_000_000L + timescale / 2, timescale)

    val durationUs: Long get() = ticksToUs(durationTicks)

    fun isSync(i: Int): Boolean = syncSamples.isEmpty() || java.util.Arrays.binarySearch(syncSamples, i) >= 0

    /** Samples in presentation order (indices), for streams whose decode order differs. */
    fun presentationOrder(): IntArray = (0 until count).sortedBy { presentationTicks(it) }.toIntArray()

    /** The last sample (in decode order) whose presentation time is not after [ptsUs] and which is a sync sample. */
    fun syncAtOrBefore(sampleIndex: Int): Int {
        var i = sampleIndex.coerceIn(0, maxOf(0, count - 1))
        while (i > 0 && !isSync(i)) i--
        return i
    }
}

class Mp4File(
    val fileSize: Long,
    val tracks: List<Mp4Track>,
    /** Offset of the `moov` box. */
    val moovOffset: Long,
    /** The file ends inside the `mdat` box (a truncated file whose index was still found, e.g. moov at the start). */
    val mdatTruncated: Boolean,
) {
    val video: Mp4Track? get() = tracks.firstOrNull { it.handler == "vide" }
    val audio: Mp4Track? get() = tracks.firstOrNull { it.handler == "soun" }
}

/** A small reader of the ISO base media sample tables; enough to audit a file this app wrote, no more. */
object Mp4Reader {
    private const val MAX_MOOV_BYTES = 256L * 1024 * 1024

    /** @throws Mp4FormatException when there is no readable `moov` box. */
    fun read(source: ByteSource): Mp4File {
        val fileSize = source.size
        var position = 0L
        var moovAt = -1L
        var moovSize = 0L
        var mdatTruncated = false
        while (position + 8 <= fileSize) {
            val header = source.read(position, 16)
            if (header.size < 8) break
            val buf = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
            var boxSize = buf.int.toLong() and 0xFFFFFFFFL
            val type = String(header, 4, 4, Charsets.ISO_8859_1)
            buf.int
            var headerSize = 8
            if (boxSize == 1L) {
                if (header.size < 16) throw Mp4FormatException("a box header at $position is cut off")
                boxSize = buf.long
                headerSize = 16
            } else if (boxSize == 0L) {
                boxSize = fileSize - position
            }
            if (boxSize < headerSize) throw Mp4FormatException("invalid box '$type' of size $boxSize at $position")
            if (type == "moov") {
                if (position + boxSize > fileSize) throw Mp4FormatException("the index (moov) is cut off: the file ends ${position + boxSize - fileSize} bytes early")
                moovAt = position
                moovSize = boxSize
            } else if (type == "mdat" && position + boxSize > fileSize) {
                mdatTruncated = true
            }
            position += boxSize
        }
        if (moovAt < 0) throw Mp4FormatException("the file has no index (moov): it is cut off or was never finished")
        if (moovSize > MAX_MOOV_BYTES) throw Mp4FormatException("the index (moov) is implausibly large")
        val moov = source.read(moovAt, moovSize.toInt())
        if (moov.size.toLong() != moovSize) throw Mp4FormatException("the index (moov) could not be read completely")
        val tracks = mutableListOf<Mp4Track>()
        val moovBuffer = ByteBuffer.wrap(moov).order(ByteOrder.BIG_ENDIAN)
        forEachBox(moovBuffer, if (moovBuffer.getInt(0) == 1) 16 else 8) { type, body ->
            if (type == "trak") tracks += readTrack(body)
        }
        if (tracks.isEmpty()) throw Mp4FormatException("the index (moov) lists no tracks")
        return Mp4File(fileSize, tracks, moovAt, mdatTruncated)
    }

    /** Calls [block] with each child box of [parent] from [start] to its limit; [body] is a slice of the payload. */
    private fun forEachBox(parent: ByteBuffer, start: Int, block: (String, ByteBuffer) -> Unit) {
        var p = start
        val limit = parent.limit()
        while (p + 8 <= limit) {
            var size = (parent.getInt(p).toLong() and 0xFFFFFFFFL)
            val bytes = ByteArray(4) { parent.get(p + 4 + it) }
            val type = String(bytes, Charsets.ISO_8859_1)
            var header = 8
            if (size == 1L) {
                if (p + 16 > limit) throw Mp4FormatException("a box header inside the index is cut off")
                size = parent.getLong(p + 8)
                header = 16
            } else if (size == 0L) {
                size = (limit - p).toLong()
            }
            if (size < header || p + size > limit) throw Mp4FormatException("box '$type' inside the index is malformed")
            val slice = parent.duplicate().order(ByteOrder.BIG_ENDIAN)
            slice.limit((p + size).toInt()).position(p + header)
            block(type, slice.slice().order(ByteOrder.BIG_ENDIAN))
            p += size.toInt()
        }
    }

    private class Builder {
        var handler = ""
        var timescale = 0L
        var codec = ""
        var nalLength = 4
        var deltas = LongArray(0)
        var compositionOffsets: LongArray? = null
        var sizes = IntArray(0)
        var sync = IntArray(0)
        var chunkOffsets = LongArray(0)
        var runs = LongArray(0) // first_chunk, samples_per_chunk pairs
        var editStart = 0L
    }

    private fun readTrack(trak: ByteBuffer): Mp4Track {
        val b = Builder()
        forEachBox(trak, 0) { type, body ->
            if (type == "edts") {
                forEachBox(body, 0) { t2, b2 ->
                    if (t2 == "elst" && b2.limit() >= 8 && b2.getInt(4) > 0) {
                        // First entry only: segment_duration, then media_time (signed; -1 is an empty edit, which does not shift media).
                        val version = b2.get(0).toInt()
                        val mediaTime = if (version == 1) b2.getLong(8 + 8) else b2.getInt(8 + 4).toLong()
                        if (mediaTime > 0) b.editStart = mediaTime
                    }
                }
            }
            if (type == "mdia") {
                forEachBox(body, 0) { t2, b2 ->
                    when (t2) {
                        "mdhd" -> {
                            val version = b2.get(0).toInt()
                            b.timescale = (b2.getInt(if (version == 1) 20 else 12).toLong()) and 0xFFFFFFFFL
                        }
                        "hdlr" -> b.handler = String(ByteArray(4) { b2.get(8 + it) }, Charsets.ISO_8859_1)
                        "minf" -> forEachBox(b2, 0) { t3, b3 -> if (t3 == "stbl") readStbl(b3, b) }
                    }
                }
            }
        }
        if (b.timescale <= 0) throw Mp4FormatException("a track has no time scale")
        val count = b.sizes.size
        val dts = LongArray(count)
        var t = 0L
        var n = 0
        // deltas holds (count, delta) pairs
        var i = 0
        while (i + 1 < b.deltas.size) {
            val runCount = b.deltas[i]
            val delta = b.deltas[i + 1]
            for (k in 0 until runCount) {
                if (n < count) dts[n] = t
                n++
                t += delta
            }
            i += 2
        }
        if (n < count) throw Mp4FormatException("the time table of a track lists $n samples but it has $count")
        val offsets = sampleOffsets(b, count)
        val comp = LongArray(count)
        b.compositionOffsets?.let { c ->
            var s = 0
            var j = 0
            while (j + 1 < c.size && s < count) {
                for (k in 0 until c[j]) {
                    if (s < count) comp[s] = c[j + 1]
                    s++
                }
                j += 2
            }
        }
        return Mp4Track(b.handler, b.timescale, t, b.codec, b.nalLength, b.sizes, offsets, dts, comp, b.sync, b.editStart)
    }

    private fun sampleOffsets(b: Builder, count: Int): LongArray {
        val offsets = LongArray(count)
        if (count == 0) return offsets
        if (b.chunkOffsets.isEmpty() || b.runs.isEmpty()) throw Mp4FormatException("a track has samples but no chunk table")
        var sample = 0
        val chunks = b.chunkOffsets.size
        var run = 0
        for (chunk in 1..chunks) {
            while (run + 2 < b.runs.size && b.runs[run + 2] <= chunk) run += 2
            val perChunk = b.runs[run + 1].toInt()
            var at = b.chunkOffsets[chunk - 1]
            for (k in 0 until perChunk) {
                if (sample >= count) break
                offsets[sample] = at
                at += b.sizes[sample]
                sample++
            }
        }
        if (sample < count) throw Mp4FormatException("the chunk table of a track places $sample samples but it has $count")
        return offsets
    }

    private fun readStbl(stbl: ByteBuffer, b: Builder) {
        forEachBox(stbl, 0) { type, body ->
            when (type) {
                "stsd" -> readStsd(body, b)
                "stts" -> {
                    val n = body.getInt(4)
                    b.deltas = LongArray(n * 2) { i -> body.getInt(8 + i * 4).toLong() and 0xFFFFFFFFL }
                }
                "ctts" -> {
                    val n = body.getInt(4)
                    b.compositionOffsets = LongArray(n * 2) { i ->
                        val v = body.getInt(8 + i * 4)
                        if (i % 2 == 0) v.toLong() and 0xFFFFFFFFL else v.toLong()
                    }
                }
                "stss" -> {
                    val n = body.getInt(4)
                    b.sync = IntArray(n) { body.getInt(8 + it * 4) - 1 }
                }
                "stsc" -> {
                    val n = body.getInt(4)
                    b.runs = LongArray(n * 2) { i -> body.getInt(8 + (i / 2) * 12 + (i % 2) * 4).toLong() and 0xFFFFFFFFL }
                }
                "stsz" -> {
                    val constant = body.getInt(4)
                    val n = body.getInt(8)
                    if (n < 0) throw Mp4FormatException("the size table of a track is malformed")
                    b.sizes = if (constant != 0) IntArray(n) { constant } else {
                        if (12L + n * 4L > body.limit()) throw Mp4FormatException("the size table of a track is cut off")
                        IntArray(n) { body.getInt(12 + it * 4) }
                    }
                }
                "stco" -> {
                    val n = body.getInt(4)
                    b.chunkOffsets = LongArray(n) { body.getInt(8 + it * 4).toLong() and 0xFFFFFFFFL }
                }
                "co64" -> {
                    val n = body.getInt(4)
                    b.chunkOffsets = LongArray(n) { body.getLong(8 + it * 8) }
                }
            }
        }
    }

    private fun readStsd(stsd: ByteBuffer, b: Builder) {
        if (stsd.limit() < 16) return
        val entrySize = stsd.getInt(8)
        b.codec = String(ByteArray(4) { stsd.get(12 + it) }, Charsets.ISO_8859_1)
        if (b.handler != "vide" || entrySize < 86 + 8) return
        // Visual sample entry: 8 (size, type) + 78 bytes of fields, then child boxes (avcC, hvcC, ...).
        val entry = stsd.duplicate().order(ByteOrder.BIG_ENDIAN)
        entry.limit(minOf(stsd.limit(), 8 + entrySize)).position(8 + 8 + 78)
        val children = entry.slice().order(ByteOrder.BIG_ENDIAN)
        try {
            forEachBox(children, 0) { type, body ->
                when (type) {
                    "avcC" -> if (body.limit() > 4) b.nalLength = (body.get(4).toInt() and 3) + 1
                    "hvcC" -> if (body.limit() > 21) b.nalLength = (body.get(21).toInt() and 3) + 1
                }
            }
        } catch (e: Mp4FormatException) {
            // An odd sample entry: keep the default length size; the sample check then reports what it finds.
        }
    }
}
