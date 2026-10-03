#pragma once

// Crossfade curves shared by the exporter (video opacity) and the audio mixer (equal-power gains).
// Kotlin mirrors these in domain/RenderPlan.kt (CrossfadeCurve); change both together.
//
// A fade over `d` steps has progress (k + 0.5) / d at step k: never exactly 0 or 1 inside the
// fade, and symmetric around the middle. Video opacity of the incoming clip is the progress;
// audio gains are sin/cos of it, so the powers of the two clips always sum to 1.

#include <cmath>
#include <cstdint>

namespace uv::core {

inline double crossfadeProgress(int64_t k, int64_t d) {
    if (d <= 0 || k >= d) return 1.0;
    return (static_cast<double>(k < 0 ? 0 : k) + 0.5) / static_cast<double>(d);
}

inline float crossfadeFadeInGain(int64_t k, int64_t d) {
    return static_cast<float>(std::sin(crossfadeProgress(k, d) * 1.57079632679489661923));
}

inline float crossfadeFadeOutGain(int64_t k, int64_t d) {
    // k counts from the start of the fade-out; a position past the fade is silent.
    if (d <= 0) return 1.0f;
    if (k >= d) return 0.0f;
    return static_cast<float>(std::cos(crossfadeProgress(k, d) * 1.57079632679489661923));
}

}  // namespace uv::core
