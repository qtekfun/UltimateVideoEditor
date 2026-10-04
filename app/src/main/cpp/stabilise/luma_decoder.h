#pragma once

#include <atomic>
#include <cstdint>
#include <functional>
#include <memory>

#include "core/error.h"
#include "stabilise/gray.h"

namespace uv::stab {

// Sequential video decoder for the analysis pass. It is independent from the preview pipeline (the same
// approach as the thumbnail decoder): AMediaExtractor + AMediaCodec decode into CPU-readable YUV buffers with
// no output surface, and only the luma plane is read, rotated upright and scaled down. Blocking and
// single-threaded; one instance at a time, destroyed as soon as the analysis is over so the hardware decoder
// is free for the preview.
class LumaDecoder {
public:
    // Opens the first video track of the media behind `fd` (not owned, not closed).
    static std::unique_ptr<LumaDecoder> open(int fd, core::Status* status);
    ~LumaDecoder();
    LumaDecoder(const LumaDecoder&) = delete;
    LumaDecoder& operator=(const LumaDecoder&) = delete;

    int64_t durationUs() const;

    // Called for each frame in presentation order with its time (microseconds from the first frame of the
    // media, the same origin as the preview decoder's frame numbers) and its luma. Return false to stop.
    using FrameFn = std::function<bool(int64_t ptsUs, const Gray& luma)>;

    // Decodes [startUs, endUs] (from the first frame) and hands every frame to `onFrame`. The decoder seeks to
    // the sync frame before `startUs` and discards frames until it. Returns Ok when it reached `endUs`, the
    // end of the stream or the callback said stop; Cancelled when `cancel` was set.
    core::Status run(int64_t startUs, int64_t endUs, int maxDimension, const std::atomic<bool>& cancel, const FrameFn& onFrame);

    struct Impl;

private:
    explicit LumaDecoder(std::unique_ptr<Impl> impl);
    std::unique_ptr<Impl> impl_;
};

}  // namespace uv::stab
