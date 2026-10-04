#include "timeline_view/hit_test.h"

#include <algorithm>
#include <cmath>

namespace uv::timeline {

HitResult hitTest(const TimelineSnapshot& snap, const Viewport& vp, const Layout& layout, float x, float y,
                  int64_t playheadFrame) {
    HitResult res;
    res.frame = std::max<int64_t>(0, vp.xToFrame(x));
    if (y < layout.rulerHeight) {
        res.kind = HitKind::Ruler;
        if (playheadFrame >= 0 && std::abs(x - vp.frameToX(playheadFrame)) <= layout.handleWidth * 1.5) {
            res.kind = HitKind::Playhead;
        }
        return res;
    }
    const double yy = y + vp.scrollY - layout.rulerHeight - layout.inset;  // y inside the scrolled track area
    const double stride = layout.trackHeight + layout.trackGap;
    if (yy < 0) {
        res.kind = HitKind::AboveLanes;
        return res;
    }
    const int track = static_cast<int>(yy / stride);
    if (track < 0 || track >= static_cast<int>(snap.tracks.size())) return res;
    if (yy - track * stride > layout.trackHeight) return res;  // inside the gap
    res.trackIndex = track;
    res.kind = HitKind::EmptyTrack;

    // Last matching clip wins so a later-drawn clip is picked first.
    for (auto it = snap.clips.rbegin(); it != snap.clips.rend(); ++it) {
        if (it->trackIndex != track) continue;
        const double left = vp.frameToX(it->startFrame);
        const double right = vp.frameToX(it->startFrame + it->durationFrames);
        if (x < left - layout.handleWidth * 0.5 || x > right + layout.handleWidth * 0.5) continue;
        res.clipKey = it->clipKey;
        // Handles only count when the clip is wide enough to leave a body to grab.
        const double handle = std::min<double>(layout.handleWidth, (right - left) / 3.0);
        if (x <= left + handle) res.kind = HitKind::ClipLeftEdge;
        else if (x >= right - handle) res.kind = HitKind::ClipRightEdge;
        else res.kind = HitKind::Clip;
        return res;
    }
    return res;
}

std::vector<int64_t> clipsInRect(const TimelineSnapshot& snap, const Viewport& vp, const Layout& layout, float x0, float y0, float x1,
                                 float y1) {
    if (x0 > x1) std::swap(x0, x1);
    if (y0 > y1) std::swap(y0, y1);
    y0 = std::max(y0, layout.rulerHeight);
    std::vector<int64_t> keys;
    if (y1 <= y0) return keys;
    for (const ClipSnapshot& c : snap.clips) {
        const double top = layout.trackTop(c.trackIndex) - vp.scrollY;
        const double bottom = top + layout.trackHeight;
        const double left = vp.frameToX(c.startFrame);
        const double right = vp.frameToX(c.startFrame + c.durationFrames);
        if (left < x1 && right > x0 && top < y1 && bottom > y0) keys.push_back(c.clipKey);
    }
    return keys;
}

}  // namespace uv::timeline
