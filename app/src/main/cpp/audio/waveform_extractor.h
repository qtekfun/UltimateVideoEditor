#pragma once

#include <atomic>

#include "audio/waveform_peaks.h"
#include "core/error.h"

namespace uv::audio {

// Decodes the first audio track of the media behind `fd` to PCM with AMediaExtractor/AMediaCodec
// and builds its peak pyramid. Blocking: call from a worker thread. Does not take ownership of fd.
core::Status extractWaveform(int fd, const std::atomic<bool>& cancel, PeakPyramid* out);

}  // namespace uv::audio
