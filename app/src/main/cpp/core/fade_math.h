#pragma once

// The one definition of a clip's own fade handles (fade in / fade out) as a gain at a sample. The audio mixer (preview
// playback, export and the offline renders of the tests all go through it) calls fadeGainAt(); Kotlin mirrors the
// formulas in domain/FadeCurve.kt and both are checked against the same values, so change them together.
//
// A fade over `d` steps has progress (k + 0.5) / d at step k (never exactly 0 or 1 inside the fade, as for
// transitions, see crossfade_math.h). The shape maps the progress of a fade-in to a gain; a fade-out is the same
// curve played backwards, so the two halves of a cut made inside a fade join without a step.

#include <cmath>
#include <cstdint>

#include "core/crossfade_math.h"

namespace uv::core {

// Wire values (2 bits in the audio snapshot, see audio/audio_snapshot.h); an unknown value reads as EqualPower.
enum class FadeShape : int32_t {
    EqualPower = 0,   // sin(p * pi / 2): constant power against a fade of the same shape on a neighbour
    Linear = 1,       // p: constant amplitude ramp
    Logarithmic = 2,  // -60 dB to 0 dB in equal dB steps: sounds even to the ear, slow to start
};

constexpr int32_t kFadeShapeCount = 3;

inline FadeShape fadeShapeFromWire(int32_t v) {
    return (v >= 0 && v < kFadeShapeCount) ? static_cast<FadeShape>(v) : FadeShape::EqualPower;
}

// Gain of a fade-in at progress p in [0, 1].
inline float fadeShapeGain(FadeShape shape, double p) {
    if (p <= 0.0) return 0.0f;
    if (p >= 1.0) return 1.0f;
    switch (shape) {
        case FadeShape::Linear:
            return static_cast<float>(p);
        case FadeShape::Logarithmic: {
            // 10^(-3 (1 - p)) is -60 dB at the start; the floor is subtracted so the curve really starts at silence.
            constexpr double kFloor = 0.001;
            return static_cast<float>((std::pow(10.0, -3.0 * (1.0 - p)) - kFloor) / (1.0 - kFloor));
        }
        case FadeShape::EqualPower:
        default:
            return static_cast<float>(std::sin(p * 1.57079632679489661923));
    }
}

// Gain of a fade-in at step k of d (k = samples since the clip's start).
inline float fadeInShapeGain(FadeShape shape, int64_t k, int64_t d) {
    if (d <= 0 || k >= d) return 1.0f;
    return fadeShapeGain(shape, crossfadeProgress(k, d));
}

// Gain of a fade-out at step k of d (k = samples since the fade began); silent past the end.
inline float fadeOutShapeGain(FadeShape shape, int64_t k, int64_t d) {
    if (d <= 0) return 1.0f;
    if (k >= d) return 0.0f;
    return fadeShapeGain(shape, 1.0 - crossfadeProgress(k, d));
}

// The factor the fade handles of a clip `length` samples long apply to its sample `s` (clip-local, 0 = first sample).
// fadeIn / fadeOut are in samples, 0 = none; where the two overlap their gains multiply.
inline float fadeGainAt(FadeShape shape, int64_t fadeIn, int64_t fadeOut, int64_t length, int64_t s) {
    float gain = 1.0f;
    if (fadeIn > 0 && s < fadeIn) gain *= fadeInShapeGain(shape, s, fadeIn);
    if (fadeOut > 0) {
        const int64_t from = length - fadeOut;
        if (s >= from) gain *= fadeOutShapeGain(shape, s - from, fadeOut);
    }
    return gain;
}

}  // namespace uv::core
