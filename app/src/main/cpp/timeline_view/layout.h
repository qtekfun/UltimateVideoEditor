#pragma once

#include <algorithm>
#include <cstdint>
#include <memory>
#include <vector>

namespace uv::timeline {

// Pixel metrics derived from display density.
struct Layout {
    float rulerHeight;
    float trackHeight;  // video and title lanes
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

    // Height of audio lanes: trackHeight times the layout sheet's audio lane factor (so the waveform and the fade / volume
    // handles have more room). Only lanes known to be audio use it, which needs withTracks(); a Layout without lanes
    // treats every lane as trackHeight.
    float audioTrackHeight = 0.0f;
    // Per-lane geometry (offsets from the first lane, heights), filled by withTracks(). Shared so copies stay cheap.
    struct Lanes {
        std::vector<float> top;     // offset of each lane from the first lane's top
        std::vector<float> height;  // height of each lane
        float total = 0.0f;         // all lanes with their gaps
    };
    std::shared_ptr<const Lanes> lanes = nullptr;

    // [laneScale] stretches only the lanes (the layout sheet's lane height preset); the ruler, gaps and touch slop keep their
    // size. It is clamped to [kMinLaneScale, kMaxLaneScale] so a bad value cannot hide the lanes.
    static constexpr float kMinLaneScale = 0.5f;
    static constexpr float kMaxLaneScale = 3.0f;
    // [audioFactor] is the audio lanes' height relative to the other lanes, clamped to [kMinAudioFactor, kMaxAudioFactor].
    static constexpr float kMinAudioFactor = 1.0f;
    static constexpr float kMaxAudioFactor = 3.0f;
    static Layout forDensity(float density, float laneScale = 1.0f, float audioFactor = 1.0f) {
        const float scale = std::min(kMaxLaneScale, std::max(kMinLaneScale, laneScale));
        const float factor = std::min(kMaxAudioFactor, std::max(kMinAudioFactor, audioFactor));
        Layout l{28.0f * density, 64.0f * density * scale, 4.0f * density, 14.0f * density};
        l.audioTrackHeight = l.trackHeight * factor;
        l.markerHitHalf = 20.0f * density;
        return l;
    }
    // Copy of this layout with a lane header column of the given width.
    Layout withHeaders(float width) const {
        Layout l = *this;
        l.headerWidth = std::max(0.0f, width);
        return l;
    }
    // Copy of this layout with per-lane geometry for the given tracks (anything with a `type` of TrackType), audio lanes
    // taking audioTrackHeight. Everything lane related (drawing, hit tests, scroll extent) then goes through trackTop /
    // heightOf / trackAt / contentHeight, so they stay consistent.
    template <typename Tracks>
    Layout withTracks(const Tracks& tracks) const {
        Layout l = *this;
        auto built = std::make_shared<Lanes>();
        built->top.reserve(tracks.size());
        built->height.reserve(tracks.size());
        float offset = 0.0f;
        for (const auto& t : tracks) {
            const float h = static_cast<int32_t>(t.type) == 1 && audioTrackHeight > 0.0f ? audioTrackHeight : trackHeight;  // TrackType::Audio
            built->top.push_back(offset);
            built->height.push_back(h);
            offset += h + trackGap;
        }
        built->total = offset;
        l.lanes = std::move(built);
        return l;
    }
    bool hasLane(int index) const { return lanes && index >= 0 && static_cast<size_t>(index) < lanes->top.size(); }
    // Offset of a lane from the first lane's top; lanes past the known ones continue at the default height.
    float laneOffset(int index) const {
        if (hasLane(index)) return lanes->top[static_cast<size_t>(index)];
        const int known = lanes ? static_cast<int>(lanes->top.size()) : 0;
        return (lanes ? lanes->total : 0.0f) + static_cast<float>(index - known) * (trackHeight + trackGap);
    }
    float trackTop(int index) const { return rulerHeight + inset + laneOffset(index); }
    float heightOf(int index) const { return hasLane(index) ? lanes->height[static_cast<size_t>(index)] : trackHeight; }
    // The lane at `yy` pixels below the first lane's top (the scrolled lane area), or -1 above the first lane, in a gap or
    // past the last of [trackCount] lanes.
    int trackAt(double yy, int trackCount) const {
        if (yy < 0) return -1;
        int lo = 0, hi = trackCount - 1, found = -1;
        while (lo <= hi) {  // the last lane whose top is <= yy
            const int mid = (lo + hi) / 2;
            if (laneOffset(mid) <= yy) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        if (found < 0 || yy - laneOffset(found) > heightOf(found)) return -1;
        return found;
    }
    // Height of the lane stack plus ruler, without the anchoring inset: what scrolling is clamped to.
    float contentHeight(int trackCount) const { return rulerHeight + laneOffset(trackCount); }

    // Copy of this layout with the lane stack pushed down so its last lane rests on the panel bottom.
    Layout anchoredBottom(int trackCount, float viewHeight) const {
        Layout l = *this;
        l.inset = std::max(0.0f, viewHeight - contentHeight(trackCount));
        return l;
    }
};

}  // namespace uv::timeline
