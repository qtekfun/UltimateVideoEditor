#pragma once

#include <cstdint>

namespace uv::decode {

constexpr int64_t kMaxForwardSkipFrames = 120;  // decode forward instead of seeking up to this far

/**
 * Whether the decode thread must (re)seek to bring frame [missing] into reach.
 *
 * A seek lands on the previous sync frame, which in a long-GOP stream can be more than
 * kMaxForwardSkipFrames before the goal. Seeking again just because the goal is "too far ahead" of that
 * sync frame would restart from the same place forever (a livelock seen on a 720p clip with one key
 * frame), so while the decoder is still running toward the goal it just issued, no new seek is needed.
 * A stalled decoder is recovered separately by the watchdog (forceSeek).
 */
inline bool needsSeek(bool forced, bool primed, bool awaitingFirstOutput, int64_t decodePos, int64_t missing,
                      int64_t seekGoal) {
    if (forced || !primed) return true;
    if (awaitingFirstOutput) return false;
    if (seekGoal == missing && decodePos <= missing) return false;  // still approaching the goal
    return decodePos > missing || missing - decodePos > kMaxForwardSkipFrames;
}

}  // namespace uv::decode
