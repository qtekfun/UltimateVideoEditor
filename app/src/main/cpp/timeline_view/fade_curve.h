#pragma once

#include <cstdint>

namespace uv::timeline {

// How a thumbnail appears once its tile has been uploaded: opacity from 0 to 1 over `durationNanos`, eased (smoothstep) so
// it neither pops in nor lingers. `elapsedNanos` is the time since the tile became ready; a negative time (a clock that
// went backwards) or one past the end counts as fully visible so a tile can never be stuck transparent.
constexpr int64_t kThumbFadeNanos = 160'000'000;

inline float fadeAlpha(int64_t elapsedNanos, int64_t durationNanos) {
    if (durationNanos <= 0 || elapsedNanos >= durationNanos || elapsedNanos < 0) return 1.0f;
    const float t = static_cast<float>(elapsedNanos) / static_cast<float>(durationNanos);
    return t * t * (3.0f - 2.0f * t);
}

}  // namespace uv::timeline
