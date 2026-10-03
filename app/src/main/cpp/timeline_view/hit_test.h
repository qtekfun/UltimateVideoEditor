#pragma once

#include <cstdint>

#include "timeline_view/layout.h"
#include "timeline_view/timeline_snapshot.h"
#include "timeline_view/viewport.h"

namespace uv::timeline {

enum class HitKind : int32_t {
    None = 0,
    Ruler = 1,
    Clip = 2,
    ClipLeftEdge = 3,
    ClipRightEdge = 4,
    EmptyTrack = 5,
};

struct HitResult {
    HitKind kind = HitKind::None;
    int32_t trackIndex = -1;
    int64_t clipKey = -1;
    int64_t frame = 0;  // timeline frame under the touch point
};

HitResult hitTest(const TimelineSnapshot& snap, const Viewport& vp, const Layout& layout, float x, float y);

}  // namespace uv::timeline
