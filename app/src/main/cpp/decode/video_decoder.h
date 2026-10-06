#pragma once

#include <android/hardware_buffer.h>
#include <media/NdkImageReader.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaExtractor.h>

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <functional>
#include <map>
#include <memory>
#include <mutex>
#include <vector>
#include <set>
#include <string>
#include <thread>

#include "core/codec_config.h"
#include "core/file_lock.h"
#include "decode/frame_rate.h"
#include "decode/status.h"
#include "decode/seek_policy.h"
#include "decode/video_decoder_api.h"

namespace uv::decode {

// Decodes one video track with a hardware AMediaCodec into an AImageReader (GPU-usage buffers).
// A worker thread keeps the window [target - lookBehind, target + lookAhead] decoded: frames
// that are inside the window and not cached are rendered to the reader, all others are
// dropped without touching the GPU. The render thread drains the reader via drainImages().
class VideoDecoder final : public IVideoDecoder {
public:
    using Callbacks = DecoderCallbacks;

    // Takes ownership of `fd` (closed on destruction/failure).
    static Result<std::unique_ptr<VideoDecoder>> open(int fd, Rational fpsOverride, Callbacks callbacks);

    ~VideoDecoder() override;
    VideoDecoder(const VideoDecoder&) = delete;
    VideoDecoder& operator=(const VideoDecoder&) = delete;

    const AssetInfo& info() const override { return info_; }

    // Thread-safe. Moves the playhead and wakes the worker.
    void setTarget(int64_t frame) override;
    void setWindow(int32_t lookBehind, int32_t lookAhead) override;

    // Render thread. Calls `fn` for every image waiting in the reader. `fn` returns a native fence
    // fd (or -1) that signals when the GPU is done reading the buffer; the image is released with it,
    // so the codec will not overwrite the buffer before then. Ownership of the fd passes to the reader.
    void drainImages(const std::function<int(int64_t frame, AHardwareBuffer* buffer)>& fn) override;
    // Render thread: the frame reached the cache (or was discarded), stop counting it as in flight.
    void markResolved(int64_t frame) override;

    // Stops and joins the worker. Must not be called from the render thread.
    void shutdown() override;

    int64_t framesDecoded() const override { return framesDecoded_.load(); }

    // Thread-safe. True when the stream is known never to produce `frame` (it was skipped, or lies past
    // the last decodable frame), so a reader may stand in an earlier frame instead of waiting for it.
    bool isUnavailable(int64_t frame) const override;
    // Thread-safe. For a reader whose frame has not arrived for a while: forgets what is "in flight" and
    // makes the decoder seek afresh to `frame` instead of trusting its running state.
    void recover(int64_t frame) override;
    // Thread-safe one-line snapshot of the decode thread (positions, flags, counters) for stall reports.
    std::string describe() const override;

private:
    VideoDecoder() = default;

    void threadMain();
    // Returns true when the window has no missing frames (or nothing more can be decoded).
    bool step(int64_t target);
    bool nothingNeededBefore(int64_t target, int64_t frame);
    bool findMissing(int64_t target, int64_t* missing);
    void seekTo(int64_t frame);
    void resubmitCodecConfig();
    void pump(int64_t lo, int64_t hi);
    bool needsFrame(int64_t frame);
    size_t inFlightCount();
    bool pendingExpired(int64_t releasedMs) const;
    void reportError(Status code, const std::string& message);
    int64_t ptsToFrame(int64_t ptsUs) const;

    AMediaExtractor* extractor_ = nullptr;
    AMediaCodec* codec_ = nullptr;
    AImageReader* reader_ = nullptr;
    bool softwareDecoder_ = false;  // the codec that started is the platform's software decoder (a fallback)
    core::CodecConfig codecConfig_;  // csd-0..2 of the track, queued again after every flush (core/codec_config.h)
    std::string rungLabel_;  // which rung of the open ladder (decode/decoder_ladder.h) started the codec
    int fd_ = -1;
    core::FileLock fileLock_;  // held around every extractor call that reads the file (core/file_lock.h)
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
    std::atomic<bool> forceSeek_{false};  // recover(): the next step seeks even if the codec looks positioned

    // Decode-thread state.
    int64_t decodePos_ = 0;          // next frame the codec will output (valid when !awaitingFirst_)
    bool awaitingFirstOutput_ = false;
    bool decoderPrimed_ = false;     // false until the first seek/feed
    bool inputEos_ = false;
    bool failed_ = false;            // unrecoverable codec error; worker idles until shutdown
    int64_t seekGoal_ = -1;          // frame we seeked for; -1 when satisfied
    int64_t lastFrame_ = 0;          // highest decodable frame
    mutable std::mutex unavailableMu_;  // unavailable_ is read by the render thread (isUnavailable)
    std::set<int64_t> unavailable_;  // frames the stream never produced

    // Mirrors of decode-thread state for describe()/isUnavailable(), readable from any thread.
    std::atomic<int64_t> sharedLastFrame_{0};
    std::atomic<int64_t> sharedDecodePos_{0};
    std::atomic<int64_t> sharedSeekGoal_{-1};
    std::atomic<int64_t> sharedLastOutFrame_{-1};
    std::atomic<int64_t> sharedSeeks_{0};
    std::atomic<int64_t> sharedMarkedUnavailable_{0};
    std::atomic<bool> sharedPrimed_{false};
    std::atomic<bool> sharedInputEos_{false};
    bool earlyEndReported_ = false;  // decode thread: a stream that ends far before its declared length was reported once
    std::atomic<bool> sharedEarlyEnd_{false};
    std::atomic<bool> sharedFailed_{false};

    std::mutex readerMu_;  // guards reader_ between drainImages() and shutdown()

    std::mutex pendingMu_;
    std::condition_variable pendingCv_;
    std::map<int64_t, int64_t> pending_;  // frame -> steady-clock ms when released for render

    std::atomic<int64_t> framesDecoded_{0};
    std::atomic<int64_t> lastDrainMs_{0};  // steady-clock ms when the consumer last finished draining the reader

    // Decode-thread diagnostics, summarised in the log about once per second of activity.
    struct Diag {
        int64_t rendered = 0;       // outputs released to the reader
        int64_t dropped = 0;        // outputs released without rendering (outside the window or cached)
        int64_t seeks = 0;
        int64_t backpressure = 0;   // steps that waited for the render thread
        int64_t dequeueNs = 0;      // time blocked waiting for codec output
        int64_t dequeueEmpty = 0;   // dequeue calls that timed out
        std::chrono::steady_clock::time_point last;
    };
    void logDiag();
    Diag diag_;
};

}  // namespace uv::decode
