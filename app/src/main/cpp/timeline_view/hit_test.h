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
    Playhead = 6,  // the playhead handle in the ruler
    Outside = 8,      // the finger left the panel (set by the renderer, which knows the panel size)
    AboveLanes = 7,  // below the ruler but above the first lane (room left by the bottom-anchored stack)
};

struct HitResult {
    HitKind kind = HitKind::None;
    int32_t trackIndex = -1;
    int64_t clipKey = -1;
    int64_t frame = 0;  // timeline frame under the touch point
};

// `playheadFrame` < 0 means there is no playhead to grab. The playhead is only grabbable in the
// ruler: below it, a playhead sitting on a clip edge (common after a split) must not steal the
// clip's trim handle.
HitResult hitTest(const TimelineSnapshot& snap, const Viewport& vp, const Layout& layout, float x, float y,
                  int64_t playheadFrame = -1);

}  // namespace uv::timeline
