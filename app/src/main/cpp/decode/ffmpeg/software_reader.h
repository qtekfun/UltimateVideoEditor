#pragma once

// Software video decoding with libavcodec: one video stream of a file, frame by frame, with exact integer
// frame indices. No Android types, so it can be tested on the host against any libav build. The Android glue
// (ffmpeg_decoder.h) wraps it into the `IVideoDecoder` the preview and the exporter use.

#include <cstdint>
#include <memory>
#include <string>

#include "decode/frame_rate.h"
#include "decode/status.h"

namespace uv::decode::ffmpeg {

constexpr int64_t kUnknownDurationFrames = INT64_MAX / 4;

struct VideoStreamInfo {
    int32_t width = 0;
    int32_t height = 0;
    Rational fps{30, 1};
    int64_t durationFrames = kUnknownDurationFrames;
    bool durationKnown = false;
    int32_t colorTransfer = 0;    // MediaFormat.COLOR_TRANSFER_*
    int32_t rotationDegrees = 0;  // clockwise, 0/90/180/270
    std::string codec;            // libav codec name, for logs and errors
};

enum class ReadStatus { Frame, EndOfStream, Failed };

class SoftwareVideoReader {
public:
    // Takes ownership of `fd` (closed on destruction, and on failure). `threads` is the libavcodec thread count.
    static Result<std::unique_ptr<SoftwareVideoReader>> open(int fd, Rational fpsOverride, int threads);

    ~SoftwareVideoReader();
    SoftwareVideoReader(const SoftwareVideoReader&) = delete;
    SoftwareVideoReader& operator=(const SoftwareVideoReader&) = delete;

    const VideoStreamInfo& info() const { return info_; }

    // Repositions the demuxer so the next decode() returns a picture at or before `frame` (the previous key
    // frame); the caller decodes forward and drops what it does not need. False with *error on failure.
    bool seek(int64_t frame, std::string* error);

    // Decodes the next picture in presentation order and returns its frame index. Indices increase strictly
    // within one seek: a picture whose index repeats (variable frame rate rounding) is skipped.
    ReadStatus decode(int64_t* frame, std::string* error);

    // Converts the picture returned by the last decode() to RGBA8 into `dst` (`stride` bytes per row, at least
    // width * 4). The picture stays valid until the next decode() or seek().
    bool convertRgba(uint8_t* dst, int32_t stride, std::string* error);

private:
    SoftwareVideoReader();
    struct Impl;
    std::unique_ptr<Impl> impl_;
    VideoStreamInfo info_;
};

}  // namespace uv::decode::ffmpeg
