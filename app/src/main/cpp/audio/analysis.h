#pragma once

// Offline measurements on a PcmDecoder: integrated loudness (for "normalise to a target") and the
// noise profile of a quiet region (for noise suppression). They run on the caller's thread
// (Kotlin calls them from Dispatchers.IO), decode at 48 kHz stereo and never touch the playback state.

#include <cstdint>
#include <functional>

#include "audio/pcm_decoder.h"
#include "core/error.h"

namespace uv::audio {

constexpr int32_t kAnalysisRate = 48000;
// A noise profile never needs more than this much audio; longer regions are cut.
constexpr int64_t kMaxNoiseProfileMicros = 10'000'000;

// Called between reads so a long measurement can be cancelled; return true to stop (-> Cancelled).
using CancelCheck = std::function<bool()>;

// Integrated loudness (LUFS) of [startMicros, endMicros) of the decoder's media (endMicros < 0:
// until the end). `lufsOut` is LoudnessMeter::kSilence when nothing audible was found.
core::Status measureLoudness(PcmDecoder& decoder, int64_t startMicros, int64_t endMicros, double* lufsOut,
                             double* samplePeakOut = nullptr, const CancelCheck& cancel = {});

// Noise profile (kDenoiseBins magnitudes) of [startMicros, endMicros). InvalidArgument when the
// region is shorter than about 90 ms.
core::Status measureNoiseProfile(PcmDecoder& decoder, int64_t startMicros, int64_t endMicros, float* magnitudeOut,
                                 const CancelCheck& cancel = {});

}  // namespace uv::audio
