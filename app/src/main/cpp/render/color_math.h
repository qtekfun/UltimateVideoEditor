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

// ---- HDR (HLG) targets ------------------------------------------------------------------------
//
// An HLG project is rendered in HLG signal space (BT.2100 HLG, Rec.2020 primaries, 1000 nit system),
// and layers are blended there as well. Reference white: SDR white and graphics sit at 203 nit,
// which is HLG signal ~0.75 (BT.2408).
//
// SDR Rec.709 -> HLG Rec.2020:
//   1. display-referred decode, gamma 2.4 (BT.1886), so 1.0 = SDR peak white
//   2. Rec.709 -> Rec.2020 primaries (BT.2087 inverse)
//   3. scale so SDR white = 203 nit, expressed relative to the 1000 nit HLG display
//   4. invert the HLG OOTF (display -> scene), 5. HLG OETF.
// PQ -> HLG skips 1-3: PQ already encodes display nits (10000 nit peak), clipped at 1000 nit.
// PQ -> SDR uses the HLG tone-map path from the display-nit stage on.

constexpr float kPqM1 = 2610.0f / 16384.0f;
constexpr float kPqM2 = 2523.0f / 4096.0f * 128.0f;
constexpr float kPqC1 = 3424.0f / 4096.0f;
constexpr float kPqC2 = 2413.0f / 4096.0f * 32.0f;
constexpr float kPqC3 = 2392.0f / 4096.0f * 32.0f;
constexpr float kPqPeakNits = 10000.0f;
constexpr float kSdrGamma = 2.4f;

// Row-major linear Rec.709 -> Rec.2020 (BT.2087).
constexpr std::array<float, 9> kRec709ToRec2020 = {
    0.6274f, 0.3293f, 0.0433f,
    0.0691f, 0.9195f, 0.0114f,
    0.0164f, 0.0880f, 0.8956f,
};

// Scene linear [0,1] -> HLG signal [0,1] (inverse of hlgInverseOetf).
inline float hlgOetf(float e) {
    e = std::clamp(e, 0.0f, 1.0f);
    return e <= 1.0f / 12.0f ? std::sqrt(3.0f * e) : kHlgA * std::log(12.0f * e - kHlgB) + kHlgC;
}

// ST 2084 signal [0,1] -> display light in nit.
inline float pqEotfNits(float e) {
    e = std::clamp(e, 0.0f, 1.0f);
    const float p = std::pow(e, 1.0f / kPqM2);
    const float num = std::max(p - kPqC1, 0.0f);
    const float den = kPqC2 - kPqC3 * p;
    return kPqPeakNits * std::pow(num / den, 1.0f / kPqM1);
}

// Display light (1.0 = 1000 nit peak) -> scene light: inverse of hlgOotf.
inline Vec3 hlgInverseOotf(const Vec3& display) {
    const float yd = luminance2020(display);
    const float gain = yd > 0.0f ? std::pow(yd, (1.0f - kSystemGamma) / kSystemGamma) : 0.0f;
    return {display.r * gain, display.g * gain, display.b * gain};
}

// Display light relative to the 1000 nit HLG display (values > 1 clip) -> nonlinear HLG signal.
inline Vec3 displayToHlgSignal(Vec3 display) {
    display = {std::clamp(display.r, 0.0f, 1.0f), std::clamp(display.g, 0.0f, 1.0f), std::clamp(display.b, 0.0f, 1.0f)};
    const Vec3 scene = hlgInverseOotf(display);
    return {hlgOetf(scene.r), hlgOetf(scene.g), hlgOetf(scene.b)};
}

// Nonlinear SDR Rec.709 RGB in [0,1] -> nonlinear HLG Rec.2020 RGB in [0,1].
inline Vec3 sdr709ToHlg2020(const Vec3& sdr) {
    const Vec3 lin{std::pow(std::clamp(sdr.r, 0.0f, 1.0f), kSdrGamma), std::pow(std::clamp(sdr.g, 0.0f, 1.0f), kSdrGamma),
                   std::pow(std::clamp(sdr.b, 0.0f, 1.0f), kSdrGamma)};
    Vec3 d = mul(kRec709ToRec2020, lin);
    const float scale = kDiffuseWhiteNits / kPeakNits;
    d = {d.r * scale, d.g * scale, d.b * scale};
    return displayToHlgSignal(d);
}

// Nonlinear PQ BT.2020 RGB in [0,1] -> display light where 1.0 = 203 nit SDR white.
inline Vec3 pq2020ToDisplayRelative203(const Vec3& pq) {
    const float k = 1.0f / kDiffuseWhiteNits;
    return {pqEotfNits(pq.r) * k, pqEotfNits(pq.g) * k, pqEotfNits(pq.b) * k};
}

inline Vec3 pq2020ToSdr709(const Vec3& pq) {
    Vec3 display = pq2020ToDisplayRelative203(pq);
    const float l = luminance2020(display);
    if (l > 0.0f) {
        const float k = toneMapLuma(l) / l;
        display = {display.r * k, display.g * k, display.b * k};
    }
    Vec3 lin = mul(kRec2020ToRec709, display);
    lin = {std::clamp(lin.r, 0.0f, 1.0f), std::clamp(lin.g, 0.0f, 1.0f), std::clamp(lin.b, 0.0f, 1.0f)};
    return {bt709Oetf(lin.r), bt709Oetf(lin.g), bt709Oetf(lin.b)};
}

inline Vec3 pq2020ToHlg2020(const Vec3& pq) {
    const float k = kDiffuseWhiteNits / kPeakNits;  // 203-nit-relative -> 1000-nit-relative
    const Vec3 rel = pq2020ToDisplayRelative203(pq);
    return displayToHlgSignal({rel.r * k, rel.g * k, rel.b * k});
}

// Applies `mode` (render/color_space.h) to a nonlinear source pixel. Mirrors the composite shader.
inline Vec3 convertToTarget(int mode, const Vec3& rgb) {
    switch (mode) {
        case 1: return hlg2020ToSdr709(rgb);
        case 2: return sdr709ToHlg2020(rgb);
        case 4: return pq2020ToSdr709(rgb);
        case 5: return pq2020ToHlg2020(rgb);
        default: return rgb;  // 0 SDR->SDR and 3 HLG->HLG are sampled as is
    }
}

}  // namespace uv::render
