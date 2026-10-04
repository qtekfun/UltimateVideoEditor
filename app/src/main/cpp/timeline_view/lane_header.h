#pragma once

#include <string>

#include "timeline_view/timeline_snapshot.h"

namespace uv::timeline {

// Width of the lane header column, in dp (scaled by the display density in the renderer).
constexpr float kLaneHeaderDp = 22.0f;

// Index of the base lane (the lowest video lane, i.e. the last video track in display order), or -1.
inline int baseLaneIndex(const TimelineSnapshot& snap) {
    for (int i = static_cast<int>(snap.tracks.size()) - 1; i >= 0; --i) {
        if (snap.tracks[static_cast<size_t>(i)].type == TrackType::Video) return i;
    }
    return -1;
}

// The short name written in a lane header, matching the editor's lane names: video lanes count up from the base
// (V1 is the base, overlays above it are V2, V3...), audio and title lanes count down from the top (A1, A2 / T1, T2).
inline std::string laneLabel(const TimelineSnapshot& snap, int index) {
    if (index < 0 || index >= static_cast<int>(snap.tracks.size())) return {};
    const TrackType type = snap.tracks[static_cast<size_t>(index)].type;
    int sameBefore = 0;
    int sameTotal = 0;
    for (int i = 0; i < static_cast<int>(snap.tracks.size()); ++i) {
        if (snap.tracks[static_cast<size_t>(i)].type != type) continue;
        ++sameTotal;
        if (i < index) ++sameBefore;
    }
    switch (type) {
        case TrackType::Video: return "V" + std::to_string(sameTotal - sameBefore);
        case TrackType::Audio: return "A" + std::to_string(sameBefore + 1);
        case TrackType::Title: return "T" + std::to_string(sameBefore + 1);
    }
    return {};
}

// The y span the lane-drag indicator bar sits at: the top edge of lane `to` when the dragged lane `from` moves up
// (to < from), the bottom edge when it moves down. Returns false when there is nothing to show.
inline bool laneDragBarEdge(int from, int to, int trackCount, bool* atTopEdge) {
    if (from < 0 || to < 0 || from == to || from >= trackCount || to >= trackCount) return false;
    *atTopEdge = to < from;
    return true;
}

}  // namespace uv::timeline
