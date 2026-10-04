#pragma once

#include <cstdint>

namespace uv::decode {

/**
 * Whether a frame released for rendering at [releasedMs] must be given up as lost.
 *
 * The consumer (the render thread, or the export loop) drains the image reader whenever it gets to it. A frame
 * that is merely waiting in the reader because the consumer is busy is not lost, however long that takes:
 * declaring it lost makes the decoder seek back to the previous key frame and decode it again, which on a
 * long-GOP stream restarts from the start of the GOP. A frame is lost when the consumer drained the reader more
 * than [timeoutMs] after the release and the frame was still not there, or, as a safety net for a consumer that
 * stopped draining, when it has been pending for [hardTimeoutMs].
 */
inline bool pendingExpiredAfterDrain(int64_t releasedMs, int64_t lastDrainMs, int64_t nowMs, int64_t timeoutMs,
                                     int64_t hardTimeoutMs) {
    if (nowMs - releasedMs > hardTimeoutMs) return true;
    return lastDrainMs - releasedMs > timeoutMs;
}

}  // namespace uv::decode
