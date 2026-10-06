#pragma once

#include <algorithm>
#include <cstdint>

#include "decode/frame_rate.h"

namespace uv::decode {

constexpr int64_t kMaxForwardSkipFrames = 120;  // decode forward instead of seeking up to this far

/**
 * How many frames short of its declared length a stream may end before that counts as a read error rather than the end
 * of the file: one second, or 2% of the clip when that is more. A container length is not exact (trailing audio, rounding),
 * but a clip that stops at 18 s of 28.7 s (an MP4 reader hitting unreadable data, seen on a Pixel 8) is cut short.
 */
inline int64_t earlyEndToleranceFrames(Rational fps, int64_t declaredFrames) {
    const int64_t second = fps.den > 0 ? (fps.num + fps.den - 1) / fps.den : 60;
    return std::max<int64_t>(second, declaredFrames / 50);
}

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
