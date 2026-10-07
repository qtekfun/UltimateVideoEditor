#include "timeline_view/hit_test.h"

#include <algorithm>
#include <cmath>

#include "timeline_view/audio_shaping.h"

namespace uv::timeline {

HitResult hitTest(const TimelineSnapshot& snap, const Viewport& vp, const Layout& layout, float x, float y,
                  int64_t playheadFrame) {
    HitResult res;
    res.frame = std::max<int64_t>(0, vp.xToFrame(x));
    if (y < layout.rulerHeight) {
        res.kind = HitKind::Ruler;
        double playheadDistance = 1.0e18;
        if (playheadFrame >= 0) {
            playheadDistance = std::abs(x - vp.frameToX(playheadFrame));
            if (playheadDistance <= layout.handleWidth * 1.5) res.kind = HitKind::Playhead;
        }
        // A marker takes the touch when it is within its (finger sized) target and not strictly farther than the
        // playhead handle, so a marker just dropped under the playhead can still be tapped.
        if (layout.markerHitHalf > 0.0f) {
            double best = layout.markerHitHalf + 1.0;
            int bestIndex = -1;
            for (size_t i = 0; i < snap.markers.size(); ++i) {
                const double d = std::abs(x - vp.frameToX(snap.markers[i].frame));
                if (d <= layout.markerHitHalf && d < best) {
                    best = d;
                    bestIndex = static_cast<int>(i);
                }
            }
            if (bestIndex >= 0 && (res.kind != HitKind::Playhead || best <= playheadDistance)) {
                res.kind = HitKind::Marker;
                res.clipKey = bestIndex;  // `frame` stays the frame under the finger, so a drag can follow it
            }
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
    const float laneTop = static_cast<float>(layout.trackTop(track) - vp.scrollY);
    res.hasDb = true;
    res.db = yToDb(y, laneTop, layout.trackHeight);
    // The header column sits over the lane's left edge and takes the touch before any clip under it.
    if (layout.headerWidth > 0.0f && x < layout.headerWidth) {
        res.kind = HitKind::LaneHeader;
        return res;
    }

    // Last matching clip wins so a later-drawn clip is picked first.
    for (auto it = snap.clips.rbegin(); it != snap.clips.rend(); ++it) {
        if (it->trackIndex != track) continue;
        const double left = vp.frameToX(it->startFrame);
        const double right = vp.frameToX(it->startFrame + it->durationFrames);
        if (x < left - layout.handleWidth * 0.5 || x > right + layout.handleWidth * 0.5) continue;
        res.clipKey = it->clipKey;
        // The selected audio clip's volume points and fade handles come before its edges: the points first (they
        // sit on the body), then the circles in the top corners.
        if (const ShapingSnapshot* shaping = snap.shapingOf(it->clipKey); shaping != nullptr && shaping->editable()) {
            const int point = pointAt(*shaping, it->startFrame, [&](int64_t f) { return vp.frameToX(f); }, laneTop,
                                      layout.trackHeight, x, y, layout.handleWidth * 1.4f);
            if (point >= 0) {
                res.kind = HitKind::VolumePoint;
                res.index = point;
                return res;
            }
            const FadeHandles h = fadeHandles(left, right, vp.pxPerFrame, shaping->fadeInFrames, shaping->fadeOutFrames, laneTop, layout);
            const auto near = [&](float cx) { return (x - cx) * (x - cx) + (y - h.y) * (y - h.y) <= h.hitR * h.hitR; };
            const bool nearIn = near(h.inX), nearOut = near(h.outX);
            if (nearIn || nearOut) {
                // Overlapping circles (a short clip): the one whose centre is closer wins.
                const bool in = nearIn && (!nearOut || std::abs(x - h.inX) <= std::abs(x - h.outX));
                res.kind = in ? HitKind::FadeInHandle : HitKind::FadeOutHandle;
                return res;
            }
        }
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
