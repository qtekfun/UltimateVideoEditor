#pragma once

// Luma plane of a decoded YUV frame, rotated upright and scaled down for motion analysis. Pure CPU and
// pointer-based so it runs in host tests; the Android decoder wrapper hands it codec output buffers.

#include <algorithm>
#include <cmath>
#include <cstdint>

#include "stabilise/gray.h"
#include "thumbnail/yuv_tile.h"

namespace uv::stab {

// Fills `out` with the upright luma of `frame` (rotated clockwise by `rotationDegrees`, a multiple of 90, as in
// the container's rotation metadata) scaled so the long side is at most `maxDimension` (never enlarged). Each
// output pixel averages up to 4x4 samples of its source block, so it is cheap even for 4K sources. Returns
// false for an unusable frame.
inline bool lumaToGray(const thumb::YuvFrame& f, int rotationDegrees, int maxDimension, Gray* out) {
    if (f.y == nullptr || out == nullptr || f.cropWidth < 2 || f.cropHeight < 2 || maxDimension < 16) return false;
    if (f.sampleBytes != 1 && f.sampleBytes != 2) return false;
    const int rot = ((rotationDegrees % 360) + 360) % 360;
    if (rot % 90 != 0) return false;
    const bool swap = rot == 90 || rot == 270;
    const int dispW = swap ? f.cropHeight : f.cropWidth;
    const int dispH = swap ? f.cropWidth : f.cropHeight;
    const double scale = std::min(1.0, static_cast<double>(maxDimension) / static_cast<double>(std::max(dispW, dispH)));
    const int outW = std::max(16, static_cast<int>(std::lround(dispW * scale)));
    const int outH = std::max(16, static_cast<int>(std::lround(dispH * scale)));
    const double stepX = static_cast<double>(dispW) / outW, stepY = static_cast<double>(dispH) / outH;
    const int taps = std::clamp(static_cast<int>(std::ceil(std::max(stepX, stepY))), 1, 4);
    const size_t high = static_cast<size_t>(f.sampleBytes - 1);

    *out = Gray(outW, outH);
    for (int oy = 0; oy < outH; ++oy) {
        for (int ox = 0; ox < outW; ++ox) {
            float sum = 0.0f;
            for (int ty = 0; ty < taps; ++ty) {
                for (int tx = 0; tx < taps; ++tx) {
                    const int ix = std::clamp(static_cast<int>((ox + (tx + 0.5) / taps) * stepX), 0, dispW - 1);
                    const int iy = std::clamp(static_cast<int>((oy + (ty + 0.5) / taps) * stepY), 0, dispH - 1);
                    int sx, sy;
                    switch (rot) {
                        case 90: sx = iy; sy = f.cropHeight - 1 - ix; break;
                        case 180: sx = f.cropWidth - 1 - ix; sy = f.cropHeight - 1 - iy; break;
                        case 270: sx = f.cropWidth - 1 - iy; sy = ix; break;
                        default: sx = ix; sy = iy; break;
                    }
                    sx += f.cropLeft;
                    sy += f.cropTop;
                    sum += static_cast<float>(
                        f.y[static_cast<size_t>(sy) * static_cast<size_t>(f.yRowStride) + static_cast<size_t>(sx) * static_cast<size_t>(f.sampleBytes) + high]);
                }
            }
            out->ref(ox, oy) = sum / static_cast<float>(taps * taps);
        }
    }
    return true;
}

}  // namespace uv::stab
