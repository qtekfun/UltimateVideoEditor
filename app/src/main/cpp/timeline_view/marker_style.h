#pragma once

#include <cstdint>

namespace uv::timeline {

// How a ruler marker is styled. The Kotlin side packs the style into the marker's last wire word (the one that
// was reserved in snapshot version 5, so no version bump and old snapshots still read as "no colour, no note"):
//   bits 0..2: colour code, 0 = none, 1..6 = red, orange, yellow, green, blue, purple (MarkerColor order + 1)
//   bit 3:     the marker has a note
constexpr int kMarkerColorCount = 6;
constexpr int32_t kMarkerColorMask = 7;
constexpr int32_t kMarkerNoteBit = 8;

struct MarkerRgb {
    float r;
    float g;
    float b;
};

// The colour code of a marker's `extra` word; an unknown code (7) reads as none.
constexpr int markerColorCode(int32_t extra) {
    const int code = static_cast<int>(extra & kMarkerColorMask);
    return code <= kMarkerColorCount ? code : 0;
}

constexpr bool markerHasNote(int32_t extra) { return (extra & kMarkerNoteBit) != 0; }

// Flag colour for a colour code; code 0 is the default pink used before markers could be coloured.
constexpr MarkerRgb markerRgb(int code) {
    switch (code) {
        case 1: return {1.00f, 0.32f, 0.32f};  // red
        case 2: return {1.00f, 0.57f, 0.00f};  // orange
        case 3: return {1.00f, 0.84f, 0.25f};  // yellow
        case 4: return {0.41f, 0.94f, 0.68f};  // green
        case 5: return {0.27f, 0.54f, 1.00f};  // blue
        case 6: return {0.70f, 0.53f, 1.00f};  // purple
        default: return {1.00f, 0.45f, 0.80f};
    }
}

}  // namespace uv::timeline
