package com.qtekfun.ultimatevideoeditor.data.interchange

import kotlin.math.roundToInt

/**
 * SMPTE timecode for the interchange formats. Non-drop frame counts [nominal] frames per second;
 * drop frame (29.97 and 59.94 only) skips frame numbers, not frames, so the clock keeps real time.
 */
class SmpteTimecode(val fpsNum: Int, val fpsDen: Int, dropFrame: Boolean = false) {
    /** Frames per second as written on the clock: 24 for 23.976, 30 for 29.97, 60 for 59.94. */
    val nominal: Int = (fpsNum.toDouble() / fpsDen).roundToInt().coerceAtLeast(1)

    /** Drop frame only exists for the NTSC rates 29.97 and 59.94; asking for it elsewhere is ignored. */
    val dropFrame: Boolean = dropFrame && fpsDen == 1001 && (nominal == 30 || nominal == 60)

    private val dropped: Int = if (this.dropFrame) nominal / 15 else 0

    /** "HH:MM:SS:FF", or "HH:MM:SS;FF" in drop frame, for frame number [frame] (0 is 00:00:00:00). */
    fun format(frame: Long): String {
        require(frame >= 0) { "timecode of a negative frame" }
        var n = frame
        if (dropFrame) {
            val framesPer10Minutes = nominal * 600L - dropped * 9L
            val framesPerMinute = nominal * 60L - dropped
            val tenMinutes = n / framesPer10Minutes
            val rest = n % framesPer10Minutes
            n += dropped * 9L * tenMinutes
            if (rest >= dropped) n += dropped * ((rest - dropped) / framesPerMinute)
        }
        val ff = (n % nominal).toInt()
        val totalSeconds = n / nominal
        val ss = (totalSeconds % 60).toInt()
        val mm = ((totalSeconds / 60) % 60).toInt()
        val hh = (totalSeconds / 3600).toInt()
        val sep = if (dropFrame) ';' else ':'
        return "%02d:%02d:%02d%c%02d".format(hh, mm, ss, sep, ff)
    }

    companion object {
        /** True for the rates where editors conventionally count drop frame. */
        fun isNtsc(fpsNum: Int, fpsDen: Int): Boolean = fpsDen == 1001 && (fpsNum == 30000 || fpsNum == 60000)
    }
}
