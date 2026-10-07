package com.ultimatevideo.uveditor.engine.verify

import kotlin.math.abs

/**
 * File-level checks that need no decoder: the index (sample tables) says what the exporter promised, and every sample
 * it lists lies inside the file. Calibrated on real exports (DECISIONS.md "Post-export verification").
 */
object ContainerCheck {
    /** One AAC frame (1024 samples at 48 kHz). */
    const val AAC_FRAME_US = 21_334L

    /**
     * The audio track may be this much shorter than the picture, and this much longer. Measured on real exports: the mix is
     * shifted earlier by the encoder delay and padded to whole frames, so the track ends up to ~2.5 AAC frames longer than
     * the picture (v034.mp4: +51 ms); a track more than one frame shorter than the picture is missing its end.
     */
    const val AUDIO_SHORTER_TOLERANCE_US = AAC_FRAME_US
    const val AUDIO_LONGER_TOLERANCE_US = 4 * AAC_FRAME_US

    fun check(file: Mp4File, exp: VerifyExpectation): List<Finding> {
        val findings = mutableListOf<Finding>()
        val video = file.video
        if (video == null) {
            findings += Finding(VerifyCheck.CONTAINER, "the file has no video track")
            return findings
        }
        if (file.mdatTruncated) findings += Finding(VerifyCheck.CONTAINER, "the file ends in the middle of its media data")
        checkVideo(file, video, exp, findings)
        if (exp.hasAudio) checkAudio(file, exp, findings)
        return findings
    }

    private fun checkVideo(file: Mp4File, video: Mp4Track, exp: VerifyExpectation, out: MutableList<Finding>) {
        val frameUs = exp.frameDurationUs
        val count = video.count
        if (count.toLong() != exp.totalFrames) {
            val missing = exp.totalFrames - count
            out += Finding(
                VerifyCheck.FRAME_COUNT,
                "the file holds $count frames, the export should have ${exp.totalFrames}",
                tailFrames = if (missing > 0) missing else 0,
            )
        }
        val empty = video.sizes.count { it <= 0 }
        if (empty > 0) out += Finding(VerifyCheck.SAMPLE_DATA, "$empty frames are empty")
        val outside = (0 until count).filter { video.offsets[it] + video.sizes[it] > file.fileSize }
        if (outside.isNotEmpty()) {
            val lastBytes = outside.maxOf { video.offsets[it] + video.sizes[it] } - file.fileSize
            out += Finding(
                VerifyCheck.SAMPLE_DATA,
                "${outside.size} frames lie beyond the end of the file ($lastBytes bytes missing)",
                tailFrames = framesFromEnd(video, outside.min()),
            )
        }
        if (count == 0) return
        val order = video.presentationOrder()
        val firstUs = video.presentationUs(order.first())
        val lastUs = video.presentationUs(order.last())
        if (abs(firstUs) > frameUs) out += Finding(VerifyCheck.TIMING, "the first frame is at ${firstUs / 1000} ms, not at the start")
        val expectedLast = exp.ptsUsOf(exp.totalFrames - 1)
        if (abs(lastUs - expectedLast) > frameUs) {
            out += Finding(VerifyCheck.TIMING, "the last frame is at ${lastUs / 1000} ms, expected ${expectedLast / 1000} ms")
        }
        var worstGap = 0.0
        var duplicate = 0
        for (k in 1 until order.size) {
            val gap = (video.presentationUs(order[k]) - video.presentationUs(order[k - 1])).toDouble()
            if (gap <= 0.0) duplicate++
            if (gap > worstGap) worstGap = gap
        }
        if (duplicate > 0) out += Finding(VerifyCheck.TIMING, "$duplicate frames share a timestamp")
        if (worstGap > frameUs * 1.5) out += Finding(VerifyCheck.TIMING, "a gap of ${(worstGap / 1000).toLong()} ms between two frames")
        val expectedDuration = exp.durationUs
        if (abs(video.durationUs - expectedDuration) > frameUs) {
            out += Finding(VerifyCheck.TIMING, "the picture lasts ${video.durationUs / 1000} ms, expected ${expectedDuration / 1000} ms")
        }
    }

    private fun checkAudio(file: Mp4File, exp: VerifyExpectation, out: MutableList<Finding>) {
        val audio = file.audio
        if (audio == null) {
            out += Finding(VerifyCheck.AUDIO, "the file has no audio track although the project has sound")
            return
        }
        if (audio.count == 0) {
            out += Finding(VerifyCheck.AUDIO, "the audio track is empty")
            return
        }
        if (audio.sizes.any { it <= 0 }) out += Finding(VerifyCheck.AUDIO, "${audio.sizes.count { it <= 0 }} audio samples are empty")
        val beyond = (0 until audio.count).count { audio.offsets[it] + audio.sizes[it] > file.fileSize }
        if (beyond > 0) out += Finding(VerifyCheck.AUDIO, "$beyond audio samples lie beyond the end of the file")
        val difference = audio.durationUs - exp.durationUs
        if (difference < -AUDIO_SHORTER_TOLERANCE_US) {
            out += Finding(VerifyCheck.AUDIO, "the sound is ${-difference / 1000} ms shorter than the picture")
        } else if (difference > AUDIO_LONGER_TOLERANCE_US) {
            out += Finding(VerifyCheck.AUDIO, "the sound is ${difference / 1000} ms longer than the picture")
        }
    }

    /** Frames from [sample] (decode order) to the end of the file's video, as the user counts them. */
    fun framesFromEnd(video: Mp4Track, sample: Int): Long = (video.count - sample).toLong()
}
