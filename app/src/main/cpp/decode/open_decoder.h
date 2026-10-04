#pragma once

#include <memory>

#include "decode/video_decoder_api.h"

namespace uv::decode {

// Opens the video track of the media behind `fd`: MediaCodec first, then (only if MediaCodec cannot open it for
// a reason software can fix, and this build includes FFmpeg) the software decoder; see decoder_selection.h.
// Takes ownership of `fd` (closed on every path). `AssetInfo::software` tells which one answered.
Result<std::unique_ptr<IVideoDecoder>> openVideoDecoder(int fd, Rational fpsOverride, DecoderCallbacks callbacks);

}  // namespace uv::decode
