#pragma once

namespace uv::timeline {

// Pixel metrics derived from display density.
struct Layout {
    float rulerHeight;
    float trackHeight;
    float trackGap;
    float handleWidth;  // touch slop at clip edges

    static Layout forDensity(float density) {
        return {28.0f * density, 64.0f * density, 4.0f * density, 14.0f * density};
    }
    float trackTop(int index) const { return rulerHeight + index * (trackHeight + trackGap); }
    float contentHeight(int trackCount) const { return trackTop(trackCount); }
};

}  // namespace uv::timeline
