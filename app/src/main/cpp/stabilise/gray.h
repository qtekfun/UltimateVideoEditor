#pragma once

// Float grayscale images and pyramids for the classical motion analysis. No dependencies.

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <vector>

namespace uv::stab {

struct Gray {
    int w = 0;
    int h = 0;
    std::vector<float> px;  // row-major, values 0..255

    Gray() = default;
    Gray(int width, int height)
        : w(width), h(height), px(static_cast<size_t>(std::max(0, width)) * static_cast<size_t>(std::max(0, height)), 0.0f) {}

    bool empty() const { return w <= 0 || h <= 0; }
    float at(int x, int y) const {
        x = std::clamp(x, 0, w - 1);
        y = std::clamp(y, 0, h - 1);
        return px[static_cast<size_t>(y) * static_cast<size_t>(w) + static_cast<size_t>(x)];
    }
    float& ref(int x, int y) { return px[static_cast<size_t>(y) * static_cast<size_t>(w) + static_cast<size_t>(x)]; }

    // Bilinear sample at a continuous position (pixel centres are at integer coordinates).
    float sample(float x, float y) const {
        const float fx = std::floor(x), fy = std::floor(y);
        const int ix = static_cast<int>(fx), iy = static_cast<int>(fy);
        const float tx = x - fx, ty = y - fy;
        const float a = at(ix, iy), b = at(ix + 1, iy), c = at(ix, iy + 1), d = at(ix + 1, iy + 1);
        return (a + (b - a) * tx) * (1.0f - ty) + (c + (d - c) * tx) * ty;
    }
};

// Halves the size with a 2x2 box filter.
inline Gray downsample2(const Gray& src) {
    Gray out(std::max(1, src.w / 2), std::max(1, src.h / 2));
    for (int y = 0; y < out.h; ++y) {
        for (int x = 0; x < out.w; ++x) {
            out.ref(x, y) =
                0.25f * (src.at(2 * x, 2 * y) + src.at(2 * x + 1, 2 * y) + src.at(2 * x, 2 * y + 1) + src.at(2 * x + 1, 2 * y + 1));
        }
    }
    return out;
}

// Level 0 is the full image; each next level is half the size. Stops early when the image gets tiny.
inline std::vector<Gray> buildPyramid(const Gray& base, int levels) {
    std::vector<Gray> pyramid;
    pyramid.push_back(base);
    for (int i = 1; i < levels; ++i) {
        const Gray& last = pyramid.back();
        if (last.w < 32 || last.h < 32) break;
        pyramid.push_back(downsample2(last));
    }
    return pyramid;
}

}  // namespace uv::stab
