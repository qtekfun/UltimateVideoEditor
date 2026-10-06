#pragma once

// Occlusion for the exporter: a layer that covers the whole canvas with full opacity hides every layer beneath it, so those
// layers need neither a decoded frame nor a draw. Pure and host-testable (tests/export_host_tests.cpp). The test is
// conservative: whatever it cannot prove (rotation, effects, masks, blend modes, translucency, pictures that may carry
// alpha) counts as "not covering", and a not-covering layer is simply drawn as before, so the output is unchanged.

#include <cmath>
#include <cstdint>

#include "core/layer_fx.h"
#include "render/layout_math.h"

namespace uv::encode {

// Tolerance of the coverage test in normalised device units (1.0 = half the canvas). 1e-5 is 0.02 px on a 4K canvas, well below
// what the rasteriser can show, and above the float rounding of the quad map.
constexpr float kCoverEpsilon = 1e-5f;

// True when the quad of a frame shown at `dispW` x `dispH` (after the container rotation) covers every pixel of the
// canvas under `t`: no rotation, full opacity, and the scaled, moved image reaches all four canvas edges.
inline bool coversCanvas(int canvasW, int canvasH, int dispW, int dispH, const render::LayerTransform& t) {
    if (canvasW <= 0 || canvasH <= 0 || dispW <= 0 || dispH <= 0) return false;
    if (!(t.opacity >= 1.0f)) return false;
    if (t.rotationDeg != 0.0f) return false;
    if (!(t.scaleX > 0.0f) || !(t.scaleY > 0.0f)) return false;
    const render::QuadMap m = render::layerQuadMap(canvasW, canvasH, dispW, dispH, t);
    // No rotation: b and c are zero and the quad spans [tx - a, tx + a] x [ty - d, ty + d] in clip space.
    return m.a >= 1.0f - kCoverEpsilon && m.d >= 1.0f - kCoverEpsilon && m.tx - m.a <= -1.0f + kCoverEpsilon &&
           m.tx + m.a >= 1.0f - kCoverEpsilon && m.ty - m.d <= -1.0f + kCoverEpsilon && m.ty + m.d >= 1.0f - kCoverEpsilon;
}

// A layer can only hide others when drawing it leaves nothing of the layers beneath: plain "over" blending (no blend mode, no
// mask) and no effect, since an effect (chroma key, vignette, blur edges, ...) may make pixels transparent.
inline bool opaqueLook(const core::LayerFx& fx) { return fx.blend == core::BlendMode::Normal && fx.mask.shape == 0 && fx.effects.empty(); }

// A layer that is hidden only briefly is not worth skipping: its decoder would be left behind and has to seek when the cover
// goes away. Skip only when the cover stays at least this long (in output frames).
inline int64_t minCullFrames(int32_t fpsNum, int32_t fpsDen) {
    if (fpsNum <= 0 || fpsDen <= 0) return 120;
    return 2 * static_cast<int64_t>(fpsNum) / fpsDen;
}

}  // namespace uv::encode
