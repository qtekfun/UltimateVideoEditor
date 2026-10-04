#pragma once

// CPU reference of the classical image-repair maths that the GLSL in render/shaders.h
// (kFlowFragment, kInterpFragment, kDenoise*, kDeflicker*) runs on the GPU: block-matching optical flow,
// bidirectional warping and blending for smooth slow motion, edge-preserving noise reduction with a
// temporal part, and flicker removal by mean-luma smoothing. No machine learning and no third-party code.
// The host tests pin these formulas; change the shader and this file together.
//
// Images are straight (not premultiplied) RGB floats in 0..1; texel (x, y) of a W x H image is centred at
// uv ((x + 0.5) / W, (y + 0.5) / H).

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <vector>

#include "render/effect_math.h"

namespace uv::render::repair {

// ---- Image helpers -------------------------------------------------------------------------------

struct Rgb3 {
    float r = 0.0f;
    float g = 0.0f;
    float b = 0.0f;
};

inline Rgb3 mix3(const Rgb3& a, const Rgb3& b, float t) { return {mixf(a.r, b.r, t), mixf(a.g, b.g, t), mixf(a.b, b.b, t)}; }
inline Rgb3 add3(const Rgb3& a, const Rgb3& b) { return {a.r + b.r, a.g + b.g, a.b + b.b}; }
inline Rgb3 scale3(const Rgb3& a, float s) { return {a.r * s, a.g * s, a.b * s}; }
inline float luma3(const Rgb3& c) { return luma709(c.r, c.g, c.b); }

struct Image {
    int w = 0;
    int h = 0;
    std::vector<float> px;  // 3 floats per pixel, row 0 first

    Image() = default;
    Image(int width, int height) : w(width), h(height), px(static_cast<size_t>(width) * static_cast<size_t>(height) * 3, 0.0f) {}

