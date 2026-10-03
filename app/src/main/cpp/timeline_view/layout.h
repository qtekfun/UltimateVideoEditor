#pragma once

#include <algorithm>

namespace uv::timeline {

// Pixel metrics derived from display density.
struct Layout {
    float rulerHeight;
    float trackHeight;
    float trackGap;
    float handleWidth;  // touch slop at clip edges
    // Empty space between the ruler and the first lane. The lane stack is anchored to the bottom of
    // the panel (LumaFusion style): overlays stack upward from the base, so with few lanes the free
    // room is above them. Zero once the stack fills the panel, where it scrolls instead.
    float inset = 0.0f;

    static Layout forDensity(float density) {
        return {28.0f * density, 64.0f * density, 4.0f * density, 14.0f * density};
    }
    float trackTop(int index) const { return rulerHeight + inset + index * (trackHeight + trackGap); }
    // Height of the lane stack plus ruler, without the anchoring inset: what scrolling is clamped to.
    float contentHeight(int trackCount) const { return rulerHeight + trackCount * (trackHeight + trackGap); }

    // Copy of this layout with the lane stack pushed down so its last lane rests on the panel bottom.
    Layout anchoredBottom(int trackCount, float viewHeight) const {
        Layout l = *this;
        l.inset = std::max(0.0f, viewHeight - contentHeight(trackCount));
        return l;
    }
};

}  // namespace uv::timeline
