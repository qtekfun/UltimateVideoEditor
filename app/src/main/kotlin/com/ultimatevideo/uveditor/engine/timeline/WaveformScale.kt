package com.ultimatevideo.uveditor.engine.timeline

/** How the timeline draws the height of an audio waveform; [code] is `uv::audio::WaveScale`. */
enum class WaveformScale(val code: Int, val label: String) {
    /** Amplitude against the clip's loudest sound: speech and silence differ the way they sound. The default. */
    LINEAR(0, "Linear"),

    /** Decibels over a 54 dB range: quiet passages stay visible. */
    DECIBEL(1, "dB"),
}