    // Clamped to the edge, like GL_CLAMP_TO_EDGE.
    Rgb3 at(int x, int y) const {
        x = std::clamp(x, 0, w - 1);
        y = std::clamp(y, 0, h - 1);
        const size_t i = (static_cast<size_t>(y) * static_cast<size_t>(w) + static_cast<size_t>(x)) * 3;
        return {px[i], px[i + 1], px[i + 2]};
    }
    void set(int x, int y, const Rgb3& c) {
        const size_t i = (static_cast<size_t>(y) * static_cast<size_t>(w) + static_cast<size_t>(x)) * 3;
        px[i] = c.r;
        px[i + 1] = c.g;
        px[i + 2] = c.b;
    }
};

// GL_LINEAR sampling at normalised coordinates.
inline Rgb3 sampleBilinear(const Image& img, float u, float v) {
    const float fx = u * static_cast<float>(img.w) - 0.5f;
    const float fy = v * static_cast<float>(img.h) - 0.5f;
    const int x0 = static_cast<int>(std::floor(fx));
    const int y0 = static_cast<int>(std::floor(fy));
    const float tx = fx - static_cast<float>(x0);
    const float ty = fy - static_cast<float>(y0);
    const Rgb3 top = mix3(img.at(x0, y0), img.at(x0 + 1, y0), tx);
    const Rgb3 bottom = mix3(img.at(x0, y0 + 1), img.at(x0 + 1, y0 + 1), tx);
    return mix3(top, bottom, ty);
}

// Box average of `src` into a `w` x `h` image: every destination pixel is the mean of the source pixels it covers.
inline Image boxDownscale(const Image& src, int w, int h) {
    Image out(w, h);
    for (int y = 0; y < h; ++y) {
        const int y0 = y * src.h / h;
        const int y1 = std::max(y0 + 1, (y + 1) * src.h / h);
        for (int x = 0; x < w; ++x) {
            const int x0 = x * src.w / w;
            const int x1 = std::max(x0 + 1, (x + 1) * src.w / w);
            Rgb3 sum;
            int n = 0;
            for (int sy = y0; sy < y1; ++sy) {
                for (int sx = x0; sx < x1; ++sx) {
                    sum = add3(sum, src.at(sx, sy));
                    ++n;
                }
            }
            out.set(x, y, scale3(sum, 1.0f / static_cast<float>(n)));
        }
    }
    return out;
}

inline std::vector<float> lumaOf(const Image& img) {
    std::vector<float> out(static_cast<size_t>(img.w) * static_cast<size_t>(img.h));
    for (int y = 0; y < img.h; ++y) {
        for (int x = 0; x < img.w; ++x) out[static_cast<size_t>(y) * static_cast<size_t>(img.w) + static_cast<size_t>(x)] = luma3(img.at(x, y));
    }
    return out;
}

inline float meanLuma(const Image& img) {
    if (img.w <= 0 || img.h <= 0) return 0.0f;
    double sum = 0.0;
    for (int y = 0; y < img.h; ++y) {
        for (int x = 0; x < img.w; ++x) sum += luma3(img.at(x, y));
    }
    return static_cast<float>(sum / (static_cast<double>(img.w) * static_cast<double>(img.h)));
}

// ---- Optical flow by block matching --------------------------------------------------------------

// The search quality levels. Low runs in the preview (about 160 px wide flow field), high in the exporter.
struct FlowQuality {
    int flowWidth;    // width of the downscaled images the flow is estimated on (height keeps the aspect)
    int radius;       // search window: displacements -radius..radius in flow pixels, both axes
    int blockHalf;    // the matched block is (2 * blockHalf + 1) squared
};
inline constexpr FlowQuality kFlowLow{160, 6, 1};
inline constexpr FlowQuality kFlowHigh{320, 8, 1};

// A small bias towards short displacements so a flat area (where every candidate matches) stays at rest.
inline constexpr float kDisplacementPenalty = 0.0005f;
// Mean absolute luma difference of the best match: confidence is 1 below kConfGood and 0 above kConfBad.
inline constexpr float kConfGood = 0.03f;
inline constexpr float kConfBad = 0.12f;

struct FlowSample {
    float dx = 0.0f;  // flow pixels, from image A to image B
    float dy = 0.0f;
    float conf = 0.0f;  // 0 (no usable match: blend frames) .. 1
};

// Offset (-0.5..0.5) of the true minimum from the best integer displacement, from a parabola through the costs
// one step before it, at it and one step after it. Flat or inverted costs (no clear minimum) give 0.
inline float parabolicOffset(float before, float centre, float after) {
    const float denominator = before - 2.0f * centre + after;
    if (denominator < 1e-6f) return 0.0f;
    return std::clamp(0.5f * (before - after) / denominator, -0.5f, 0.5f);
}

// Flow from A to B at every pixel of the `w` x `h` luma images. The best integer displacement is refined to a
// fraction of a pixel with a parabola fit, which is what slow subtle motion (a fraction of a flow pixel per
// frame) needs. A best match on the border of the search window is an object that moved further than can be
// measured, so it gets no confidence.
inline std::vector<FlowSample> blockMatch(const std::vector<float>& lumaA, const std::vector<float>& lumaB, int w, int h,
                                          int radius, int blockHalf) {
    std::vector<FlowSample> out(static_cast<size_t>(w) * static_cast<size_t>(h));
    auto fetch = [&](const std::vector<float>& img, int x, int y) {
        x = std::clamp(x, 0, w - 1);
        y = std::clamp(y, 0, h - 1);
        return img[static_cast<size_t>(y) * static_cast<size_t>(w) + static_cast<size_t>(x)];
    };
    const float taps = static_cast<float>((2 * blockHalf + 1) * (2 * blockHalf + 1));
    auto meanSad = [&](int x, int y, int dx, int dy) {
        float sad = 0.0f;
        for (int by = -blockHalf; by <= blockHalf; ++by) {
            for (int bx = -blockHalf; bx <= blockHalf; ++bx) {
                sad += std::fabs(fetch(lumaA, x + bx, y + by) - fetch(lumaB, x + bx + dx, y + by + dy));
            }
        }
        return sad / taps;
    };
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            float best = 1e30f;
            float bestMean = 0.0f;
            int bestDx = 0;
            int bestDy = 0;
            for (int dy = -radius; dy <= radius; ++dy) {
                for (int dx = -radius; dx <= radius; ++dx) {
                    const float mean = meanSad(x, y, dx, dy);
                    const float cost = mean + kDisplacementPenalty * static_cast<float>(std::abs(dx) + std::abs(dy));
                    if (cost < best) {
                        best = cost;
                        bestMean = mean;
                        bestDx = dx;
                        bestDy = dy;
                    }
                }
            }
            float conf = 1.0f - smoothstep(kConfGood, kConfBad, bestMean);
            float fx = static_cast<float>(bestDx);
            float fy = static_cast<float>(bestDy);
            if (std::max(std::abs(bestDx), std::abs(bestDy)) >= radius) {
                conf = 0.0f;
            } else {
                fx += parabolicOffset(meanSad(x, y, bestDx - 1, bestDy), bestMean, meanSad(x, y, bestDx + 1, bestDy));
                fy += parabolicOffset(meanSad(x, y, bestDx, bestDy - 1), bestMean, meanSad(x, y, bestDx, bestDy + 1));
            }
            out[static_cast<size_t>(y) * static_cast<size_t>(w) + static_cast<size_t>(x)] = FlowSample{fx, fy, conf};
        }
    }
    return out;
}

