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
    // Width of the lane header column drawn over the left edge of every lane (name tab, mute/solo marks) and the
    // area that long-pressing starts a lane drag from; 0 hides the headers (hit tests in the tests use 0).
    float headerWidth = 0.0f;
    // Half the width of the touch target of a ruler marker (a marker is easier to hit than its 1.5dp line is wide).
    float markerHitHalf = 0.0f;

    // [laneScale] stretches only the lanes (the lane height preset times the pinch zoom, see lane_zoom.h); the ruler, gaps and
    // touch slop keep their size. It is clamped to [kMinLaneScale, kMaxLaneScale] so a bad value cannot hide the lanes.
    static constexpr float kMinLaneScale = 0.5f;
    static constexpr float kMaxLaneScale = 3.0f;
    static Layout forDensity(float density, float laneScale = 1.0f) {
        const float scale = std::min(kMaxLaneScale, std::max(kMinLaneScale, laneScale));
        Layout l{28.0f * density, 64.0f * density * scale, 4.0f * density, 14.0f * density};
        l.markerHitHalf = 20.0f * density;
        return l;
    }
    // Copy of this layout with a lane header column of the given width.
    Layout withHeaders(float width) const {
        Layout l = *this;
        l.headerWidth = std::max(0.0f, width);
        return l;
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
