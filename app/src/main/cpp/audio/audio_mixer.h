#pragma once

#include <cstdint>

#include "audio/audio_types.h"

namespace uv::audio {

struct MixResult {
    int32_t underruns = 0;          // active clips whose data was not ready this block
    int64_t lastUnderrunClip = -1;
};

// dB -> linear amplitude.
float dbToLinear(float db);

// Sums every clip overlapping [startSample, startSample + frames) into `out` (stereo float,
// overwritten), applies per-clip gain, then hard-limits to [-1, 1]. `scratch` must hold
// frames * 2 floats. Real-time safe: no allocation, no locks.
MixResult mixBlock(const PreparedSnapshot& snapshot, int64_t startSample, int32_t frames, float* out,
                   float* scratch);

}  // namespace uv::audio
