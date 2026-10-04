#pragma once

// CPU reference of the HSL qualifier (core::EffectType::Qualifier, secondary colour correction) that the GLSL in
// render/shaders.h (kEffectFragment, uType 18) runs on the GPU. The host tests pin these formulas; change the
// shader, domain/Qualifier.kt and this file together.
//
// A qualifier builds a matte from the pixel's hue, saturation and luma and corrects only where the matte is
// open: out = mix(in, corrected, matte). Wire layout of `EffectOp::grade` (kQualifierParams floats):
//   0 hue centre 0..1 (the colour wheel, 0 = red, 1/3 = green, 2/3 = blue)
//   1 hue half width 0..0.5 (0.5 takes every hue)   2 hue softness 0..0.5
//   3 saturation min 0..1   4 saturation max 0..1   5 saturation softness 0..0.5
//   6 luma min 0..1         7 luma max 0..1         8 luma softness 0..0.5
//   9 invert the matte (> 0.5)                      10 show the matte as a grey picture (> 0.5)
//   11 hue shift -0.5..0.5 of the wheel   12 saturation gain 0..2   13 lightness -1..1 (added to HSL lightness)
// Hue, saturation and lightness are the usual HSL of straight display-referred RGB in the project's working
// space, like every other colour effect; luma is Rec.709 weights of the same values.

#include <algorithm>
#include <cmath>

#include "core/layer_fx.h"

namespace uv::render {

// The number of values is core::kQualifierParams (core/layer_fx.h parses the wire form).

inline float qualClamp01(float v) { return v < 0.0f ? 0.0f : (v > 1.0f ? 1.0f : v); }

// Smooth 0..1 ramp between e0 and e1; a zero-width ramp is a hard step (the GLSL adds the same epsilon).
inline float qualSmooth(float e0, float e1, float x) {
    const float t = qualClamp01((x - e0) / std::max(e1 - e0, 0.0001f));
    return t * t * (3.0f - 2.0f * t);
}

// A band that is 1 between lo and hi, fading to 0 over `soft` outside both ends.
inline float qualBand(float v, float lo, float hi, float soft) {
    return qualSmooth(lo - soft, lo, v) * (1.0f - qualSmooth(hi, hi + soft, v));
}

inline void qualRgbToHsl(float r, float g, float b, float* h, float* s, float* l) {
    const float mx = std::max(r, std::max(g, b));
    const float mn = std::min(r, std::min(g, b));
    const float d = mx - mn;
    *l = 0.5f * (mx + mn);
    if (d <= 0.00001f) {
        *h = 0.0f;
        *s = 0.0f;
        return;
    }
    *s = d / std::max(1.0f - std::fabs(2.0f * *l - 1.0f), 0.00001f);
    float hue;
    if (mx == r) hue = (g - b) / d + (g < b ? 6.0f : 0.0f);
    else if (mx == g) hue = (b - r) / d + 2.0f;
    else hue = (r - g) / d + 4.0f;
    *h = hue / 6.0f;
}

inline void qualHslToRgb(float h, float s, float l, float rgb[3]) {
    const float c = (1.0f - std::fabs(2.0f * l - 1.0f)) * s;
    const float hp = (h - std::floor(h)) * 6.0f;
    const float x = c * (1.0f - std::fabs(std::fmod(hp, 2.0f) - 1.0f));
    float r = 0.0f, g = 0.0f, b = 0.0f;
    if (hp < 1.0f) { r = c; g = x; }
    else if (hp < 2.0f) { r = x; g = c; }
    else if (hp < 3.0f) { g = c; b = x; }
    else if (hp < 4.0f) { g = x; b = c; }
    else if (hp < 5.0f) { r = x; b = c; }
    else { r = c; b = x; }
    const float m = l - 0.5f * c;
    rgb[0] = r + m;
    rgb[1] = g + m;
    rgb[2] = b + m;
}

// How much of the pixel the qualifier selects, 0..1 (after inversion).
inline float qualifierMatte(const float* q, float r, float g, float b) {
    float h, s, l;
    qualRgbToHsl(r, g, b, &h, &s, &l);
    float hueTerm = 1.0f;
    if (q[1] < 0.5f) {
        float d = std::fabs(h - q[0]);
        d = std::min(d, 1.0f - d);  // distance on the wheel
        hueTerm = 1.0f - qualSmooth(q[1], q[1] + q[2], d);
    }
    const float satTerm = qualBand(s, q[3], q[4], q[5]);
    const float y = 0.2126f * r + 0.7152f * g + 0.0722f * b;
    const float lumaTerm = qualBand(y, q[6], q[7], q[8]);
    const float matte = hueTerm * satTerm * lumaTerm;
    return q[9] > 0.5f ? 1.0f - matte : matte;
}

// Applies the qualifier `q` (kQualifierParams floats) to straight RGB in place; result clamped to 0..1.
inline void applyQualifier(const float* q, float rgb[3]) {
    const float matte = qualifierMatte(q, rgb[0], rgb[1], rgb[2]);
    if (q[10] > 0.5f) {
        rgb[0] = rgb[1] = rgb[2] = matte;
        return;
    }
    float h, s, l;
    qualRgbToHsl(rgb[0], rgb[1], rgb[2], &h, &s, &l);
    float corrected[3];
    qualHslToRgb(h + q[11], qualClamp01(s * q[12]), qualClamp01(l + q[13]), corrected);
    for (int i = 0; i < 3; ++i) rgb[i] = qualClamp01(rgb[i] + (corrected[i] - rgb[i]) * matte);
}

}  // namespace uv::render