// Bilinear lookup of the flow field at normalised (u, v).
inline FlowSample sampleFlow(const std::vector<FlowSample>& flow, int fw, int fh, float u, float v) {
    const float fx = u * static_cast<float>(fw) - 0.5f;
    const float fy = v * static_cast<float>(fh) - 0.5f;
    const int x0 = static_cast<int>(std::floor(fx));
    const int y0 = static_cast<int>(std::floor(fy));
    const float tx = fx - static_cast<float>(x0);
    const float ty = fy - static_cast<float>(y0);
    auto at = [&](int x, int y) {
        x = std::clamp(x, 0, fw - 1);
        y = std::clamp(y, 0, fh - 1);
        return flow[static_cast<size_t>(y) * static_cast<size_t>(fw) + static_cast<size_t>(x)];
    };
    auto lerp = [&](const FlowSample& a, const FlowSample& b, float t) {
        return FlowSample{mixf(a.dx, b.dx, t), mixf(a.dy, b.dy, t), mixf(a.conf, b.conf, t)};
    };
    return lerp(lerp(at(x0, y0), at(x0 + 1, y0), tx), lerp(at(x0, y0 + 1), at(x0 + 1, y0 + 1), tx), ty);
}

// The frame at fraction `t` (0 = A, 1 = B) between two source frames: every output pixel takes A from
// where the content was and B from where it is going, along the flow of the content that is at this pixel
// at time t, and the two are mixed by t. The flow lives on A's grid, so it is looked up a second time at the
// place the content came from (one fixed-point step), which keeps the edges of moving objects sharp instead of
// smearing them by the distance they travel. Where the flow has no confidence the plain blend of the two frames
// is used instead.
inline Rgb3 interpolatePixel(const Image& a, const Image& b, const std::vector<FlowSample>& flow, int fw, int fh, float u,
                             float v, float t) {
    const FlowSample first = sampleFlow(flow, fw, fh, u, v);
    const FlowSample f = sampleFlow(flow, fw, fh, u - t * first.dx / static_cast<float>(fw), v - t * first.dy / static_cast<float>(fh));
    const float du = f.dx / static_cast<float>(fw);
    const float dv = f.dy / static_cast<float>(fh);
    const Rgb3 fromA = sampleBilinear(a, u - t * du, v - t * dv);
    const Rgb3 fromB = sampleBilinear(b, u + (1.0f - t) * du, v + (1.0f - t) * dv);
    const Rgb3 warped = mix3(fromA, fromB, t);
    const Rgb3 plain = mix3(sampleBilinear(a, u, v), sampleBilinear(b, u, v), t);
    return mix3(plain, warped, f.conf);
}

