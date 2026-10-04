#pragma once

// What the software decoder's worker does next, given where the playhead window is and what is cached.
// Pure (no libav, no threads) so a fake reader can drive it in host tests. The seek rule is the one the
// MediaCodec decoder uses (decode/seek_policy.h), including its livelock guard for long-GOP streams.

#include <algorithm>
#include <cstdint>
#include <functional>

#include "decode/seek_policy.h"

namespace uv::decode::ffmpeg {

enum class Step {
    Idle,    // nothing in the window is missing
    Seek,    // reposition the demuxer for `missing`, then decode forward
    Decode,  // keep decoding forward: `missing` is ahead of (or at) the decoder
};

struct PlanState {
    bool forced = false;               // recover(): seek even if the decoder looks positioned
    bool primed = false;               // false until the first seek
    bool awaitingFirstOutput = false;  // seeked, no frame decoded since
    int64_t decodePos = 0;             // next frame the decoder will output (valid when !awaitingFirstOutput)
    int64_t seekGoal = -1;             // frame we last seeked for; -1 when satisfied
};

struct Plan {
    Step step = Step::Idle;
    int64_t missing = -1;
};

struct Window {
    int64_t lo = 0;
    int64_t hi = -1;
};

inline Window windowFor(int64_t target, int32_t lookBehind, int32_t lookAhead, int64_t lastFrame) {
    Window w;
    w.lo = std::max<int64_t>(0, target - lookBehind);
    w.hi = std::min<int64_t>(std::max<int64_t>(lastFrame, 0), target + lookAhead);
    if (target > w.hi) w.hi = target;  // a playhead past the last known frame still asks for itself
    return w;
}

// First frame in [lo, hi] that nobody has (`have` is true for cached, in-flight and unavailable frames).
inline bool findMissing(int64_t lo, int64_t hi, const std::function<bool(int64_t)>& have, int64_t* missing) {
    for (int64_t f = lo; f <= hi; ++f) {
        if (!have(f)) {
            *missing = f;
            return true;
        }
    }
    return false;
}

inline Plan planStep(const PlanState& s, const Window& w, const std::function<bool(int64_t)>& have) {
    Plan p;
    if (!findMissing(w.lo, w.hi, have, &p.missing)) return p;
    p.step = needsSeek(s.forced, s.primed, s.awaitingFirstOutput, s.decodePos, p.missing, s.seekGoal) ? Step::Seek
                                                                                                       : Step::Decode;
    return p;
}

}  // namespace uv::decode::ffmpeg
