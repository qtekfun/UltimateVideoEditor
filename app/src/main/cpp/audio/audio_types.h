#pragma once

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <memory>
#include <optional>
#include <vector>

#include "audio/audio_time.h"
#include "audio/clip_buffer.h"
#include "audio/pcm_decoder.h"
#include "audio/resampler.h"
#include "audio/retime_source.h"

namespace uv::audio {

// Per-clip decode state. `buffer`, `eofAt` and `failed` are shared with the audio thread; the
// rest is touched only by the decode worker. Sources survive snapshot edits that merely move or
// re-gain a clip (the buffer is clip-local), so such edits never restart decoding.
struct ClipSource {
    ClipSource(int64_t key, int64_t asset, int64_t srcInMicros, int32_t bufferFrames, std::vector<RetimeKnot> retimeKnots = {},
               Rational projectFps = {})
        : buffer(bufferFrames),
          clipKey(key),
          assetKey(asset),
          sourceInMicros(srcInMicros),
          knots(std::move(retimeKnots)),
          fps(projectFps) {}

    ClipBuffer buffer;
    const int64_t clipKey;
    const int64_t assetKey;
    const int64_t sourceInMicros;
    // A retimed clip (speed, ramp, reverse) reads its source through these knots instead of at 1x
    // from sourceInMicros; see audio/retime_source.h. Part of the source's identity: a new retime
    // makes a new source, so the clip buffer is never reused across it.
    const std::vector<RetimeKnot> knots;
    const Rational fps;  // project frame rate, the unit of the knots
    std::atomic<int64_t> eofAt{INT64_MAX};  // clip-local sample where decoded audio ends
    std::atomic<bool> failed{false};

    // Worker only.
    std::unique_ptr<PcmDecoder> decoder;
    std::optional<LinearResampler> resampler;
    std::unique_ptr<RetimedReader> retimed;  // set instead of `resampler` for a retimed clip
    int64_t decodedEnd = 0;
    bool hitEof = false;
    std::chrono::steady_clock::time_point retryAfter{};  // failed clips are retried no sooner
    std::vector<float> srcScratch;
    std::vector<float> outScratch;

    // True when the audio thread can play [localPos, localPos + frames) without an underrun, or
    // when waiting would be pointless (failed or past the end of the media).
    bool ready(int64_t localPos, int32_t frames) const {
        if (failed.load(std::memory_order_acquire)) return true;
        const int64_t eof = eofAt.load(std::memory_order_acquire);
        if (localPos >= eof) return true;
        const int32_t need = static_cast<int32_t>(std::min<int64_t>(frames, eof - localPos));
        return buffer.covers(localPos, need);
    }
};

struct PreparedClip {
    int64_t startSample = 0;  // timeline samples at the output rate
    int64_t endSample = 0;
    float gain = 1.0f;        // linear
    // Equal-power crossfade lengths in samples (0 = none): the clip fades in over its first
    // fadeInSamples and out over its last fadeOutSamples (core/crossfade_math.h).
    int64_t fadeInSamples = 0;
    int64_t fadeOutSamples = 0;
    std::shared_ptr<ClipSource> source;
};

// Snapshot converted to output-rate sample positions, ready for the audio thread.
struct PreparedSnapshot {
    uint64_t generation = 0;
    int32_t sampleRate = 48000;
    Rational fps;
    std::vector<PreparedClip> clips;
};

}  // namespace uv::audio
