#include "decode/open_decoder.h"

#include <fcntl.h>
#include <unistd.h>

#include "decode/decoder_selection.h"
#include "decode/ffmpeg/ffmpeg_api.h"
#include "decode/log.h"
#include "decode/video_decoder.h"

namespace uv::decode {

Result<std::unique_ptr<IVideoDecoder>> openVideoDecoder(int fd, Rational fpsOverride, DecoderCallbacks callbacks) {
    const bool softwareBuilt = ffmpeg::available();
    // MediaCodec's open consumes the descriptor even on failure; keep a duplicate for the software attempt.
    // The software reader reads with pread(), so a shared file offset does not matter.
    const int spare = softwareBuilt ? fcntl(fd, F_DUPFD_CLOEXEC, 0) : -1;

    auto hardware = VideoDecoder::open(fd, fpsOverride, callbacks);
    if (hardware.ok()) {
        if (spare >= 0) close(spare);
        return std::unique_ptr<IVideoDecoder>(std::move(hardware.value()));
    }

    const Error hardwareError = hardware.error();
    if (chooseRoute(hardwareError.code, softwareBuilt && spare >= 0) != Route::Software) {
        if (spare >= 0) close(spare);
        return combineOpenErrors(hardwareError, nullptr, softwareBuilt);
    }

    UV_LOGI("MediaCodec could not open the video (%s); trying the software decoder", hardwareError.message.c_str());
    auto software = ffmpeg::openSoftwareDecoder(spare, fpsOverride, std::move(callbacks));  // owns `spare`
    if (software.ok()) return software;
    const Error softwareError = software.error();
    return combineOpenErrors(hardwareError, &softwareError, softwareBuilt);
}

}  // namespace uv::decode
