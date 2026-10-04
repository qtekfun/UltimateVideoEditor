#include "decode/ffmpeg/ffmpeg_decoder.h"

#include <poll.h>
#include <unistd.h>

#include <algorithm>
#include <chrono>
#include <cstdio>

#include "decode/decoder_selection.h"
#include "decode/log.h"

namespace uv::decode::ffmpeg {

namespace {

constexpr size_t kMaxReady = 2;      // converted frames waiting for the consumer before the worker pauses
constexpr int kMaxBuffers = 8;       // pool size: ready + still being read by the GPU + the one being written
constexpr int kDecodeBatch = 8;      // pictures decoded between two looks at the playhead
constexpr auto kIdleWait = std::chrono::milliseconds(50);
constexpr auto kBufferWait = std::chrono::seconds(3);  // how long the worker waits for a pool buffer before dropping a frame

bool fenceSignalled(int fence) {
    if (fence < 0) return true;
    pollfd p{fence, POLLIN, 0};
    return poll(&p, 1, 0) != 0;  // readable or error: done either way
}

}  // namespace

Result<std::unique_ptr<IVideoDecoder>> FfmpegDecoder::open(int fd, Rational fpsOverride, DecoderCallbacks callbacks) {
    const SoftwareBudget budget = softwareBudget(std::thread::hardware_concurrency());
    auto reader = SoftwareVideoReader::open(fd, fpsOverride, budget.threads);
    if (!reader.ok()) return reader.error();

    std::unique_ptr<FfmpegDecoder> d(new FfmpegDecoder());
    d->reader_ = std::move(reader.value());
    d->callbacks_ = std::move(callbacks);
    const VideoStreamInfo& s = d->reader_->info();
    d->info_.width = s.width;
    d->info_.height = s.height;
    d->info_.fps = s.fps;
    d->info_.durationFrames = s.durationFrames;
    d->info_.colorTransfer = s.colorTransfer;
    d->info_.rotationDegrees = s.rotationDegrees;
    d->info_.software = true;
    d->speed_ = softwareSpeed(s.width, s.height, s.fps, budget);
    d->info_.proxyAdvised = d->speed_ == SoftwareSpeed::Slow;
    d->lastFrame_ = s.durationKnown ? std::max<int64_t>(s.durationFrames - 1, 0) : kUnknownDurationFrames;

    UV_LOGI("opened in software (%s) %dx%d %lld/%lld fps, %lld frames, transfer=%d rotation=%d, %d threads%s", s.codec.c_str(),
            s.width, s.height, static_cast<long long>(s.fps.num), static_cast<long long>(s.fps.den),
            static_cast<long long>(s.durationFrames), s.colorTransfer, s.rotationDegrees, budget.threads,
            d->info_.proxyAdvised ? ", too big for real time: a proxy is advised" : "");

    d->thread_ = std::thread([raw = d.get()] { raw->threadMain(); });
    return std::unique_ptr<IVideoDecoder>(std::move(d));
}

FfmpegDecoder::~FfmpegDecoder() { shutdown(); }

void FfmpegDecoder::shutdown() {
    {
        std::lock_guard<std::mutex> lock(mu_);
        if (stop_ && !thread_.joinable()) return;
        stop_ = true;
    }
    cv_.notify_all();
    if (thread_.joinable()) thread_.join();
    std::lock_guard<std::mutex> lock(mu_);
    for (const Ready& r : ready_) AHardwareBuffer_release(r.buffer);
    ready_.clear();
    for (const Busy& b : busy_) {
        if (b.fence >= 0) close(b.fence);
        AHardwareBuffer_release(b.buffer);
    }
    busy_.clear();
    for (AHardwareBuffer* b : free_) AHardwareBuffer_release(b);
    free_.clear();
    allocated_ = 0;
    reader_.reset();
}

void FfmpegDecoder::setTarget(int64_t frame) {
    target_.store(frame);
    {
        std::lock_guard<std::mutex> lock(mu_);
        dirty_ = true;
    }
    cv_.notify_all();
}

void FfmpegDecoder::setWindow(int32_t lookBehind, int32_t lookAhead) {
    lookBehind_.store(std::max(0, lookBehind));
    lookAhead_.store(softwareLookAhead(std::max(0, lookAhead), speed_));
    {
        std::lock_guard<std::mutex> lock(mu_);
        dirty_ = true;
    }
    cv_.notify_all();
}

void FfmpegDecoder::drainImages(const std::function<int(int64_t frame, AHardwareBuffer* buffer)>& fn) {
    std::vector<Ready> batch;
    {
        std::lock_guard<std::mutex> lock(mu_);
        batch.assign(ready_.begin(), ready_.end());
        ready_.clear();
    }
    for (const Ready& r : batch) {
        const int fence = fn(r.frame, r.buffer);  // GPU work happens here, off the lock
        std::lock_guard<std::mutex> lock(mu_);
        busy_.push_back({r.buffer, fence});
    }
    cv_.notify_all();  // the worker may have been waiting for room
}

void FfmpegDecoder::markResolved(int64_t frame) {
    {
        std::lock_guard<std::mutex> lock(mu_);
        inFlight_.erase(frame);
        dirty_ = true;
    }
    cv_.notify_all();
}

bool FfmpegDecoder::isUnavailable(int64_t frame) const {
    std::lock_guard<std::mutex> lock(mu_);
    if (unavailable_.count(frame) != 0) return true;
    return frame > lastFrame_ && (eos_ || info_.durationFrames != kUnknownDurationFrames);
}

void FfmpegDecoder::recover(int64_t frame) {
    {
        std::lock_guard<std::mutex> lock(mu_);
        inFlight_.clear();
        for (const Ready& r : ready_) free_.push_back(r.buffer);
        ready_.clear();
        plan_.forced = true;
        dirty_ = true;
    }
    target_.store(frame);
    cv_.notify_all();
}

std::string FfmpegDecoder::describe() const {
    char buf[200];
    std::lock_guard<std::mutex> lock(mu_);
    std::snprintf(buf, sizeof(buf), "software decoder: decodePos=%lld seekGoal=%lld seeks=%lld decoded=%lld discarded=%lld ready=%zu inFlight=%zu eos=%d failed=%d",
                  static_cast<long long>(sharedDecodePos_.load()), static_cast<long long>(sharedSeekGoal_.load()),
                  static_cast<long long>(seeks_.load()), static_cast<long long>(framesDecoded_.load()),
                  static_cast<long long>(discarded_.load()), ready_.size(), inFlight_.size(), eos_ ? 1 : 0, failed_ ? 1 : 0);
    return buf;
}

void FfmpegDecoder::reportError(Status code, const std::string& message) {
    UV_LOGE("software decoder: %s", message.c_str());
    if (callbacks_.onError) callbacks_.onError(Error{code, message});
}

bool FfmpegDecoder::have(int64_t frame) {
    {
        std::lock_guard<std::mutex> lock(mu_);
        if (inFlight_.count(frame) != 0 || unavailable_.count(frame) != 0) return true;
        if (frame > lastFrame_ && (eos_ || info_.durationFrames != kUnknownDurationFrames)) return true;
    }
    return callbacks_.isCached && callbacks_.isCached(frame);  // outside mu_: the cache has its own lock
}

void FfmpegDecoder::reclaimLocked() {
    for (auto it = busy_.begin(); it != busy_.end();) {
        if (fenceSignalled(it->fence)) {
            if (it->fence >= 0) close(it->fence);
            free_.push_back(it->buffer);
            it = busy_.erase(it);
        } else {
            ++it;
        }
    }
}

AHardwareBuffer* FfmpegDecoder::acquireBuffer() {
    const auto deadline = std::chrono::steady_clock::now() + kBufferWait;
    for (;;) {
        {
            std::lock_guard<std::mutex> lock(mu_);
            if (stop_) return nullptr;
            reclaimLocked();
            if (!free_.empty()) {
                AHardwareBuffer* b = free_.back();
                free_.pop_back();
                return b;
            }
            if (allocated_ < kMaxBuffers) {
                ++allocated_;
                AHardwareBuffer_Desc desc{};
                desc.width = static_cast<uint32_t>(info_.width);
                desc.height = static_cast<uint32_t>(info_.height);
                desc.layers = 1;
                desc.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
                desc.usage = AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE;
                AHardwareBuffer* b = nullptr;
                if (AHardwareBuffer_allocate(&desc, &b) == 0 && b != nullptr) return b;
                --allocated_;
                return nullptr;  // out of memory: the caller reports it
            }
        }
        if (std::chrono::steady_clock::now() > deadline) return nullptr;
        std::unique_lock<std::mutex> lock(mu_);
        cv_.wait_for(lock, std::chrono::milliseconds(2));
    }
}

void FfmpegDecoder::threadMain() {
    for (;;) {
        {
            std::lock_guard<std::mutex> lock(mu_);
            if (stop_) return;
        }
        if (failed_ || !step()) {
            std::unique_lock<std::mutex> lock(mu_);
            if (stop_) return;
            if (!dirty_) cv_.wait_for(lock, kIdleWait);
            dirty_ = false;
        }
    }
}

bool FfmpegDecoder::step() {
    // Back off while the consumer has not taken what is ready: converting more would only pile up.
    {
        std::lock_guard<std::mutex> lock(mu_);
        reclaimLocked();
        if (ready_.size() >= kMaxReady) return false;
    }

    const int64_t target = target_.load();
    int64_t lastFrame = 0;
    {
        std::lock_guard<std::mutex> lock(mu_);
        lastFrame = lastFrame_;
    }
    const Window w = windowFor(target, lookBehind_.load(), lookAhead_.load(), lastFrame);
    PlanState state;
    {
        std::lock_guard<std::mutex> lock(mu_);
        state = plan_;
    }
    const Plan plan = planStep(state, w, [this](int64_t f) { return have(f); });
    if (plan.step == Step::Idle) return false;

    std::string error;
    if (plan.step == Step::Seek) {
        if (!reader_->seek(plan.missing, &error)) {
            failed_ = true;
            reportError(Status::CodecError, "seek failed at frame " + std::to_string(plan.missing) + ": " + error);
            return false;
        }
        std::lock_guard<std::mutex> lock(mu_);
        plan_.forced = false;
        plan_.primed = true;
        plan_.awaitingFirstOutput = true;
        plan_.seekGoal = plan.missing;
        eos_ = false;
        seeks_.fetch_add(1);
        sharedSeekGoal_.store(plan.missing);
    }

    for (int i = 0; i < kDecodeBatch; ++i) {
        {
            std::lock_guard<std::mutex> lock(mu_);
            if (stop_ || ready_.size() >= kMaxReady) return true;
        }
        int64_t frame = 0;
        const ReadStatus status = reader_->decode(&frame, &error);
        if (status == ReadStatus::Failed) {
            failed_ = true;
            reportError(Status::CodecError, "decode failed near frame " + std::to_string(plan.missing) + ": " + error);
            return false;
        }
        if (status == ReadStatus::EndOfStream) {
            std::lock_guard<std::mutex> lock(mu_);
            eos_ = true;
            lastFrame_ = plan_.awaitingFirstOutput ? lastFrame_ : std::max<int64_t>(plan_.decodePos - 1, 0);
            plan_.awaitingFirstOutput = false;
            plan_.seekGoal = -1;
            sharedSeekGoal_.store(-1);
            return true;
        }

        framesDecoded_.fetch_add(1);
        {
            std::lock_guard<std::mutex> lock(mu_);
            if (!plan_.awaitingFirstOutput && frame > plan_.decodePos) {
                // The stream skipped these indices (variable frame rate): they will never arrive.
                for (int64_t f = plan_.decodePos; f < frame && f < plan_.decodePos + 4096; ++f) unavailable_.insert(f);
            }
            plan_.awaitingFirstOutput = false;
            plan_.decodePos = frame + 1;
            if (plan_.seekGoal >= 0 && frame >= plan_.seekGoal) {
                plan_.seekGoal = -1;
                sharedSeekGoal_.store(-1);
            }
            if (eos_ && frame > lastFrame_) lastFrame_ = frame;
            if (!eos_ && info_.durationFrames == kUnknownDurationFrames && frame > lastFrame_) lastFrame_ = frame;
            sharedDecodePos_.store(plan_.decodePos);
        }

        // Keep only what the window still wants; everything else was decoded just to reach it.
        const Window now = windowFor(target_.load(), lookBehind_.load(), lookAhead_.load(), std::max(lastFrame, frame));
        if (frame >= now.lo && frame <= now.hi && !have(frame)) {
            AHardwareBuffer* buffer = acquireBuffer();
            if (buffer == nullptr) {
                {
                    std::lock_guard<std::mutex> lock(mu_);
                    if (stop_) return true;
                }
                reportError(Status::OutOfMemory, "no buffer for a software-decoded frame");
                failed_ = true;
                return false;
            }
            AHardwareBuffer_Desc desc{};
            AHardwareBuffer_describe(buffer, &desc);
            void* address = nullptr;
            if (AHardwareBuffer_lock(buffer, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1, nullptr, &address) != 0 || address == nullptr) {
                {
                    std::lock_guard<std::mutex> lock(mu_);
                    free_.push_back(buffer);
                }
                failed_ = true;
                reportError(Status::CodecError, "cannot map a frame buffer for writing");
                return false;
            }
            const bool converted = reader_->convertRgba(static_cast<uint8_t*>(address), static_cast<int32_t>(desc.stride * 4), &error);
            AHardwareBuffer_unlock(buffer, nullptr);
            if (!converted) {
                {
                    std::lock_guard<std::mutex> lock(mu_);
                    free_.push_back(buffer);
                }
                failed_ = true;
                reportError(Status::CodecError, "colour conversion failed: " + error);
                return false;
            }
            {
                std::lock_guard<std::mutex> lock(mu_);
                ready_.push_back({frame, buffer});
                inFlight_.insert(frame);
            }
            if (callbacks_.onImageAvailable) callbacks_.onImageAvailable();
        } else {
            discarded_.fetch_add(1);
        }
        if (frame >= plan.missing) return true;  // look at the playhead again
    }
    return true;
}

bool available() { return true; }

Result<std::unique_ptr<IVideoDecoder>> openSoftwareDecoder(int fd, Rational fpsOverride, DecoderCallbacks callbacks) {
    return FfmpegDecoder::open(fd, fpsOverride, std::move(callbacks));
}

}  // namespace uv::decode::ffmpeg
