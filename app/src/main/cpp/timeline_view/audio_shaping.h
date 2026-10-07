#pragma once

// How the canvas shows and hit-tests the sound shaping of a clip: its fade-in / fade-out ramps and its volume curve
// (the keyframed gain, drawn as a line over the waveform). Pure geometry, no GL, so the host tests cover it. The
// editing itself (what a drag changes) lives in Kotlin (ui/editor/AudioShapeGesture.kt); this file only places things.

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>

#include "timeline_view/layout.h"

namespace uv::timeline {

// Flags of a ShapingSnapshot: bits 0..1 the fade shape (core::FadeShape), bit 2 = the handles and points can be edited.
constexpr int32_t kShapingShapeMask = 3;
constexpr int32_t kShapingEditable = 4;

// A point of the volume curve: `frame` frames after the clip's start, `db` the gain there.
struct ShapingPoint {
    int64_t frame;
    float db;
};

struct ShapingSnapshot {
    int64_t clipKey = -1;
    int32_t fadeInFrames = 0;
    int32_t fadeOutFrames = 0;
    int32_t flags = 0;
    float baseDb = 0.0f;  // the clip's static gain: where the curve line sits when it has no points
    std::vector<ShapingPoint> points;  // frames strictly increasing

    int32_t shape() const { return flags & kShapingShapeMask; }
    bool editable() const { return (flags & kShapingEditable) != 0; }
};

// The volume curve is drawn on a scale of kEnvMaxDb at the top to kEnvMinDb at the bottom, linear in dB.
constexpr float kEnvMaxDb = 12.0f;
constexpr float kEnvMinDb = -48.0f;

// The part of a lane (laneTop, laneHeight) the curve uses: clear of the name strip at the top and of the bottom edge.
inline float shapeAreaTop(float laneTop, float laneHeight) { return laneTop + 0.18f * laneHeight; }
inline float shapeAreaBottom(float laneTop, float laneHeight) { return laneTop + 0.92f * laneHeight; }

inline float dbToY(float db, float laneTop, float laneHeight) {
    const float top = shapeAreaTop(laneTop, laneHeight), bottom = shapeAreaBottom(laneTop, laneHeight);
    const float clamped = std::min(kEnvMaxDb, std::max(kEnvMinDb, db));
    return top + (kEnvMaxDb - clamped) / (kEnvMaxDb - kEnvMinDb) * (bottom - top);
}

// The gain at height y, clamped to the scale and rounded to 0.1 dB (the unit the editor stores).
inline float yToDb(float y, float laneTop, float laneHeight) {
    const float top = shapeAreaTop(laneTop, laneHeight), bottom = shapeAreaBottom(laneTop, laneHeight);
    const float f = std::min(1.0f, std::max(0.0f, (y - top) / (bottom - top)));
    const float db = kEnvMaxDb - f * (kEnvMaxDb - kEnvMinDb);
    return std::round(db * 10.0f) / 10.0f;
}

// Where the two fade handles are drawn and grabbed. A handle with no fade rests just inside its corner. All in panel
// pixels; the circles have draw radius `drawR` and are grabbed within `hitR`.
struct FadeHandles {
    float inX, outX, y, drawR, hitR;
};

inline FadeHandles fadeHandles(double clipX0, double clipX1, double pxPerFrame, int32_t fadeIn, int32_t fadeOut, float laneTop,
                               const Layout& layout) {
    FadeHandles h{};
    h.drawR = layout.handleWidth * 0.5f;
    h.hitR = layout.handleWidth * 1.4f;
    h.y = laneTop + layout.handleWidth * 0.8f;
    const float inset = h.drawR + layout.handleWidth * 0.15f;
    h.inX = static_cast<float>(std::max(clipX0 + inset, clipX0 + fadeIn * pxPerFrame));
    h.outX = static_cast<float>(std::min(clipX1 - inset, clipX1 - fadeOut * pxPerFrame));
    return h;
}

// Index of the point of `s` within `radius` pixels of (x, y) that is nearest to it, or -1. `laneTop`/`laneHeight` place the
// curve; `frameToX` maps a timeline frame to x.
template <typename FrameToX>
int pointAt(const ShapingSnapshot& s, int64_t clipStart, FrameToX frameToX, float laneTop, float laneHeight, float x, float y,
            float radius) {
    int best = -1;
    float bestD2 = radius * radius;
    for (size_t i = 0; i < s.points.size(); ++i) {
        const float px = static_cast<float>(frameToX(clipStart + s.points[i].frame));
        const float py = dbToY(s.points[i].db, laneTop, laneHeight);
        const float d2 = (px - x) * (px - x) + (py - y) * (py - y);
        if (d2 <= bestD2) {
            bestD2 = d2;
            best = static_cast<int>(i);
        }
    }
    return best;
}

}  // namespace uv::timeline
