#pragma once

#include <cstdint>

// Frame placement maths shared by the compositor and the host tests. The GLSL in shaders.h
// mirrors rotateUv(); change both together.

namespace uv::render {

struct Viewport {
    int x = 0;
    int y = 0;
    int w = 0;
    int h = 0;
    bool operator==(const Viewport& o) const { return x == o.x && y == o.y && w == o.w && h == o.h; }
};

struct Uv {
    float u;
    float v;
};

// Quarter turns clockwise (0..3) for a MediaFormat rotation in degrees; anything off-grid maps to 0.
inline int quarterTurns(int degrees) {
    const int d = ((degrees % 360) + 360) % 360;
    return (d % 90 == 0) ? d / 90 : 0;
}

// Size of the frame as shown, after the clockwise rotation.
inline void displaySize(int width, int height, int turns, int* outW, int* outH) {
    const bool swap = (turns & 1) != 0;
    *outW = swap ? height : width;
    *outH = swap ? width : height;
}

// Largest rectangle with the display aspect that fits the surface, centred (integer maths only).
inline Viewport letterbox(int displayW, int displayH, int surfaceW, int surfaceH) {
    if (displayW <= 0 || displayH <= 0 || surfaceW <= 0 || surfaceH <= 0) return Viewport{0, 0, surfaceW, surfaceH};
    Viewport v{0, 0, surfaceW, surfaceH};
    // Compare displayW/displayH with surfaceW/surfaceH without division.
    if (static_cast<int64_t>(displayW) * surfaceH > static_cast<int64_t>(surfaceW) * displayH) {
        v.h = static_cast<int>(static_cast<int64_t>(surfaceW) * displayH / displayW);
    } else {
        v.w = static_cast<int>(static_cast<int64_t>(surfaceH) * displayW / displayH);
    }
    v.x = (surfaceW - v.w) / 2;
    v.y = (surfaceH - v.h) / 2;
    return v;
}

// Maps a point of the shown (rotated) image, uv in [0,1] with y down, to the source frame uv.
inline Uv rotateUv(Uv out, int turns) {
    switch (turns & 3) {
        case 1: return Uv{out.v, 1.0f - out.u};         // 90 clockwise
        case 2: return Uv{1.0f - out.u, 1.0f - out.v};  // 180
        case 3: return Uv{1.0f - out.v, out.u};         // 270 clockwise
        default: return out;
    }
}

}  // namespace uv::render
