package com.ultimatevideo.uveditor.engine.verify

import android.graphics.ImageFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.FileDescriptor
import java.io.IOException

/**
 * Decodes frames of the output file with the platform's MediaCodec decoder (the class the app decodes its sources with),
 * into ByteBuffers, and signs them. A device decoder that fails or produces nothing is reported in [DecodedRange.error];
 * this class never throws for a damaged file.
 */
class MediaCodecFrameSource private constructor(
    private val extractor: MediaExtractor,
    private val format: MediaFormat,
    private val mime: String,
    private val exp: VerifyExpectation,
    private val ownedExtra: AutoCloseable?,
    /** Frames that are compared with a recorded signature get the fine lattice; the others only a coarse one (flatness). */
    private val detailed: Set<Long>,
) : FrameSource, AutoCloseable {
    private var codec: MediaCodec? = null
    private var signNs = 0L
    private var outputs = 0
    private var signed = 0
    private var decodeCalls = 0
    private val openedNs = System.nanoTime()

    override fun decode(from: Long, to: Long, cancel: () -> Boolean): DecodedRange {
        val frames = ArrayList<DecodedFrame>()
        decodeCalls++
        val rangeBegan = System.nanoTime()
        val outputsBefore = outputs
        val signNsBefore = signNs
        val error = try {
            run(from, to, cancel, frames)
        } catch (e: MediaCodec.CodecException) {
            dropCodec()
            "decoder error: ${e.diagnosticInfo.ifBlank { e.message ?: "unknown" }}"
        } catch (e: IllegalStateException) {
            dropCodec()
            "decoder error: ${e.message}"
        } catch (e: IllegalArgumentException) {
            dropCodec()
            "decoder error: ${e.message}"
        } catch (e: IOException) {
            dropCodec()
            "could not read the file: ${e.message}"
        }
        Log.i(
            TAG,
            "range $from..$to: ${outputs - outputsBefore} frames out, ${(System.nanoTime() - rangeBegan) / 1_000_000} ms (signing ${(signNs - signNsBefore) / 1_000_000} ms)",
        )
        return DecodedRange(from, to, frames, error)
    }

    /** Returns an error text, or null when the range was decoded to its end (or cancelled). */
    private fun run(from: Long, to: Long, cancel: () -> Boolean, out: MutableList<DecodedFrame>): String? {
        val decoder = codec ?: createCodec().also { codec = it }
        // A few frames early: in open-GOP footage (smart export copies the iPhone's) the pictures just before a CRA are decoded after
        // it, so a seek to one of them lands on that CRA and loses them; the frames before [from] are discarded below anyway.
        extractor.seekTo(exp.ptsUsOf(maxOf(0L, from - SEEK_MARGIN_FRAMES)), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        decoder.flush()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var lastProgress = System.nanoTime()
        while (true) {
            if (cancel()) return null
            var progressed = false
            if (!inputDone) {
                val slot = decoder.dequeueInputBuffer(TIMEOUT_US)
                if (slot >= 0) {
                    val buffer = decoder.getInputBuffer(slot) ?: return "decoder gave no input buffer"
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        decoder.queueInputBuffer(slot, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(slot, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                    progressed = true
                }
            }
            val slot = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                slot >= 0 -> {
                    progressed = true
                    outputs++
                    val index = exp.frameIndexOf(info.presentationTimeUs)
                    val end = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (info.size > 0 && index in from..to) {
                        val image = decoder.getOutputImage(slot)
                        if (image != null) {
                            try {
                                val began = System.nanoTime()
                                out += DecodedFrame(index, sign(image, index, info.presentationTimeUs))
                                signNs += System.nanoTime() - began
                                signed++
                            } finally {
                                image.close()
                            }
                        }
                    }
                    decoder.releaseOutputBuffer(slot, false)
                    if (end || index >= to) return null
                }
                slot == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> progressed = true
                else -> Unit
            }
            val now = System.nanoTime()
            if (progressed) lastProgress = now else if (now - lastProgress > STALL_NS) return "the decoder stopped producing frames"
        }
    }

    private fun sign(image: android.media.Image, index: Long, ptsUs: Long): FrameSignature {
        val crop = image.cropRect
        val width = crop.width()
        val height = crop.height()
        val wide = image.format == IMAGE_FORMAT_P010
        require(wide || image.format == ImageFormat.YUV_420_888) { "unsupported decoded format ${image.format}" }
        val planes = image.planes
        fun plane(n: Int, ox: Int, oy: Int) = SamplePlane(planes[n].buffer, planes[n].rowStride, planes[n].pixelStride, wide, ox, oy)
        return FrameSigner.sign(
            index, ptsUs, width, height, if (wide) 10 else 8,
            plane(0, crop.left, crop.top), plane(1, crop.left / 2, crop.top / 2), plane(2, crop.left / 2, crop.top / 2),
            coarse = index !in detailed,
        )
    }

    private fun createCodec(): MediaCodec {
        val decoder = MediaCodec.createDecoderByType(mime)
        val wanted = MediaFormat(format)
        wanted.setInteger(MediaFormat.KEY_COLOR_FORMAT, if (exp.hdr) COLOR_FORMAT_P010 else MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        try {
            decoder.configure(wanted, null, null, 0)
        } catch (e: IllegalArgumentException) {
            // A ten-bit request some decoders refuse: ask for their default output; the signer then sees 8 or 10 bits as it comes.
            Log.w(TAG, "decoder refused the colour format (${e.message}); using its default")
            decoder.reset()
            decoder.configure(format, null, null, 0)
        }
        decoder.start()
        return decoder
    }

    private fun dropCodec() {
        try {
            codec?.release()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "releasing a failed decoder: ${e.message}")
        }
        codec = null
    }

    override fun close() {
        Log.i(TAG, "decoded $outputs frames in $decodeCalls ranges, signed $signed in ${signNs / 1_000_000} ms, total ${(System.nanoTime() - openedNs) / 1_000_000} ms")
        dropCodec()
        extractor.release()
        ownedExtra?.close()
    }

    companion object {
        private const val TAG = "UVVerify"
        private const val SEEK_MARGIN_FRAMES = 8L
        private const val TIMEOUT_US = 5_000L
        private const val STALL_NS = 8_000_000_000L
        private const val COLOR_FORMAT_P010 = 54 // MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010 (API 33)
        private const val IMAGE_FORMAT_P010 = 0x36 // ImageFormat.YCBCR_P010 (API 33)

        /**
         * Opens the video track of [descriptor] for decoding. [extra] is closed with the source (the descriptor's owner).
         * @throws IOException when the file has no decodable video track or no decoder exists for it.
         */
        fun open(descriptor: FileDescriptor, exp: VerifyExpectation, detailed: Set<Long>, extra: AutoCloseable? = null): MediaCodecFrameSource {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(descriptor)
                val track = (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                } ?: throw IOException("the file has no video track the system can read")
                extractor.selectTrack(track)
                val format = extractor.getTrackFormat(track)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: throw IOException("the video track has no type")
                return MediaCodecFrameSource(extractor, format, mime, exp, extra, detailed)
            } catch (e: IOException) {
                extractor.release()
                throw e
            } catch (e: IllegalArgumentException) {
                extractor.release()
                throw IOException("the file cannot be opened: ${e.message}", e)
            }
        }
    }
}
