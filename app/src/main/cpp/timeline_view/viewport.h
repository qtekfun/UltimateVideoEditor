#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>

namespace uv::timeline {

// Horizontal scroll/zoom state. Time stays integer frames; pixels are the only floating domain.
struct Viewport {
    static constexpr double kMinPxPerFrame = 0.02;
    static constexpr double kMaxPxPerFrame = 96.0;

    double scrollX = 0.0;  // pixels from timeline frame 0 to the left edge of the view
    double scrollY = 0.0;  // pixels of vertical scroll across tracks
    double pxPerFrame = 4.0;
    double viewWidth = 1.0;

    double frameToX(int64_t frame) const { return static_cast<double>(frame) * pxPerFrame - scrollX; }
    int64_t xToFrame(double x) const {
        return static_cast<int64_t>(std::floor((x + scrollX) / pxPerFrame));
    }

    // Keeps the frame under focusX fixed while scaling.
    void zoomAt(double factor, double focusX) {
        const double next = std::clamp(pxPerFrame * factor, kMinPxPerFrame, kMaxPxPerFrame);
        const double frameAtFocus = (focusX + scrollX) / pxPerFrame;
        pxPerFrame = next;
        scrollX = frameAtFocus * pxPerFrame - focusX;
    }

    // Zooms so frames [0, endFrame) span the view width with a small margin, and scrolls to the
    // start. An empty timeline keeps the current zoom.
    void fitTo(int64_t endFrame) {
        if (endFrame <= 0 || viewWidth <= 1.0) return;
        pxPerFrame = std::clamp(viewWidth / (static_cast<double>(endFrame) * kFitMargin), kMinPxPerFrame, kMaxPxPerFrame);
        scrollX = 0.0;
    }

    static constexpr double kFitMargin = 1.03;

    // Where a frame lands, as a fraction of the view width from the left, when ensureVisible()
    // has to scroll to it; the view is only moved if the frame is outside the inner band.
    static constexpr double kFollowEdge = 0.1;

    // Brings `frame` into view by paging: if it is outside the band [10%, 90%] of the view the
    // timeline jumps so that it sits 10% from the left, and otherwise stays put. Scrolls only; the
    // caller clamps. Returns true if it moved.
    bool ensureVisible(int64_t frame) {
        const double x = frameToX(frame);
        if (x >= viewWidth * kFollowEdge && x <= viewWidth * (1.0 - kFollowEdge)) return false;
        scrollX = std::max(0.0, static_cast<double>(frame) * pxPerFrame - viewWidth * kFollowEdge);
        return true;
    }

    void clamp(int64_t endFrame, double contentHeight, double viewHeight) {
        // Allow scrolling half a screen past the last clip so the end can be edited.
        const double maxX = std::max(0.0, static_cast<double>(endFrame) * pxPerFrame - viewWidth * 0.5);
        scrollX = std::clamp(scrollX, 0.0, maxX);
        scrollY = std::clamp(scrollY, 0.0, std::max(0.0, contentHeight - viewHeight));
    }
};

}  // namespace uv::timeline