// Plain frame blending, the fallback and the baseline the interpolation is compared with.
inline Rgb3 blendPixel(const Image& a, const Image& b, float u, float v, float t) {
    return mix3(sampleBilinear(a, u, v), sampleBilinear(b, u, v), t);
}

// ---- Noise reduction -----------------------------------------------------------------------------

inline constexpr int kDenoiseRadius = 2;          // 5 x 5 spatial window
inline constexpr float kDenoiseSigmaS = 1.4f;     // spatial falloff, pixels
inline constexpr float kMotionLo = 0.015f;        // difference of the smoothed frames below which the previous frame is trusted fully
inline constexpr float kMotionHi = 0.10f;         // ... and above which it is ignored
inline constexpr float kTemporalMax = 0.5f;       // at most this share of the previous frame is mixed in

// Range falloff of the bilateral filter for a spatial strength in 0..1: more strength tolerates bigger
// colour differences, so more of the noise (but also more fine detail) is averaged away.
inline float denoiseSigmaR(float strength) { return 0.02f + 0.2f * strength; }

inline Rgb3 denoiseSpatial(const Image& cur, int x, int y, float strength) {
    const Rgb3 centre = cur.at(x, y);
    if (strength <= 0.0f) return centre;
    const float sigmaR = denoiseSigmaR(strength);
    Rgb3 sum;
    float weightSum = 0.0f;
    for (int dy = -kDenoiseRadius; dy <= kDenoiseRadius; ++dy) {
        for (int dx = -kDenoiseRadius; dx <= kDenoiseRadius; ++dx) {
            const Rgb3 c = cur.at(x + dx, y + dy);
            const float ds = static_cast<float>(dx * dx + dy * dy) / (2.0f * kDenoiseSigmaS * kDenoiseSigmaS);
            const float dr = ((c.r - centre.r) * (c.r - centre.r) + (c.g - centre.g) * (c.g - centre.g) +
                              (c.b - centre.b) * (c.b - centre.b)) /
                             (2.0f * sigmaR * sigmaR);
            const float w = std::exp(-ds - dr);
            sum = add3(sum, scale3(c, w));
            weightSum += w;
        }
    }
    const Rgb3 smoothed = scale3(sum, 1.0f / weightSum);
    return mix3(centre, smoothed, strength);
}

// Spatial smoothing of both frames, then (when `prev` is given) a mix of the two smoothed pictures that
// fades out where they differ (motion). Mixing the previous frame raw would add its own noise back.
inline Rgb3 denoisePixel(const Image& cur, const Image* prev, int x, int y, float strength, float temporal) {
    const Rgb3 spatial = denoiseSpatial(cur, x, y, strength);
    if (prev == nullptr || temporal <= 0.0f) return spatial;
    const Rgb3 previous = denoiseSpatial(*prev, x, y, strength);
    const float motion = smoothstep(kMotionLo, kMotionHi, std::fabs(luma3(spatial) - luma3(previous)));
    const float weight = temporal * kTemporalMax * (1.0f - motion);
    return mix3(spatial, previous, weight);
}

// ---- Flicker removal -----------------------------------------------------------------------------

inline constexpr float kDeflickerMinGain = 0.5f;
inline constexpr float kDeflickerMaxGain = 2.0f;

// The brightness scale for a frame of mean luma `current` given the means of the neighbouring source
// frames (negative = not available): the frame is pulled towards the mean of the window by `strength`.
inline float deflickerGain(float previous, float current, float next, float strength) {
    float sum = current;
    int n = 1;
    if (previous >= 0.0f) {
        sum += previous;
        ++n;
    }
    if (next >= 0.0f) {
        sum += next;
        ++n;
    }
    const float target = sum / static_cast<float>(n);
    const float gain = std::clamp(target / std::max(current, 1e-3f), kDeflickerMinGain, kDeflickerMaxGain);
    return mixf(1.0f, gain, clamp01(strength));
}

}  // namespace uv::render::repair
