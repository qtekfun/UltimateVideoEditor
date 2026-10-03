#pragma once

// Integer-only time conversions for the audio path (SPECS.md section 3). Everything is exact
// rational arithmetic on 128-bit intermediates: no floats, so no drift over long timelines.

#include <cstdint>

namespace uv::audio {

using i128 = __int128;

struct Rational {
    int32_t num = 1;
    int32_t den = 1;
};

// Floor division for b > 0, correct for negative numerators.
inline int64_t floorDiv(i128 a, i128 b) {
    i128 q = a / b;
    if ((a % b) != 0 && a < 0) --q;
    return static_cast<int64_t>(q);
}

// Project frame -> output sample index, rounded half up. Adjacent clips computed with this
// function tile exactly (end of one == start of the next).
inline int64_t framesToSamples(int64_t frame, Rational fps, int32_t sampleRate) {
    return floorDiv(static_cast<i128>(2) * frame * sampleRate * fps.den + fps.num,
                    static_cast<i128>(2) * fps.num);
}

// Output sample index -> project frame: the last frame whose start sample (per framesToSamples)
// is <= `sample`, i.e. the exact inverse of the rounding above. Derivation:
//   floor((2f*R*d + n) / 2n) <= s  <=>  f < n(2s+1) / (2*R*d)   =>  f = floor((n(2s+1) - 1) / (2*R*d))
inline int64_t samplesToFrames(int64_t sample, Rational fps, int32_t sampleRate) {
    return floorDiv(static_cast<i128>(fps.num) * (2 * static_cast<i128>(sample) + 1) - 1,
                    static_cast<i128>(2) * sampleRate * fps.den);
}

// Frame index in a source's own frame rate -> microseconds (rounded half up).
inline int64_t sourceFramesToMicros(int64_t frame, Rational srcFps) {
    return floorDiv(static_cast<i128>(frame) * 1000000 * srcFps.den + srcFps.num / 2, srcFps.num);
}

inline int64_t samplesToMicros(int64_t sample, int32_t sampleRate) {
    return floorDiv(static_cast<i128>(sample) * 1000000 + sampleRate / 2, sampleRate);
}

inline int64_t microsToSamples(int64_t micros, int32_t sampleRate) {
    return floorDiv(static_cast<i128>(micros) * sampleRate + 500000, 1000000);
}

// Stream frame that the hardware is presenting at `nowNs`, extrapolated from a timestamp pair
// (hwFrame was presented at hwTimeNs). Both clocks must be the same monotonic clock.
inline int64_t presentedStreamFrame(int64_t hwFrame, int64_t hwTimeNs, int64_t nowNs, int32_t sampleRate) {
    return hwFrame + floorDiv(static_cast<i128>(nowNs - hwTimeNs) * sampleRate, 1000000000);
}

}  // namespace uv::audio
