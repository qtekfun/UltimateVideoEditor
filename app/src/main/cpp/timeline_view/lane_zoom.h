#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>

#include "timeline_view/layout.h"

namespace uv::timeline {

// The vertical zoom of the lanes: one scalar that multiplies the default lane height. It is kept in Q12 fixed point so
// repeated pinch steps and a refit after rotation land on exactly the same value; the renderer reads it as a float once per
// frame (inside Layout) and nothing else depends on it. Time is not involved: horizontal positions stay integer frames.
struct LaneScale {
    static constexpr int32_t kOne = 4096;
    static constexpr int32_t kMin = kOne / 2;      // 32 dp lanes: the name, the header strip and the trim handles stay usable
    static constexpr int32_t kMax = kOne * 3;      // 192 dp lanes
    int32_t q = kOne;

    static constexpr int32_t clampQ(int64_t v) { return static_cast<int32_t>(std::clamp<int64_t>(v, kMin, kMax)); }
    static LaneScale fromFloat(float scale) {
        if (!(scale == scale)) return LaneScale{};  // NaN keeps the default
        return LaneScale{clampQ(std::llround(static_cast<double>(scale) * kOne))};
    }
    float toFloat() const { return static_cast<float>(q) / static_cast<float>(kOne); }

    // Multiplies by a pinch factor, clamped. Non-finite or non-positive factors are ignored.
    void zoom(float factor) {
        if (!(factor > 0.0f) || !std::isfinite(factor)) return;
        q = clampQ(std::llround(static_cast<double>(q) * static_cast<double>(factor)));
    }
};

// What fitting the lanes to a panel gives: the scale and whether every lane is visible at it. When the lanes cannot fit even
// at LaneScale::kMin, `fitsAll` is false and the scale is the minimum (the stack then scrolls).
struct LaneFit {
    LaneScale scale;
    bool fitsAll = true;
};

// The largest scale (at most kMax) at which `trackCount` lanes plus the ruler fit in `viewHeight`. An empty timeline or a
// panel with no room keeps the default scale (nothing to fit; a panel that small cannot be sized for anyway).
inline LaneFit fitLaneScale(float density, int trackCount, float viewHeight) {
    LaneFit fit;
    const Layout base = Layout::forDensity(density);  // scale 1: ruler, gap and default lane height in pixels
    if (trackCount <= 0 || !(viewHeight > base.rulerHeight)) return fit;
    const float perLane = (viewHeight - base.rulerHeight) / static_cast<float>(trackCount) - base.trackGap;
    // Round down so the stack never overflows by a fraction of a pixel.
    const int64_t q = static_cast<int64_t>(std::floor(static_cast<double>(perLane) / base.trackHeight * LaneScale::kOne));
    fit.scale.q = LaneScale::clampQ(q);
    fit.fitsAll = q >= LaneScale::kMin;
    return fit;
}

// The vertical scroll that keeps the point of the lane stack under `focusY` (view pixels) where it is when the lanes change
// from `before` to `after`. The point is tracked as a fractional lane position, so the lane (and the part of it) under the
// fingers stays under them. The anchoring inset of each layout (the stack rests on the panel bottom) is taken into account.
// The result is not clamped: Viewport::clamp does that.
inline double anchoredScrollY(const Layout& before, const Layout& after, int trackCount, float viewHeight, double scrollY,
                              float focusY) {
    const float strideBefore = before.trackHeight + before.trackGap;
    const float strideAfter = after.trackHeight + after.trackGap;
    if (!(strideBefore > 0.0f) || !(strideAfter > 0.0f)) return scrollY;
    const Layout a = before.anchoredBottom(trackCount, viewHeight);
    const Layout b = after.anchoredBottom(trackCount, viewHeight);
    // Position inside the lane stack, in lane strides, of the point under the focus.
    const double lanes = (static_cast<double>(focusY) + scrollY - a.rulerHeight - a.inset) / strideBefore;
    return lanes * strideAfter + b.rulerHeight + b.inset - static_cast<double>(focusY);
}

}  // namespace uv::timeline
