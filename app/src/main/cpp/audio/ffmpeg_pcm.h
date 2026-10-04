#pragma once

#include <memory>

#include "audio/pcm_decoder.h"
#include "core/error.h"

namespace uv::audio {

// Software audio decoder of the FFmpeg fallback (interleaved stereo float at the stream's own rate), for audio
// the platform cannot decode. Duplicates `fd`; the caller keeps its own. Returns nullptr and sets *status on
// failure (UnsupportedFormat when this build does not include FFmpeg: see decode/ffmpeg/ffmpeg_api.h).
std::unique_ptr<PcmDecoder> openSoftwarePcmDecoder(int fd, core::Status* status);

}  // namespace uv::audio
