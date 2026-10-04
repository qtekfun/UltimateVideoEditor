#pragma once

#include <cstddef>
#include <cstdint>

namespace uv::decode {

// Frames released to the reader but not yet taken by the consumer. The buffer queue between the codec and the
// image reader keeps only the newest of the frames queued since the consumer last acquired one: releasing a second
// frame before the first was drained silently discards the first (seen on a Pixel 8: with 4 in flight, 4K60
// playback drew 429 of 600 frames with 227 stalls and a 1080p export of a long-GOP clip ran at 0.35x real time
// because every lost frame meant a backward seek and a re-decode from the key frame; with 1, 600 of 600 frames and
// 1.8x real time). `app/src/test/cpp/decode_sim_tests.cpp` reproduces this deterministically and fails if the value
// is raised. Do not raise it without re-measuring on a device (scripts/run-export-throughput.sh).
constexpr size_t kMaxInFlightFrames = 1;

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
