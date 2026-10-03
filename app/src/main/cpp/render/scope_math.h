#pragma once

// Maths of the video scopes (waveform, RGB parade, vectorscope, histogram), shared by the GLSL in
// render/scope_shaders.h and the host tests. The GPU draws one point per sample of a small copy of the
// preview frame into an accumulation texture; this header says where each sample lands and how a count
// becomes brightness, plus a CPU accumulator that follows the same rules so tests can pin them.
//
// Samples are straight display-referred RGB in 0..1 (the preview as shown: Rec.709 gamma in an SDR
// project, the HLG signal in an HLG project). Scale labels depend on the project space and are drawn by
// the app, not here.

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>

namespace uv::render::scope {

enum class Mode : int { Waveform = 0, Parade = 1, Vector = 2, Histogram = 3 };

// Size of the frame copy the scopes read (the preview is blitted down to this).
inline constexpr int kSrcWidth = 320;
inline constexpr int kSrcHeight = 180;
// The accumulation texture; the waveform and parade fill it, the vectorscope uses its left square.
inline constexpr int kAccumWidth = 512;
inline constexpr int kAccumHeight = 256;
inline constexpr int kVectorSize = 256;
inline constexpr int kHistogramBins = 256;
// Chroma (Cb, Cr in -0.5..0.5) to normalised device coordinates: a fully saturated primary reaches 0.9.
inline constexpr float kVectorScale = 1.8f;
// Brightness curve: 1 - exp(-k * count). Counts are the samples that fell on one accumulation pixel.
inline constexpr float kWaveformGain = 0.12f;
inline constexpr float kVectorGain = 0.5f;
// Histogram heights are log scaled against this many samples in one bin.
inline constexpr float kHistogramFull = 2880.0f;

struct Point {
    float x = 0.0f;  // normalised device coordinates, -1..1
    float y = 0.0f;
};

inline float clamp01(float v) { return v < 0.0f ? 0.0f : (v > 1.0f ? 1.0f : v); }
inline float luma709(float r, float g, float b) { return 0.2126f * r + 0.7152f * g + 0.0722f * b; }

// `col` is the sample's horizontal position in the picture, 0..1; `level` its value, 0..1.
inline Point waveformPoint(float col, float level) { return Point{col * 2.0f - 1.0f, clamp01(level) * 2.0f - 1.0f}; }

// The parade puts red, green and blue side by side, each across a third of the width.
inline Point paradePoint(float col, float level, int channel) {
    const float x = col / 3.0f + static_cast<float>(channel) / 3.0f;
    return Point{x * 2.0f - 1.0f, clamp01(level) * 2.0f - 1.0f};
}

// Cb horizontal (right = blue), Cr vertical (up = red), BT.709 weights.
inline Point vectorPoint(float r, float g, float b) {
    const float y = luma709(r, g, b);
    const float cb = (b - y) / 1.8556f;
    const float cr = (r - y) / 1.5748f;
    return Point{cb * kVectorScale, cr * kVectorScale};
}

// Histogram bin of a 0..1 value, matching a point drawn at x = v * 2 - 1 into a 256 pixel wide target.
inline int histogramBin(float v) { return std::min(kHistogramBins - 1, static_cast<int>(clamp01(v) * static_cast<float>(kHistogramBins))); }

// Pixel of an accumulation target `dim` pixels across that a point at normalised coordinate `ndc` lands on.
inline int pixelOf(float ndc, int dim) {
    return std::clamp(static_cast<int>(std::floor((ndc * 0.5f + 0.5f) * static_cast<float>(dim))), 0, dim - 1);
}

inline float intensity(float count, float gain) { return 1.0f - std::exp(-gain * count); }

// Height of a histogram bar (0..1) for a bin holding `count` samples.
inline float histogramHeight(float count) {
    return clamp01(std::log(1.0f + count) / std::log(1.0f + kHistogramFull));
}

// CPU accumulator with the same landing rules as the GPU passes. `rgba` is a `width` x `height` RGBA8 image.
struct Accumulation {
    int width = 0;
    int height = 0;
    std::vector<float> counts;  // width * height * 4 (red, green, blue, luma channels; mode dependent)

    float& at(int x, int y, int channel) { return counts[static_cast<size_t>((y * width + x) * 4 + channel)]; }
    float get(int x, int y, int channel) const { return counts[static_cast<size_t>((y * width + x) * 4 + channel)]; }
};

inline Accumulation accumulate(Mode mode, const uint8_t* rgba, int width, int height) {
    Accumulation a;
    a.width = mode == Mode::Histogram ? kHistogramBins : (mode == Mode::Vector ? kVectorSize : kAccumWidth);
    a.height = mode == Mode::Histogram ? 1 : (mode == Mode::Vector ? kVectorSize : kAccumHeight);
    a.counts.assign(static_cast<size_t>(a.width) * static_cast<size_t>(a.height) * 4, 0.0f);
    for (int py = 0; py < height; ++py) {
        for (int px = 0; px < width; ++px) {
            const uint8_t* p = rgba + (static_cast<size_t>(py) * static_cast<size_t>(width) + static_cast<size_t>(px)) * 4;
            const float c[3] = {static_cast<float>(p[0]) / 255.0f, static_cast<float>(p[1]) / 255.0f, static_cast<float>(p[2]) / 255.0f};
            const float col = (static_cast<float>(px) + 0.5f) / static_cast<float>(width);
            const float y = luma709(c[0], c[1], c[2]);
            switch (mode) {
                case Mode::Waveform: {
                    const Point pt = waveformPoint(col, y);
                    a.at(pixelOf(pt.x, a.width), pixelOf(pt.y, a.height), 3) += 1.0f;
                    break;
                }
                case Mode::Parade:
                    for (int ch = 0; ch < 3; ++ch) {
                        const Point pt = paradePoint(col, c[ch], ch);
                        a.at(pixelOf(pt.x, a.width), pixelOf(pt.y, a.height), ch) += 1.0f;
                    }
                    break;
                case Mode::Vector: {
                    const Point pt = vectorPoint(c[0], c[1], c[2]);
                    a.at(pixelOf(pt.x, a.width), pixelOf(pt.y, a.height), 3) += 1.0f;
                    break;
                }
                case Mode::Histogram:
                    for (int ch = 0; ch < 3; ++ch) a.at(histogramBin(c[ch]), 0, ch) += 1.0f;
                    a.at(histogramBin(y), 0, 3) += 1.0f;
                    break;
            }
        }
    }
    return a;
}

}  // namespace uv::render::scope
