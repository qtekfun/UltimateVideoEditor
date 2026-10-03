#pragma once

#include <cstdint>
#include <functional>
#include <memory>

#include "core/error.h"

namespace uv::audio {

struct PcmReadResult {
    core::Status status = core::Status::Ok;
    int32_t frames = 0;  // stereo frames written to dst
    bool eof = false;    // no more audio after `frames`
};

// Sequential PCM source for one asset: interleaved stereo float at its own sample rate.
// Used from the decode worker only. Android implementation: android_pcm_decoder.h.
class PcmDecoder {
public:
    virtual ~PcmDecoder() = default;
    virtual int32_t sampleRate() const = 0;
    // Positions the stream so the next read starts exactly at `micros` of the source.
    virtual core::Status seekToMicros(int64_t micros) = 0;
    // May return frames == 0 with eof == false when no output is ready yet; call again.
    virtual PcmReadResult read(float* dstStereo, int32_t maxFrames) = 0;
};

// Opens a decoder for an asset key. Returns nullptr and sets *status on failure.
using DecoderFactory = std::function<std::unique_ptr<PcmDecoder>(int64_t assetKey, core::Status* status)>;

}  // namespace uv::audio
