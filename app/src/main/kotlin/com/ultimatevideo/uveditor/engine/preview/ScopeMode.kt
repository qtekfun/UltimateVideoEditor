package com.ultimatevideo.uveditor.engine.preview

/** Which video scope the scopes panel draws. [code] is the native `scope::Mode` value (`render/scope_math.h`). */
enum class ScopeMode(val code: Int, val label: String) {
    WAVEFORM(0, "Waveform"),
    PARADE(1, "RGB parade"),
    VECTORSCOPE(2, "Vectorscope"),
    HISTOGRAM(3, "Histogram"),
    ;

    companion object {
        fun fromCode(code: Int): ScopeMode? = entries.firstOrNull { it.code == code }
    }
}
