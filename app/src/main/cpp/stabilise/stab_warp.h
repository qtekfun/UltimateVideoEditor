#pragma once

// CPU reference of the stabiliser's pixel mapping (the GLSL in render/shaders.h, effect type 15, mirrors it).
//
// A correction (dx, dy, theta, scale) maps a raw picture position X to where the stabilised picture shows
// it:  Xo = scale * R(theta) * X + (dx, dy). Positions are in height units (1.0 is the layer's height),
// centred on the layer, +x right, +y down, theta clockwise. The compositor needs the inverse: for each
// output pixel, where to read the raw picture.

#include <algorithm>
#include <cmath>

#include "stabilise/similarity.h"

namespace uv::stab {

// Raw-picture position that the stabilised picture shows at `output`.
inline Vec2 stabilisedSourcePosition(const float correction[4], Vec2 output) {
    const float qx = output.x - correction[0], qy = output.y - correction[1];
    const float cs = std::cos(correction[2]), sn = std::sin(correction[2]);
    const float inv = 1.0f / std::max(correction[3], 0.0001f);
    return {(cs * qx + sn * qy) * inv, (-sn * qx + cs * qy) * inv};
}

// Where `raw` ends up in the stabilised picture (the forward mapping).
inline Vec2 stabilisedPosition(const float correction[4], Vec2 raw) {
    const float cs = std::cos(correction[2]), sn = std::sin(correction[2]);
    return {correction[3] * (cs * raw.x - sn * raw.y) + correction[0], correction[3] * (sn * raw.x + cs * raw.y) + correction[1]};
}

}  // namespace uv::stab
