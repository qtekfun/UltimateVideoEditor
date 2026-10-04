#pragma once

// CPU reference of the effect, mask and blend maths that the GLSL in render/shaders.h
// (kEffectFragment, kCompositeFragment) runs on the GPU. The host tests pin these formulas; change
// the shader and this file together.
//
// Colour effects work on display-referred, straight (not premultiplied) RGB in 0..1.

#include <algorithm>
#include <cmath>

#include "core/layer_fx.h"
#include "render/grade_math.h"

namespace uv::render {

struct Rgba {
    float r = 0.0f;
    float g = 0.0f;
    float b = 0.0f;
    float a = 1.0f;
};

inline float clamp01(float v) { return v < 0.0f ? 0.0f : (v > 1.0f ? 1.0f : v); }

inline float smoothstep(float e0, float e1, float x) {
    const float t = clamp01((x - e0) / (e1 - e0));
    return t * t * (3.0f - 2.0f * t);
}

inline float mixf(float a, float b, float t) { return a + (b - a) * t; }

inline float luma709(float r, float g, float b) { return 0.2126f * r + 0.7152f * g + 0.0722f * b; }

// Blur sigma in pixels for the "radius" parameter (0..1) of a layer `layerHeightPx` tall.
inline float blurSigmaPx(float radius, float layerHeightPx) { return radius * 0.02f * layerHeightPx; }

inline constexpr int kBlurTapsEachSide = 16;

// Distance in texels between blur taps: 1 for small blurs, wider (so 3 sigma is covered) for big ones.
inline float blurStepTexels(float sigmaPx) { return std::max(1.0f, sigmaPx * 3.0f / static_cast<float>(kBlurTapsEachSide)); }

inline float blurWeight(int tap, float stepTexels, float sigmaPx) {
    const float x = static_cast<float>(tap) * stepTexels / sigmaPx;
    return std::exp(-0.5f * x * x);
}

// Blur passes under this sigma (pixels) change nothing visible and are skipped.
inline constexpr float kMinBlurSigmaPx = 0.3f;

inline constexpr float kVignetteInnerBase = 0.7f;
inline constexpr float kChromaSimilarityScale = 0.5f;
inline constexpr float kChromaSmoothScale = 0.5f;
inline constexpr float kChromaSpillReach = 0.25f;

// One per-pixel effect (everything except blur and sharpen, which need neighbours). `u`,`v` is the
// pixel position in the layer, 0..1, used by the vignette.
inline Rgba applyColorEffect(const core::EffectOp& op, Rgba c, float u, float v) {
    using core::EffectType;
    const float* p = op.v;
    switch (op.type) {
        case EffectType::Brightness:
            c.r += p[0];
            c.g += p[0];
            c.b += p[0];
            break;
        case EffectType::Contrast:
            c.r = (c.r - 0.5f) * p[0] + 0.5f;
            c.g = (c.g - 0.5f) * p[0] + 0.5f;
            c.b = (c.b - 0.5f) * p[0] + 0.5f;
            break;
        case EffectType::Saturation: {
            const float l = luma709(c.r, c.g, c.b);
            c.r = mixf(l, c.r, p[0]);
            c.g = mixf(l, c.g, p[0]);
            c.b = mixf(l, c.b, p[0]);
            break;
        }
        case EffectType::Exposure: {
            const float gain = std::exp2(p[0]);
            c.r *= gain;
            c.g *= gain;
            c.b *= gain;
            break;
        }
        case EffectType::Temperature:  // warm: more red, less blue
            c.r *= 1.0f + 0.2f * p[0];
            c.b *= 1.0f - 0.2f * p[0];
            break;
        case EffectType::Tint:  // positive: towards magenta (less green)
            c.g *= 1.0f - 0.2f * p[0];
            c.r *= 1.0f + 0.1f * p[0];
            c.b *= 1.0f + 0.1f * p[0];
            break;
        case EffectType::Grayscale: {
            const float l = luma709(c.r, c.g, c.b);
            c.r = mixf(c.r, l, p[0]);
            c.g = mixf(c.g, l, p[0]);
            c.b = mixf(c.b, l, p[0]);
            break;
        }
        case EffectType::Sepia: {
            const float sr = 0.393f * c.r + 0.769f * c.g + 0.189f * c.b;
            const float sg = 0.349f * c.r + 0.686f * c.g + 0.168f * c.b;
            const float sb = 0.272f * c.r + 0.534f * c.g + 0.131f * c.b;
            c.r = mixf(c.r, clamp01(sr), p[0]);
            c.g = mixf(c.g, clamp01(sg), p[0]);
            c.b = mixf(c.b, clamp01(sb), p[0]);
            break;
        }
        case EffectType::Vignette: {
            const float px = (u - 0.5f) * 2.0f;
            const float py = (v - 0.5f) * 2.0f;
            const float r = std::sqrt(px * px + py * py) / 1.41421356f;
            const float inner = kVignetteInnerBase * (1.0f - p[1]);
            const float shade = 1.0f - p[0] * smoothstep(inner, 1.0f, r);
            c.r *= shade;
            c.g *= shade;
            c.b *= shade;
            break;
        }
        case EffectType::ChromaKey: {
            const float ky = luma709(p[0], p[1], p[2]);
            const float kcb = (p[2] - ky) / 1.8556f;
            const float kcr = (p[0] - ky) / 1.5748f;
            const float y = luma709(c.r, c.g, c.b);
            const float cb = (c.b - y) / 1.8556f;
            const float cr = (c.r - y) / 1.5748f;
            const float d = std::sqrt((cb - kcb) * (cb - kcb) + (cr - kcr) * (cr - kcr));
            const float t0 = p[3] * kChromaSimilarityScale;
            const float t1 = t0 + p[4] * kChromaSmoothScale + 0.0001f;
            const float keep = smoothstep(t0, t1, d);  // 0 = fully keyed out
            const float spill = p[5] * (1.0f - smoothstep(t1, t1 + kChromaSpillReach, d));
            c.r = mixf(c.r, y, spill);
            c.g = mixf(c.g, y, spill);
            c.b = mixf(c.b, y, spill);
            c.a *= keep;
            break;
        }
        case EffectType::Blur:
        case EffectType::Sharpen:
            break;  // neighbourhood effects, see sharpenPremultiplied
        case EffectType::Lut:
            break;  // needs the LUT table, see applyLut
        case EffectType::Stabilise:
            break;  // moves pixels instead of changing them, see stabilise/stab_warp.h
        case EffectType::Denoise:
        case EffectType::Deflicker:
            break;  // neighbourhood / temporal effects, see render/repair_math.h
        case EffectType::ColorGrade: {
            if (op.grade.size() == static_cast<size_t>(core::kGradeWireValues)) {
                float rgb[3] = {c.r, c.g, c.b};
                applyGrade(op.grade.data(), rgb);
                c.r = rgb[0];
                c.g = rgb[1];
                c.b = rgb[2];
            }
            break;
        }
    }
    c.r = clamp01(c.r);
    c.g = clamp01(c.g);
    c.b = clamp01(c.b);
    return c;
}

// Unsharp mask on a premultiplied value: `centre` and the mean of the four neighbours `avg4`.
inline float sharpenPremultiplied(float centre, float avg4, float amount, float alpha) {
    const float out = centre + amount * (centre - avg4);
    return std::min(std::max(out, 0.0f), alpha);
}

// Share of the layer a mask lets through at point (px, py) of the layer box (-0.5..0.5, y down).
inline float maskCoverage(const core::MaskParams& m, float px, float py) {
    if (m.shape == 0) return 1.0f;
    const float hx = m.w * 0.5f;
    const float hy = m.h * 0.5f;
    const float dx = px - m.cx;
    const float dy = py - m.cy;
    float dist = 0.0f;
    if (m.shape == 1) {
        const float qx = std::fabs(dx) - hx;
        const float qy = std::fabs(dy) - hy;
        dist = std::sqrt(std::max(qx, 0.0f) * std::max(qx, 0.0f) + std::max(qy, 0.0f) * std::max(qy, 0.0f)) +
               std::min(std::max(qx, qy), 0.0f);
    } else {
        const float k = std::sqrt((dx / hx) * (dx / hx) + (dy / hy) * (dy / hy));
        dist = (k - 1.0f) * std::min(hx, hy);
    }
    float cov = 0.0f;
    if (m.feather <= 0.0f) {
        cov = dist <= 0.0f ? 1.0f : 0.0f;
    } else {
        cov = 1.0f - smoothstep(-m.feather, m.feather, dist);
    }
    return m.invert ? 1.0f - cov : cov;
}

// The 3D LUT effect (core::EffectType::Lut): trilinear lookup of straight RGB in a `size`^3 table of
// RGB triples (red varying fastest, as in a .cube file), mixed with the input by `intensity`. This is
// the same maths as the GPU's texture(uLut, (rgb * (size - 1) + 0.5) / size) with linear filtering.
inline void lutSample(const float* data, int size, const float in[3], float out[3]) {
    const float maxIndex = static_cast<float>(size - 1);
    float f[3];
    int i0[3];
    float t[3];
    for (int c = 0; c < 3; ++c) {
        f[c] = clamp01(in[c]) * maxIndex;
        i0[c] = std::min(static_cast<int>(f[c]), size - 2);
        t[c] = f[c] - static_cast<float>(i0[c]);
    }
    auto at = [&](int r, int g, int b, int c) { return data[((b * size + g) * size + r) * 3 + c]; };
    for (int c = 0; c < 3; ++c) {
        const float c00 = mixf(at(i0[0], i0[1], i0[2], c), at(i0[0] + 1, i0[1], i0[2], c), t[0]);
        const float c10 = mixf(at(i0[0], i0[1] + 1, i0[2], c), at(i0[0] + 1, i0[1] + 1, i0[2], c), t[0]);
        const float c01 = mixf(at(i0[0], i0[1], i0[2] + 1, c), at(i0[0] + 1, i0[1], i0[2] + 1, c), t[0]);
        const float c11 = mixf(at(i0[0], i0[1] + 1, i0[2] + 1, c), at(i0[0] + 1, i0[1] + 1, i0[2] + 1, c), t[0]);
        out[c] = mixf(mixf(c00, c10, t[1]), mixf(c01, c11, t[1]), t[2]);
    }
}

inline void applyLut(const float* data, int size, float intensity, float rgb[3]) {
    float graded[3];
    lutSample(data, size, rgb, graded);
    for (int c = 0; c < 3; ++c) rgb[c] = clamp01(mixf(rgb[c], graded[c], intensity));
}

// Blended colour of source `s` over destination `d` (both straight RGB, display-referred).
inline float blendChannel(core::BlendMode mode, float s, float d) {
    using core::BlendMode;
    switch (mode) {
        case BlendMode::Add: return std::min(s + d, 1.0f);
        case BlendMode::Multiply: return s * d;
        case BlendMode::Screen: return 1.0f - (1.0f - s) * (1.0f - d);
        case BlendMode::Overlay: return d < 0.5f ? 2.0f * s * d : 1.0f - 2.0f * (1.0f - s) * (1.0f - d);
        case BlendMode::Normal: break;
    }
    return s;
}

// What a layer of straight colour `s` and coverage `a` leaves over an opaque destination `d`.
inline float compositeChannel(core::BlendMode mode, float s, float d, float a) {
    return mixf(d, blendChannel(mode, s, d), a);
}

}  // namespace uv::render
