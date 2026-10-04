#pragma once

// The only surface of the FFmpeg fallback the rest of the engine sees. Builds without
// `-Puveditor.ffmpeg=<dir>` link ffmpeg_stub.cpp (available() == false, every open fails with a typed error);
// builds with it link ffmpeg_decoder.cpp / audio/ffmpeg_pcm_decoder.cpp. The audio entry point is in
// audio/ffmpeg_pcm.h.

#include <memory>

#include "decode/status.h"
#include "decode/video_decoder_api.h"

namespace uv::decode::ffmpeg {

// True when libavcodec/libavformat are linked into this build.
bool available();

// Software video decoder; takes ownership of `fd` (closed on failure too).
Result<std::unique_ptr<IVideoDecoder>> openSoftwareDecoder(int fd, Rational fpsOverride, DecoderCallbacks callbacks);

}  // namespace uv::decode::ffmpeg
