#pragma once

#include <atomic>
#include <cstdint>
#include <memory>
#include <string>

#include "core/error.h"

namespace uv::thumb {

// Lightweight single-purpose video decoder for timeline thumbnails. It is independent from the
// preview pipeline: AMediaExtractor + AMediaCodec decode to CPU-readable YUV buffers, and only the
// frames that become tiles are ever read back (every other output buffer is released untouched). Blocking and single-threaded: use
// from the thumbnail worker, one instance at a time, and destroy it when idle so the hardware
// decoder is not held while the preview needs it.
class ThumbDecoder {
public:
    // Opens the first video track of the media behind `fd` (not owned, not closed); a photo becomes a one-tile decoder.
    // On failure `detail` (optional) says what failed and with which underlying code.
    static std::unique_ptr<ThumbDecoder> open(int fd, core::Status* status, std::string* detail = nullptr);
    ~ThumbDecoder();
    ThumbDecoder(const ThumbDecoder&) = delete;
    ThumbDecoder& operator=(const ThumbDecoder&) = delete;

    int64_t durationUs() const;

    // Decodes the frame at (or the first frame after) `timeUs` into one RGB565 tile of
    // kTilePixels values. A time past the last frame yields the last frame.
    core::Status decodeTile(int64_t timeUs, const std::atomic<bool>& cancel, uint16_t* out);

    struct Impl;

private:
    explicit ThumbDecoder(std::unique_ptr<Impl> impl);
    std::unique_ptr<Impl> impl_;
};

}  // namespace uv::thumb
