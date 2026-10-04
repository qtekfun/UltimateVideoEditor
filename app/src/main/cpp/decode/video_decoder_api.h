#pragma once

#include <android/hardware_buffer.h>

#include <cstdint>
#include <functional>
#include <string>

#include "decode/frame_rate.h"
#include "decode/status.h"

namespace uv::decode {

struct AssetInfo {
    int32_t width = 0;
    int32_t height = 0;
    int64_t durationFrames = 0;
    Rational fps{30, 1};
    int32_t colorTransfer = 0;  // MediaFormat.COLOR_TRANSFER_*, 0 if unknown
    int32_t rotationDegrees = 0;  // clockwise rotation to apply for display (0/90/180/270)
    bool software = false;  // decoded by the FFmpeg fallback instead of a MediaCodec decoder
    bool proxyAdvised = false;  // software decoding of this size/rate will not keep up in real time: suggest a proxy
};

struct DecoderCallbacks {
    std::function<void()> onImageAvailable;           // any thread
    std::function<bool(int64_t frame)> isCached;      // decode thread
    std::function<void(const Error&)> onError;        // decode thread
};

// What the preview and the exporter need from a video decoder. Two implementations: the MediaCodec one
// (`VideoDecoder`) and the optional software one (`ffmpeg::FfmpegDecoder`); `openVideoDecoder()` picks.
// Every decoder keeps the window [target - lookBehind, target + lookAhead] decoded and hands each image to
// the render thread as an AHardwareBuffer through drainImages().
class IVideoDecoder {
public:
    using Callbacks = DecoderCallbacks;

    virtual ~IVideoDecoder() = default;

    virtual const AssetInfo& info() const = 0;

    // Thread-safe. Moves the playhead and wakes the worker.
    virtual void setTarget(int64_t frame) = 0;
    virtual void setWindow(int32_t lookBehind, int32_t lookAhead) = 0;

    // Render thread. Calls `fn` for every image waiting. `fn` returns a native fence fd (or -1) that signals
    // when the GPU is done reading the buffer; the buffer is not reused before then. Ownership of the fd
    // passes to the decoder.
    virtual void drainImages(const std::function<int(int64_t frame, AHardwareBuffer* buffer)>& fn) = 0;
    // Render thread: the frame reached the cache (or was discarded), stop counting it as in flight.
    virtual void markResolved(int64_t frame) = 0;

    // Stops and joins the worker. Must not be called from the render thread.
    virtual void shutdown() = 0;

    virtual int64_t framesDecoded() const = 0;

    // Thread-safe. True when the stream is known never to produce `frame`.
    virtual bool isUnavailable(int64_t frame) const = 0;
    // Thread-safe. Forgets what is "in flight" and makes the decoder seek afresh to `frame`.
    virtual void recover(int64_t frame) = 0;
    // Thread-safe one-line snapshot of the decode thread for stall reports.
    virtual std::string describe() const = 0;
};

}  // namespace uv::decode
