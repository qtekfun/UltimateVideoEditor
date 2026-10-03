#pragma once

#include <algorithm>
#include <cstdint>

#include "timeline_view/layout.h"
#include "timeline_view/viewport.h"

namespace uv::timeline {

// What releasing a dragged clip would do, drawn live over the timeline. Decided in Kotlin by one
// function that also runs the drop, so what is shown is what happens.
enum class DropHintKind : int32_t {
    None = 0,
    Insert = 1,     // a bar at the junction where a gap opens
    Overwrite = 2,  // tint over the frames that will be replaced
    NewLane = 3,    // placeholder for a new overlay lane
    Cancel = 4,     // the clip goes back where it was
};

struct DropHint {
    DropHintKind kind = DropHintKind::None;
    int32_t trackIndex = -1;  // lane in the displayed snapshot; unused for Cancel
    int64_t startFrame = 0;
    int64_t endFrame = 0;  // equal to startFrame for Insert
};

struct HintRect {
    float x0 = 0, y0 = 0, x1 = 0, y1 = 0;
    bool valid = false;
};

// Screen rectangle of the indicator. `barWidth` is the width of the insertion bar in pixels.
inline HintRect dropHintRect(const DropHint& hint, const Viewport& vp, const Layout& layout, float viewWidth, float viewHeight,
                             int trackCount, float barWidth) {
    HintRect r;
    if (hint.kind == DropHintKind::None) return r;
    if (hint.kind == DropHintKind::Cancel) {
        r = {0.0f, layout.rulerHeight, viewWidth, viewHeight, true};
        return r;
    }
    if (hint.trackIndex < 0 || hint.trackIndex >= trackCount) return r;
    const float top = layout.trackTop(hint.trackIndex) - static_cast<float>(vp.scrollY);
    r.y0 = top;
    r.y1 = top + layout.trackHeight;
    switch (hint.kind) {
        case DropHintKind::Insert: {
            const float x = static_cast<float>(vp.frameToX(hint.startFrame));
            r.x0 = x - barWidth * 0.5f;
            r.x1 = x + barWidth * 0.5f;
            break;
        }
        case DropHintKind::Overwrite: {
            const float a = static_cast<float>(vp.frameToX(hint.startFrame));
            const float b = static_cast<float>(vp.frameToX(hint.endFrame));
            r.x0 = a;
            r.x1 = std::max(b, a + 2.0f * barWidth);
            break;
        }
        case DropHintKind::NewLane:
            r.x0 = 0.0f;
            r.x1 = viewWidth;
            break;
        default:
            return HintRect{};
    }
    r.valid = true;
    return r;
}

}  // namespace uv::timeline
