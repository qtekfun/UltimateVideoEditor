#pragma once

#include <algorithm>
#include <cstdint>

#include "thumbnail/tile_math.h"

namespace uv::thumb {

// A decoded YUV 4:2:0 frame as plane pointers (planar or semi-planar, as codec output buffers lay it out).
struct YuvFrame {
    const uint8_t* y = nullptr;
    const uint8_t* u = nullptr;
    const uint8_t* v = nullptr;
    // Strides are in bytes. Samples are 8-bit, or 16-bit little-endian words with the value in the
    // high bits (P010) when sampleBytes is 2; only the high byte is used.
    int yRowStride = 0;
    int uvRowStride = 0;
    int uvPixelStride = 1;
    int sampleBytes = 1;
    // Visible region inside the planes (the codec may pad the buffer).
    int cropLeft = 0, cropTop = 0, cropWidth = 0, cropHeight = 0;
};

namespace detail {

inline uint16_t packRgb565(float r, float g, float b) {
    const auto q = [](float c, int bits) {
        const int maxv = (1 << bits) - 1;
        return std::clamp(static_cast<int>(c * static_cast<float>(maxv) / 255.0f + 0.5f), 0, maxv);
    };
    return static_cast<uint16_t>((q(r, 5) << 11) | (q(g, 6) << 5) | q(b, 5));
}

}  // namespace detail

// Scales a frame down to one thumbnail tile: rotated upright by `rotationDegrees` (0/90/180/270,
// clockwise, as in the container's rotation metadata), centre-cropped to 16:9, converted from
// BT.709 limited-range YUV to RGB565 and averaged over 2x2 samples per pixel. Pure CPU, tiny output,
// so it is cheap even for 4K sources. Returns false for an unusable frame.
inline bool yuvToTile(const YuvFrame& f, int rotationDegrees, uint16_t* out) {
    if (f.y == nullptr || f.u == nullptr || f.v == nullptr || out == nullptr) return false;
    if (f.cropWidth < 2 || f.cropHeight < 2) return false;
    if (f.sampleBytes != 1 && f.sampleBytes != 2) return false;
    const int rot = ((rotationDegrees % 360) + 360) % 360;
    if (rot % 90 != 0) return false;

    const bool swap = rot == 90 || rot == 270;
    const int dispW = swap ? f.cropHeight : f.cropWidth;
    const int dispH = swap ? f.cropWidth : f.cropHeight;

    // Centre crop of the upright image to the tile aspect.
    double cw, ch;
    if (static_cast<int64_t>(dispW) * kTileHeight > static_cast<int64_t>(dispH) * kTileWidth) {
        ch = dispH;
        cw = dispH * kTileAspect;
    } else {
        cw = dispW;
        ch = dispW / kTileAspect;
    }
    const double ox = (dispW - cw) * 0.5, oy = (dispH - ch) * 0.5;

    const auto sample = [&](double dx, double dy, float* yy, float* uu, float* vv) {
        const int ix = std::clamp(static_cast<int>(dx), 0, dispW - 1);
        const int iy = std::clamp(static_cast<int>(dy), 0, dispH - 1);
        int sx, sy;
        switch (rot) {
            case 90: sx = iy; sy = f.cropHeight - 1 - ix; break;
            case 180: sx = f.cropWidth - 1 - ix; sy = f.cropHeight - 1 - iy; break;
            case 270: sx = f.cropWidth - 1 - iy; sy = ix; break;
            default: sx = ix; sy = iy; break;
        }
        sx += f.cropLeft;
        sy += f.cropTop;
        const size_t high = static_cast<size_t>(f.sampleBytes - 1);  // index of the most significant byte
        *yy = static_cast<float>(f.y[static_cast<size_t>(sy) * f.yRowStride + static_cast<size_t>(sx) * f.sampleBytes + high]);
        const size_t co = static_cast<size_t>(sy / 2) * f.uvRowStride + static_cast<size_t>(sx / 2) * f.uvPixelStride + high;
        *uu = static_cast<float>(f.u[co]);
        *vv = static_cast<float>(f.v[co]);
    };

    const double stepX = cw / kTileWidth, stepY = ch / kTileHeight;
    for (int ty = 0; ty < kTileHeight; ++ty) {
        for (int tx = 0; tx < kTileWidth; ++tx) {
            float ySum = 0.0f, u = 0.0f, v = 0.0f;
            for (int sy = 0; sy < 2; ++sy) {
                for (int sx = 0; sx < 2; ++sx) {
                    float yy, uu, vv;
                    sample(ox + (tx + 0.25 + 0.5 * sx) * stepX, oy + (ty + 0.25 + 0.5 * sy) * stepY, &yy, &uu, &vv);
                    ySum += yy;
                    u += uu;
                    v += vv;
                }
            }
            const float yl = (ySum * 0.25f - 16.0f) * 1.164383f;
            const float cu = u * 0.25f - 128.0f, cv = v * 0.25f - 128.0f;
            out[static_cast<size_t>(ty) * kTileWidth + tx] = detail::packRgb565(
                yl + 1.792741f * cv, yl - 0.213249f * cu - 0.532909f * cv, yl + 2.112402f * cu);
        }
    }
    return true;
}

}  // namespace uv::thumb
