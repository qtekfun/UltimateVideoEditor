#pragma once

#include <algorithm>
#include <cstddef>
#include <cstdint>

#include "thumbnail/yuv_tile.h"

namespace uv::thumb {

// Scales an 8-bit RGBA image (a decoded photo) down to one thumbnail tile: centre-cropped to the tile's
// 16:9 and averaged over the source pixels each tile pixel covers. The pixels are read as they come
// from the image decoder (premultiplied alpha), which composites a transparent area over black.
// `strideBytes` is the row pitch of `rgba`. Pure CPU. Returns false for an unusable image.
inline bool rgbaToTile(const uint8_t* rgba, int width, int height, size_t strideBytes, uint16_t* out) {
    if (rgba == nullptr || out == nullptr || width < 1 || height < 1 || strideBytes < static_cast<size_t>(width) * 4) {
        return false;
    }
    double cropW = width;
    double cropH = height;
    if (cropW * kTileHeight > cropH * kTileWidth) {
        cropW = cropH * kTileAspect;  // wider than the tile: trim the sides
    } else {
        cropH = cropW / kTileAspect;  // taller: trim top and bottom
    }
    const double x0 = (width - cropW) / 2.0;
    const double y0 = (height - cropH) / 2.0;
    for (int ty = 0; ty < kTileHeight; ++ty) {
        const int sy0 = std::clamp(static_cast<int>(y0 + ty * cropH / kTileHeight), 0, height - 1);
        const int sy1 = std::clamp(std::max(sy0 + 1, static_cast<int>(y0 + (ty + 1) * cropH / kTileHeight)), sy0 + 1, height);
        for (int tx = 0; tx < kTileWidth; ++tx) {
            const int sx0 = std::clamp(static_cast<int>(x0 + tx * cropW / kTileWidth), 0, width - 1);
            const int sx1 = std::clamp(std::max(sx0 + 1, static_cast<int>(x0 + (tx + 1) * cropW / kTileWidth)), sx0 + 1, width);
            int64_t r = 0, g = 0, b = 0;
            for (int sy = sy0; sy < sy1; ++sy) {
                const uint8_t* row = rgba + static_cast<size_t>(sy) * strideBytes;
                for (int sx = sx0; sx < sx1; ++sx) {
                    r += row[sx * 4];
                    g += row[sx * 4 + 1];
                    b += row[sx * 4 + 2];
                }
            }
            const int64_t n = static_cast<int64_t>(sy1 - sy0) * (sx1 - sx0);
            out[static_cast<size_t>(ty) * kTileWidth + tx] = detail::packRgb565(
                static_cast<float>(r) / static_cast<float>(n), static_cast<float>(g) / static_cast<float>(n),
                static_cast<float>(b) / static_cast<float>(n));
        }
    }
    return true;
}

}  // namespace uv::thumb
