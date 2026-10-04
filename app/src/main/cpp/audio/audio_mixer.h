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

// Mixes every clip overlapping [startSample, startSample + frames) into `out` (stereo float,
// overwritten): per clip EQ, pan, fade handles, transition ramps and gain; per track bus
// compressor and volume/mute; sidechain ducking of the music tracks; the master limiter; a final
// clamp to [-1, 1]; and the peak meter. `scratch` must hold frames * 2 floats and frames must not
// exceed kMaxMixBlock. All DSP is per sample, so splitting a render into different block sizes
// gives the same samples. Real-time safe: no allocation, no locks.
MixResult mixBlock(const PreparedSnapshot& snapshot, int64_t startSample, int32_t frames, float* out,
                   float* scratch);

}  // namespace uv::audio
