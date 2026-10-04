#pragma once

#include <cstdint>
#include <vector>

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
    LaneHeader = 9,  // the header column at the left edge of a lane (only when the layout has one)
    Marker = 10,     // a ruler marker; `clipKey` holds its index in the snapshot's marker list (`frame` is still the frame under the finger)
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

// Keys of the clips whose block on screen intersects the rectangle (x0,y0)-(x1,y1), in snapshot order. The
// corners may come in any order; the ruler never counts, so a rectangle dragged up into it still selects the
// lanes below. Used by the marquee selection.
std::vector<int64_t> clipsInRect(const TimelineSnapshot& snap, const Viewport& vp, const Layout& layout, float x0, float y0, float x1,
                                 float y1);

}  // namespace uv::timeline
