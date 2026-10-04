// Stands in for the FFmpeg fallback in builds that do not include it (the default).
#include <unistd.h>

#include "audio/ffmpeg_pcm.h"
#include "decode/ffmpeg/ffmpeg_api.h"

namespace uv::decode::ffmpeg {

bool available() { return false; }

Result<std::unique_ptr<IVideoDecoder>> openSoftwareDecoder(int fd, Rational, DecoderCallbacks) {
    if (fd >= 0) close(fd);
    return Error{Status::UnsupportedFormat, "software decoding is not included in this build"};
}

}  // namespace uv::decode::ffmpeg

namespace uv::audio {

std::unique_ptr<PcmDecoder> openSoftwarePcmDecoder(int, core::Status* status) {
    *status = core::Status::UnsupportedFormat;
    return nullptr;
}

}  // namespace uv::audio
