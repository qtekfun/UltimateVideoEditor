#pragma once

// The software `IVideoDecoder`: libavcodec decodes on a worker thread, frames are converted to RGBA8 straight into
// CPU-writable AHardwareBuffers from a small pool and handed to the render thread through drainImages() exactly
// like a MediaCodec image, so the frame cache, the colour shader, the effects and the exporter are untouched.
// Only compiled into builds made with `-Puveditor.ffmpeg=<dir>`; otherwise ffmpeg_stub.cpp stands in.

#include <android/hardware_buffer.h>

#include <atomic>
#include <condition_variable>
#include <deque>
#include <memory>
#include <mutex>
#include <set>
#include <string>
#include <thread>
#include <vector>

#include "decode/ffmpeg/software_policy.h"
#include "decode/ffmpeg/software_reader.h"
#include "decode/video_decoder_api.h"

namespace uv::decode::ffmpeg {

class FfmpegDecoder final : public IVideoDecoder {
public:
    // Takes ownership of `fd` (closed on failure and on destruction).
    static Result<std::unique_ptr<IVideoDecoder>> open(int fd, Rational fpsOverride, DecoderCallbacks callbacks);

    ~FfmpegDecoder() override;
    FfmpegDecoder(const FfmpegDecoder&) = delete;
    FfmpegDecoder& operator=(const FfmpegDecoder&) = delete;

    const AssetInfo& info() const override { return info_; }
    void setTarget(int64_t frame) override;
    void setWindow(int32_t lookBehind, int32_t lookAhead) override;
    void drainImages(const std::function<int(int64_t frame, AHardwareBuffer* buffer)>& fn) override;
    void markResolved(int64_t frame) override;
    void shutdown() override;
    int64_t framesDecoded() const override { return framesDecoded_.load(); }
    bool isUnavailable(int64_t frame) const override;
    void recover(int64_t frame) override;
    std::string describe() const override;

private:
    FfmpegDecoder() = default;

    struct Ready {
        int64_t frame;
        AHardwareBuffer* buffer;
    };
    struct Busy {
        AHardwareBuffer* buffer;
        int fence;  // -1: free as soon as it is looked at
    };

    void threadMain();
    // One planning step; returns true when it did work (sought or decoded), false when it should wait.
    bool step();
    bool have(int64_t frame);  // mu_ held
    AHardwareBuffer* acquireBuffer();
    void reclaimLocked();
    void reportError(Status code, const std::string& message);

    std::unique_ptr<SoftwareVideoReader> reader_;
    AssetInfo info_;
    DecoderCallbacks callbacks_;
    SoftwareSpeed speed_ = SoftwareSpeed::Realtime;

    std::thread thread_;
    mutable std::mutex mu_;  // guards everything below except the atomics
    std::condition_variable cv_;
    bool stop_ = false;
    bool dirty_ = false;
    std::atomic<int64_t> target_{0};
    std::atomic<int32_t> lookBehind_{30};
    std::atomic<int32_t> lookAhead_{60};

    // Decode-thread state (only the worker touches plan_, lastFrame_ and failed_; mu_ guards the sets).
    PlanState plan_;
    int64_t lastFrame_ = 0;       // highest frame the stream can produce (the container's, until EOS says otherwise)
    bool eos_ = false;
    bool failed_ = false;
    std::set<int64_t> unavailable_;  // frames the stream never produced
    std::set<int64_t> inFlight_;     // converted and not yet resolved by the consumer
    std::deque<Ready> ready_;        // converted, waiting for drainImages
    std::vector<AHardwareBuffer*> free_;
    std::vector<Busy> busy_;
    int allocated_ = 0;

    std::atomic<int64_t> framesDecoded_{0};
    std::atomic<int64_t> seeks_{0};
    std::atomic<int64_t> discarded_{0};
    std::atomic<int64_t> sharedDecodePos_{0};
    std::atomic<int64_t> sharedSeekGoal_{-1};
};

}  // namespace uv::decode::ffmpeg
