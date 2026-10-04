#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>

#include "timeline_view/drop_hint.h"
#include "timeline_view/layout.h"
#include "timeline_view/viewport.h"

namespace uv::timeline {

// What the canvas draws while clips are being dragged or trimmed (see TimelineRenderer::setDragOverlay): a thin line at the
// frame an edge snapped to, and a lifted look (soft shadow) on the dragged blocks. Both are decided in Kotlin, which owns
// the editing rules; the canvas only places them. When nothing is being dragged the overlay is empty and costs nothing.
constexpr int64_t kNoSnapGuide = -1;

// The screen rectangle of the snap guide: a vertical line through the lanes (below the ruler, to the bottom of the view)
// centred on `frame`. Whole pixels so the line stays crisp. Invalid when there is no guide, or when it is off screen or
// under the lane header column.
inline HintRect snapGuideRect(int64_t frame, const Viewport& vp, const Layout& layout, float viewWidth, float viewHeight, float lineWidth) {
    HintRect r;
    if (frame < 0) return r;
    const float x = std::floor(static_cast<float>(vp.frameToX(frame)));
    const float x0 = x - std::floor(lineWidth * 0.5f);
    const float x1 = x0 + std::max(1.0f, lineWidth);
    if (x1 <= layout.headerWidth || x0 >= viewWidth) return r;
    r.x0 = std::max(x0, layout.headerWidth);
    r.x1 = std::min(x1, viewWidth);
    r.y0 = layout.rulerHeight;
    r.y1 = viewHeight;
    r.valid = r.x1 > r.x0 && r.y1 > r.y0;
    return r;
}

// The soft shadow under a dragged block: a few nested rounded rectangles, the widest and faintest first. Each layer grows
// the block by `grow` pixels and paints `alpha`; the layers overlap, so the opacity builds up towards the block.
struct ShadowLayer {
    float grow;
    float alpha;
};

constexpr int kShadowLayers = 4;

// Layer `i` (0 = outermost) of a shadow reaching `spread` pixels out with total opacity `peak` next to the block. The alphas
// are chosen so that the layers composite to an even ramp: the opacity once layer k is painted is peak * (k + 1) / kShadowLayers.
inline ShadowLayer shadowLayer(int i, float spread, float peak) {
    const float n = static_cast<float>(kShadowLayers);
    const float k = static_cast<float>(i);
    const float wanted = peak * (k + 1.0f) / n;  // opacity wanted once this layer is painted
    const float before = peak * k / n;           // opacity already there from the wider layers
    const float alpha = before >= 1.0f ? 0.0f : (wanted - before) / (1.0f - before);
    return {spread * (n - k) / n, alpha};
}

}  // namespace uv::timeline
