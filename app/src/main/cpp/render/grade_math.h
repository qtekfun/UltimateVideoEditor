#pragma once

// CPU reference of the colour grade effect (core::EffectType::ColorGrade) that the GLSL in
// render/shaders.h (kEffectFragment, uType 14) runs on the GPU. The host tests pin these formulas;
// change the shader, domain/GradeMath.kt and this file together.
//
// Wire layout of `EffectOp::grade` (see core/layer_fx.h): kGradeParams parameters then
// kGradeCurveSamples x (master, red, green, blue) curve samples at x = i / (kGradeCurveSamples - 1).
//   0..3   lift   R G B master    each -1..1; positive raises the blacks
//   4..7   gamma  R G B master    each -1..1; positive brightens the midtones
//   8..11  gain   R G B master    each -1..1; scales by 2^value (a stop per unit)
//   12..14 offset R G B           each -0.5..0.5; added to the colour
//   15 contrast 0..2 (1 = none)   16 pivot 0..1 (0.5)   17 saturation 0..2 (1)   18 vibrance -1..1
//   19 temperature -1..1          20 tint -1..1
// The grade runs on straight display-referred RGB in 0..1 in the project's working space (Rec.709
// gamma in an SDR project, the HLG signal in an HLG project), like every other colour effect.

#include <algorithm>
#include <cmath>

#include "core/layer_fx.h"

namespace uv::render {

inline float gradeClamp01(float v) { return v < 0.0f ? 0.0f : (v > 1.0f ? 1.0f : v); }

// Piecewise-linear lookup in one channel of the baked curve block: `curve` points at the first
// sample, `channel` is 0 master, 1 red, 2 green, 3 blue.
inline float gradeCurve(const float* curve, int channel, float x) {
    const float pos = gradeClamp01(x) * static_cast<float>(core::kGradeCurveSamples - 1);
    const int i = std::min(static_cast<int>(pos), core::kGradeCurveSamples - 2);
    const float t = pos - static_cast<float>(i);
    const float a = curve[i * 4 + channel];
    const float b = curve[(i + 1) * 4 + channel];
    return a + (b - a) * t;
}

// Applies the grade `g` (kGradeWireValues floats) to straight RGB in place; result clamped to 0..1.
inline void applyGrade(const float* g, float rgb[3]) {
    const float temperature = g[19];
    const float tint = g[20];
    float c[3] = {rgb[0], rgb[1], rgb[2]};
    // White balance, as the standalone temperature and tint effects do.
    c[0] *= (1.0f + 0.2f * temperature) * (1.0f + 0.1f * tint);
    c[1] *= 1.0f - 0.2f * tint;
    c[2] *= (1.0f - 0.2f * temperature) * (1.0f + 0.1f * tint);
    const float contrast = g[15];
    const float pivot = g[16];
    for (int k = 0; k < 3; ++k) {
        float x = c[k] + g[12 + k];                // offset
        x = (x - pivot) * contrast + pivot;         // contrast about the pivot
        x = gradeClamp01(x);
        const float lift = 0.5f * (g[3] + g[k]);
        x = x + lift * (1.0f - x);                  // lift raises the blacks, leaves white
        x = gradeClamp01(x * std::exp2(g[11] + g[8 + k]));  // gain, in stops
        x = std::pow(x, std::exp2(-(g[7] + g[4 + k])));    // gamma: positive brightens midtones
        c[k] = x;
    }
    const float luma = 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2];
    const float chroma = std::max({c[0], c[1], c[2]}) - std::min({c[0], c[1], c[2]});
    const float factor = std::max(0.0f, g[17] * (1.0f + g[18] * (1.0f - chroma)));  // saturation, vibrance
    const float* curves = g + core::kGradeParams;
    for (int k = 0; k < 3; ++k) {
        const float x = luma + (c[k] - luma) * factor;
        rgb[k] = gradeClamp01(gradeCurve(curves, 1 + k, gradeCurve(curves, 0, x)));
    }
}

}  // namespace uv::render
