package com.ultimatevideo.uveditor.engine.verify

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Writes a minimal but valid MP4 (ftyp, mdat, moov with sample tables) for tests: a video track of [videoFrames] samples
 * (each a valid length-prefixed NAL chain), optionally an AAC-like audio track, `moov` at the end as the Android muxer does.
 */
class Mp4TestBuilder(
    private val videoFrames: Int,
    private val fpsNum: Int = 30,
    private val fpsDen: Int = 1,
    private val audioSamples: Int = 0,
    private val syncEvery: Int = 30,
    private val videoSampleBytes: Int = 400,
    private val firstPtsTicks: Long = 0,
) {
    class Built(val bytes: ByteArray, val videoOffsets: LongArray, val videoSizes: IntArray, val moovOffset: Long)

    fun build(): Built {
        val videoTimescale = fpsNum * 1000L
        val videoDelta = fpsDen * 1000L
        val ftyp = box("ftyp", "isom".toByteArray() + int(0x200) + "isomiso2avc1mp41".toByteArray())
        val mdatPayload = ByteArrayOutputStream()
        val mdatStart = ftyp.size + 8L
        val videoOffsets = LongArray(videoFrames)
        val videoSizes = IntArray(videoFrames) { videoSampleBytes + (it % 7) }
        for (i in 0 until videoFrames) {
            videoOffsets[i] = mdatStart + mdatPayload.size()
            mdatPayload.write(nalSample(videoSizes[i], i))
        }
        val audioOffsets = LongArray(audioSamples)
        val audioSizes = IntArray(audioSamples) { 180 + (it % 5) }
        for (i in 0 until audioSamples) {
            audioOffsets[i] = mdatStart + mdatPayload.size()
            mdatPayload.write(ByteArray(audioSizes[i]) { (1 + (it + i) % 250).toByte() })
        }
        val mdat = box("mdat", mdatPayload.toByteArray())
        val video = trak(
            "vide", videoTimescale, videoDelta, videoOffsets, videoSizes,
            sync = (0 until videoFrames step syncEvery).toList(),
            entry = visualEntry(),
            firstPts = firstPtsTicks,
        )
        val audio = if (audioSamples > 0) trak("soun", 48_000, 1024, audioOffsets, audioSizes, sync = emptyList(), entry = audioEntry(), firstPts = 0) else ByteArray(0)
        val moov = box("moov", video + audio)
        val bytes = ftyp + mdat + moov
        return Built(bytes, videoOffsets, videoSizes, (ftyp.size + mdat.size).toLong())
    }

    private fun nalSample(size: Int, seed: Int): ByteArray {
        val out = ByteArray(size)
        ByteBuffer.wrap(out).putInt(size - 4)
        out[4] = 0x65 // IDR slice: forbidden bit clear
        for (i in 5 until size) out[i] = (1 + (i * 31 + seed) % 250).toByte()
        return out
    }

    private fun visualEntry(): ByteArray {
        val avcC = box("avcC", byteArrayOf(1, 100, 0, 40, 0xFF.toByte(), 0xE0.toByte(), 0))
        val fields = ByteArray(78)
        return box("avc1", fields + avcC)
    }

    private fun audioEntry(): ByteArray = box("mp4a", ByteArray(28))

    private fun trak(
        handler: String,
        timescale: Long,
        delta: Long,
        offsets: LongArray,
        sizes: IntArray,
        sync: List<Int>,
        entry: ByteArray,
        firstPts: Long,
    ): ByteArray {
        val count = sizes.size
        val mdhd = fullBox("mdhd", 0, int(0) + int(0) + int(timescale.toInt()) + int((count * delta).toInt()) + short(0x55c4) + short(0))
        val hdlr = fullBox("hdlr", 0, int(0) + handler.toByteArray() + ByteArray(12) + byteArrayOf(0))
        val stsd = fullBox("stsd", 0, int(1) + entry)
        val stts = fullBox("stts", 0, int(1) + int(count) + int(delta.toInt()))
        val ctts = if (firstPts != 0L) fullBox("ctts", 0, int(1) + int(count) + int(firstPts.toInt())) else ByteArray(0)
        val stsz = fullBox("stsz", 0, int(0) + int(count) + sizes.fold(ByteArray(0)) { acc, s -> acc + int(s) })
        val stsc = fullBox("stsc", 0, int(1) + int(1) + int(1) + int(1))
        val stco = fullBox("stco", 0, int(count) + offsets.fold(ByteArray(0)) { acc, o -> acc + int(o.toInt()) })
        val stss = if (sync.isNotEmpty()) fullBox("stss", 0, int(sync.size) + sync.fold(ByteArray(0)) { acc, s -> acc + int(s + 1) }) else ByteArray(0)
        val stbl = box("stbl", stsd + stts + ctts + stss + stsc + stsz + stco)
        val minf = box("minf", stbl)
        val mdia = box("mdia", mdhd + hdlr + minf)
        return box("trak", mdia)
    }

    private fun box(type: String, payload: ByteArray): ByteArray = int(payload.size + 8) + type.toByteArray(Charsets.ISO_8859_1) + payload

    private fun fullBox(type: String, version: Int, payload: ByteArray): ByteArray = box(type, byteArrayOf(version.toByte(), 0, 0, 0) + payload)

    private fun int(v: Int): ByteArray = ByteBuffer.allocate(4).putInt(v).array()

    private fun short(v: Int): ByteArray = ByteBuffer.allocate(2).putShort(v.toShort()).array()
}
