#pragma once

#include <android/hardware_buffer.h>
#include <media/NdkImageReader.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaExtractor.h>

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <functional>
#include <map>
#include <memory>
#include <mutex>
#include <set>
#include <thread>

#include "decode/frame_rate.h"
#include "decode/status.h"

namespace uv::decode {

struct AssetInfo {
    int32_t width = 0;
    int32_t height = 0;
    int64_t durationFrames = 0;
    Rational fps{30, 1};
    int32_t colorTransfer = 0;  // MediaFormat.COLOR_TRANSFER_*, 0 if unknown
};

// Decodes one video track with a hardware AMediaCodec into an AImageReader (GPU-usage buffers).
// A worker thread keeps the window [target - lookBehind, target + lookAhead] decoded: frames
// that are inside the window and not cached are rendered to the reader, all others are
// dropped without touching the GPU. The render thread drains the reader via drainImages().
class VideoDecoder {
public:
    struct Callbacks {
        std::function<void()> onImageAvailable;           // any thread
        std::function<bool(int64_t frame)> isCached;      // decode thread
        std::function<void(const Error&)> onError;        // decode thread
    };

    // Takes ownership of `fd` (closed on destruction/failure).
    static Result<std::unique_ptr<VideoDecoder>> open(int fd, Rational fpsOverride, Callbacks callbacks);

    ~VideoDecoder();
    VideoDecoder(const VideoDecoder&) = delete;
    VideoDecoder& operator=(const VideoDecoder&) = delete;

    const AssetInfo& info() const { return info_; }

    // Thread-safe. Moves the playhead and wakes the worker.
    void setTarget(int64_t frame);
    void setWindow(int32_t lookBehind, int32_t lookAhead);

    // Render thread. Calls `fn` for every image waiting in the reader; `fn` must be done reading
    // the buffer when it returns (it is released right after).
    void drainImages(const std::function<void(int64_t frame, AHardwareBuffer* buffer)>& fn);
    // Render thread: the frame reached the cache (or was discarded), stop counting it as in flight.
    void markResolved(int64_t frame);

    // Stops and joins the worker. Must not be called from the render thread.
    void shutdown();

    int64_t framesDecoded() const { return framesDecoded_.load(); }

private:
    VideoDecoder() = default;

    void threadMain();
    // Returns true when the window has no missing frames (or nothing more can be decoded).
    bool step(int64_t target);
    bool findMissing(int64_t target, int64_t* missing);
    void seekTo(int64_t frame);
    void pump(int64_t lo, int64_t hi);
    bool needsFrame(int64_t frame);
    size_t inFlightCount();
    void reportError(Status code, const std::string& message);
    int64_t ptsToFrame(int64_t ptsUs) const;

    AMediaExtractor* extractor_ = nullptr;
    AMediaCodec* codec_ = nullptr;
    AImageReader* reader_ = nullptr;
    int fd_ = -1;
    AssetInfo info_;
    Callbacks callbacks_;
    int64_t startPtsUs_ = 0;

    std::thread thread_;
    std::mutex mu_;
    std::condition_variable cv_;
    bool stop_ = false;
    bool dirty_ = false;
    std::atomic<int64_t> target_{0};
    std::atomic<int32_t> lookBehind_{30};
    std::atomic<int32_t> lookAhead_{60};

    // Decode-thread state.
    int64_t decodePos_ = 0;          // next frame the codec will output (valid when !awaitingFirst_)
    bool awaitingFirstOutput_ = false;
    bool decoderPrimed_ = false;     // false until the first seek/feed
    bool inputEos_ = false;
    bool failed_ = false;            // unrecoverable codec error; worker idles until shutdown
    int64_t seekGoal_ = -1;          // frame we seeked for; -1 when satisfied
    int64_t lastFrame_ = 0;          // highest decodable frame
    std::set<int64_t> unavailable_;  // frames the stream never produced

    std::mutex readerMu_;  // guards reader_ between drainImages() and shutdown()

    std::mutex pendingMu_;
    std::condition_variable pendingCv_;
    std::map<int64_t, int64_t> pending_;  // frame -> steady-clock ms when released for render

    std::atomic<int64_t> framesDecoded_{0};
};

}  // namespace uv::decode
