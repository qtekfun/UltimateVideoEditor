#pragma once

#include <algorithm>
#include <cstdint>

namespace uv::audio {

// Relates the output stream's frame counter to timeline position. Published by the audio
// thread whenever playback (re)starts, pauses, seeks or leaves a warm-up hold.
struct ClockAnchor {
    int64_t streamFrame = 0;      // stream frames rendered before the anchor block
    int64_t timelineSample = 0;   // timeline sample rendered at that stream frame
    bool playing = false;         // false: position is frozen at timelineSample
};

// Timeline sample being heard when the device presents `presentedFrame`. Clamped so it never
// precedes the anchor (audio still in the pipeline from before the anchor) nor runs past what
// has actually been rendered.
inline int64_t timelineSampleAt(const ClockAnchor& a, int64_t presentedFrame, int64_t renderedStreamFrames) {
    if (!a.playing) return a.timelineSample;
    const int64_t maxDelta = std::max<int64_t>(0, renderedStreamFrames - a.streamFrame);
    const int64_t delta = std::clamp<int64_t>(presentedFrame - a.streamFrame, 0, maxDelta);
    return a.timelineSample + delta;
}

}  // namespace uv::audio
