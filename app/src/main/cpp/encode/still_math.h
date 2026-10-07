#pragma once

// Pure maths of the "save frame as image" mode of the exporter (SPECS.md 5.35): which part of the rendered surface is
// read back and how the rows are put into the caller's buffer. Host-testable, no GL.

#include <cstdint>
#include <cstring>

namespace uv::encode {

// A crop rectangle in image coordinates, y down (row 0 is the top row of the picture).
struct StillCrop {
    int32_t x = 0;
    int32_t y = 0;
    int32_t w = 0;
    int32_t h = 0;
};

constexpr int32_t kMaxStillSide = 8192;  // largest rendered surface edge (the GLES texture limit on current devices)

// True when `crop` is a non-empty rectangle inside a surface of `surfaceW` x `surfaceH` and the surface is within limits.
inline bool validStillCrop(const StillCrop& crop, int32_t surfaceW, int32_t surfaceH) {
    if (surfaceW <= 0 || surfaceH <= 0 || surfaceW > kMaxStillSide || surfaceH > kMaxStillSide) return false;
    if (crop.w <= 0 || crop.h <= 0 || crop.x < 0 || crop.y < 0) return false;
    return static_cast<int64_t>(crop.x) + crop.w <= surfaceW && static_cast<int64_t>(crop.y) + crop.h <= surfaceH;
}

// GL reads rows from the bottom of the framebuffer: the lower-left y of the same rectangle.
inline int32_t glReadY(const StillCrop& crop, int32_t surfaceH) { return surfaceH - crop.y - crop.h; }

inline int64_t stillBytes(const StillCrop& crop) { return static_cast<int64_t>(crop.w) * crop.h * 4; }

// Copies `h` rows of `rowBytes` from `bottomUp` (first row = bottom of the picture, as glReadPixels returns) to `out`
// (first row = top of the picture), reversing their order.
inline void flipRows(const uint8_t* bottomUp, int32_t rowBytes, int32_t h, uint8_t* out) {
    for (int32_t row = 0; row < h; ++row) {
        std::memcpy(out + static_cast<size_t>(row) * static_cast<size_t>(rowBytes),
                    bottomUp + static_cast<size_t>(h - 1 - row) * static_cast<size_t>(rowBytes), static_cast<size_t>(rowBytes));
    }
}

// The picture is composited over opaque black, so alpha is 1 everywhere; a blend-mode pass may leave other values in the
// alpha channel, which a saved image must not carry.
inline void forceOpaque(uint8_t* rgba, int64_t pixels) {
    for (int64_t i = 0; i < pixels; ++i) rgba[i * 4 + 3] = 255;
}

// True when every RGBA pixel equals the first one: a picture nothing was drawn into (or that was drawn flat black) is uniform,
// real footage never is.
inline bool isUniformRgba(const uint8_t* rgba, int64_t pixels) {
    if (pixels <= 1) return true;
    for (int64_t i = 1; i < pixels; ++i) {
        if (std::memcmp(rgba, rgba + i * 4, 4) != 0) return false;
    }
    return true;
}

// The guard of a saved frame: a frame that has visible layers must not come out uniform. A frame without layers (a gap) is
// legitimately black and is never retried or refused here.
inline bool stillLooksEmpty(int64_t layerCount, bool uniform) { return layerCount > 0 && uniform; }

}  // namespace uv::encode
