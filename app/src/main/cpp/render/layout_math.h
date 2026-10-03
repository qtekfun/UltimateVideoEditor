#pragma once

#include <cmath>
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

// ----------------------------------------------------------------------------------------------
// Multi-layer placement. The project canvas (project resolution) is letterboxed into the surface;
// every layer is a quad inside it. Keep in step with ClipTransform on the Kotlin side:
//   1. the frame, as displayed (after the container rotation), is fitted "contain" into the canvas;
//   2. it is scaled by (scaleX, scaleY) about its centre, rotated clockwise by rotationDeg, then
//      its centre is moved by (posX, posY) canvas pixels (+x right, +y down).
// ----------------------------------------------------------------------------------------------

struct LayerTransform {
    float posX = 0.0f;
    float posY = 0.0f;
    float scaleX = 1.0f;
    float scaleY = 1.0f;
    float rotationDeg = 0.0f;
    float opacity = 1.0f;
};

// Affine map from quad coordinates p in [-1,1]^2 (p.y = +1 is the top of the image) to clip space:
//   ndc.x = a * p.x + b * p.y + tx
//   ndc.y = c * p.x + d * p.y + ty
struct QuadMap {
    float a = 1.0f;
    float b = 0.0f;
    float tx = 0.0f;
    float c = 0.0f;
    float d = 1.0f;
    float ty = 0.0f;
};

// `dispW` x `dispH` is the frame size as displayed. Degenerate sizes give the identity (full canvas).
inline QuadMap layerQuadMap(int canvasW, int canvasH, int dispW, int dispH, const LayerTransform& t) {
    if (canvasW <= 0 || canvasH <= 0 || dispW <= 0 || dispH <= 0) return QuadMap{};
    const double cw = canvasW;
    const double ch = canvasH;
    const double fitX = cw / dispW;
    const double fitY = ch / dispH;
    const double fit = fitX < fitY ? fitX : fitY;
    const double hx = dispW * fit * t.scaleX * 0.5;
    const double hy = dispH * fit * t.scaleY * 0.5;
    const double rad = static_cast<double>(t.rotationDeg) * 3.14159265358979323846 / 180.0;
    const double cs = std::cos(rad);
    const double sn = std::sin(rad);
    QuadMap m;
    m.a = static_cast<float>(2.0 * cs * hx / cw);
    m.b = static_cast<float>(2.0 * sn * hy / cw);
    m.tx = static_cast<float>(2.0 * t.posX / cw);
    m.c = static_cast<float>(-2.0 * sn * hx / ch);
    m.d = static_cast<float>(2.0 * cs * hy / ch);
    m.ty = static_cast<float>(-2.0 * t.posY / ch);
    return m;
}

inline Uv applyQuadMap(const QuadMap& m, Uv p) {
    return Uv{m.a * p.u + m.b * p.v + m.tx, m.c * p.u + m.d * p.v + m.ty};
}

// Column-major 3x3 for glUniformMatrix3fv, acting on (p.x, p.y, 1).
inline void quadMapToMat3(const QuadMap& m, float out[9]) {
    out[0] = m.a;
    out[1] = m.c;
    out[2] = 0.0f;
    out[3] = m.b;
    out[4] = m.d;
    out[5] = 0.0f;
    out[6] = m.tx;
    out[7] = m.ty;
    out[8] = 1.0f;
}

// Opacity is a plain 0..1 multiplier; anything outside (or NaN) is clamped.
inline float clampOpacity(float v) { return !(v > 0.0f) ? 0.0f : (v > 1.0f ? 1.0f : v); }

}  // namespace uv::render
