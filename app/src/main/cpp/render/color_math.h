#pragma once

// CPU reference for the colour pipeline implemented in GLSL (render/shaders.h).
// Host-buildable and unit-tested; the shader must stay numerically in sync with this file.
//
// Pipeline for HLG / Rec.2020 -> SDR Rec.709:
//   1. HLG inverse OETF (BT.2100) -> scene linear
//   2. HLG OOTF, 1000 nit system gamma 1.2 -> display linear (1.0 = 1000 nit)
//   3. scale so 203 nit diffuse white = 1.0, tone-map luminance with a soft shoulder
//   4. Rec.2020 -> Rec.709 primaries, clamp
//   5. BT.709 OETF

#include <algorithm>
#include <array>
#include <cmath>

namespace uv::render {

struct Vec3 {
    float r, g, b;
};

constexpr float kHlgA = 0.17883277f;
constexpr float kHlgB = 0.28466892f;
constexpr float kHlgC = 0.55991073f;
constexpr float kSystemGamma = 1.2f;
constexpr float kDiffuseWhiteNits = 203.0f;
constexpr float kPeakNits = 1000.0f;
// Tunable: linear below the knee, soft shoulder up to the peak.
constexpr float kToneKnee = 0.9f;

inline float hlgInverseOetf(float e) {
    e = std::clamp(e, 0.0f, 1.0f);
    return e <= 0.5f ? (e * e) / 3.0f : (std::exp((e - kHlgC) / kHlgA) + kHlgB) / 12.0f;
}

inline float luminance2020(const Vec3& c) { return 0.2627f * c.r + 0.6780f * c.g + 0.0593f * c.b; }

// Scene-linear -> display-linear (1.0 = peak).
inline Vec3 hlgOotf(const Vec3& scene) {
    const float ys = luminance2020(scene);
    const float gain = ys > 0.0f ? std::pow(ys, kSystemGamma - 1.0f) : 0.0f;
    return {scene.r * gain, scene.g * gain, scene.b * gain};
}

// Maps luminance (1.0 = SDR diffuse white, up to peak/203) to [0,1].
inline float toneMapLuma(float l) {
    if (l <= kToneKnee) return std::max(l, 0.0f);
    const float lw = kPeakNits / kDiffuseWhiteNits;
    const float t = (l - kToneKnee) / (1.0f - kToneKnee);
    const float tmax = (lw - kToneKnee) / (1.0f - kToneKnee);
    const float out = t * (1.0f + t / (tmax * tmax)) / (1.0f + t);
    return std::min(kToneKnee + (1.0f - kToneKnee) * out, 1.0f);
}

// Row-major linear Rec.2020 -> Rec.709 (BT.2087).
constexpr std::array<float, 9> kRec2020ToRec709 = {
    1.6605f,  -0.5876f, -0.0728f,
    -0.1246f, 1.1329f,  -0.0083f,
    -0.0182f, -0.1006f, 1.1187f,
};

inline Vec3 mul(const std::array<float, 9>& m, const Vec3& v) {
    return {m[0] * v.r + m[1] * v.g + m[2] * v.b,
            m[3] * v.r + m[4] * v.g + m[5] * v.b,
            m[6] * v.r + m[7] * v.g + m[8] * v.b};
}

inline float bt709Oetf(float l) {
    l = std::clamp(l, 0.0f, 1.0f);
    return l < 0.018f ? 4.5f * l : 1.099f * std::pow(l, 0.45f) - 0.099f;
}

// Nonlinear HLG BT.2020 RGB in [0,1] -> nonlinear BT.709 SDR RGB in [0,1].
inline Vec3 hlg2020ToSdr709(const Vec3& hlg) {
    const Vec3 scene{hlgInverseOetf(hlg.r), hlgInverseOetf(hlg.g), hlgInverseOetf(hlg.b)};
    Vec3 display = hlgOotf(scene);
    const float scale = kPeakNits / kDiffuseWhiteNits;
    display = {display.r * scale, display.g * scale, display.b * scale};
    const float l = luminance2020(display);
    if (l > 0.0f) {
        const float k = toneMapLuma(l) / l;
        display = {display.r * k, display.g * k, display.b * k};
    }
    Vec3 lin = mul(kRec2020ToRec709, display);
    lin = {std::clamp(lin.r, 0.0f, 1.0f), std::clamp(lin.g, 0.0f, 1.0f), std::clamp(lin.b, 0.0f, 1.0f)};
    return {bt709Oetf(lin.r), bt709Oetf(lin.g), bt709Oetf(lin.b)};
}

}  // namespace uv::render
