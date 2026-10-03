#include "decode/video_decoder.h"

#include <media/NdkMediaFormat.h>
#include <unistd.h>

#include <algorithm>
#include <chrono>
#include <cstring>
#include <string>
#include <vector>

#include "decode/log.h"

namespace uv::decode {

namespace {

constexpr int64_t kMaxForwardSkipFrames = 120;   // decode forward instead of seeking up to this far
constexpr int64_t kPendingTimeoutMs = 400;       // a released frame that never shows up is retried
// Frames released to the reader but not yet blitted. Releasing faster than the render thread
// drains makes the buffer queue drop frames, which then get decoded again.
constexpr size_t kMaxInFlight = 4;
constexpr int64_t kUnknownDuration = INT64_MAX / 4;

int64_t nowMs() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
               std::chrono::steady_clock::now().time_since_epoch())
        .count();
}

bool isSupportedMime(const char* mime) {
    return mime != nullptr && (std::strcmp(mime, "video/avc") == 0 || std::strcmp(mime, "video/hevc") == 0);
}

// Median-free estimate: the smallest positive gap between presentation times of the first samples.
Rational estimateFrameRate(AMediaExtractor* extractor, int64_t* firstPtsUs) {
    std::vector<int64_t> times;
    for (int i = 0; i < 64; ++i) {
        const int64_t t = AMediaExtractor_getSampleTime(extractor);
        if (t < 0) break;
        times.push_back(t);
        if (!AMediaExtractor_advance(extractor)) break;
    }
    *firstPtsUs = times.empty() ? 0 : *std::min_element(times.begin(), times.end());
    std::sort(times.begin(), times.end());
    int64_t best = 0;
    for (size_t i = 1; i < times.size(); ++i) {
        const int64_t d = times[i] - times[i - 1];
        if (d > 0 && (best == 0 || d < best)) best = d;
    }
    if (best == 0) return {30, 1};
    return snapFrameRate(1000000.0 / static_cast<double>(best));
}

}  // namespace

Result<std::unique_ptr<VideoDecoder>> VideoDecoder::open(int fd, Rational fpsOverride, Callbacks callbacks) {
    std::unique_ptr<VideoDecoder> d(new VideoDecoder());
    d->fd_ = fd;  // from here on the destructor closes it, including on every error path
    d->callbacks_ = std::move(callbacks);

    const off_t length = lseek(fd, 0, SEEK_END);
    if (length <= 0 || lseek(fd, 0, SEEK_SET) < 0) return Error{Status::IoError, "cannot determine media size"};

    d->extractor_ = AMediaExtractor_new();
    if (AMediaExtractor_setDataSourceFd(d->extractor_, fd, 0, length) != AMEDIA_OK) {
        return Error{Status::IoError, "AMediaExtractor_setDataSourceFd failed"};
    }

    AMediaFormat* format = nullptr;
    const char* mime = nullptr;
    const size_t tracks = AMediaExtractor_getTrackCount(d->extractor_);
    size_t trackIndex = 0;
    bool found = false;
    bool sawVideo = false;
    for (size_t i = 0; i < tracks && !found; ++i) {
        AMediaFormat* f = AMediaExtractor_getTrackFormat(d->extractor_, i);
        const char* m = nullptr;
        if (AMediaFormat_getString(f, AMEDIAFORMAT_KEY_MIME, &m) && m != nullptr && std::strncmp(m, "video/", 6) == 0) {
            sawVideo = true;
            if (isSupportedMime(m)) {
                format = f;
                mime = m;
                trackIndex = i;
                found = true;
                continue;
            }
        }
        AMediaFormat_delete(f);
    }
    if (!found) {
        return Error{sawVideo ? Status::UnsupportedFormat : Status::NotFound,
                     sawVideo ? "only H.264 and HEVC video are supported" : "no video track found"};
    }
    // `mime` points into `format`; copy before the format is deleted.
    const std::string mimeCopy(mime);

    int32_t width = 0;
    int32_t height = 0;
    if (!AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_WIDTH, &width) ||
        !AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_HEIGHT, &height) || width <= 0 || height <= 0) {
        AMediaFormat_delete(format);
        return Error{Status::UnsupportedFormat, "video track has no dimensions"};
    }
    d->info_.width = width;
    d->info_.height = height;

    int64_t durationUs = 0;
    const bool hasDuration = AMediaFormat_getInt64(format, AMEDIAFORMAT_KEY_DURATION, &durationUs) && durationUs > 0;
    int32_t transfer = 0;
    if (AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_COLOR_TRANSFER, &transfer)) d->info_.colorTransfer = transfer;

    AMediaExtractor_selectTrack(d->extractor_, trackIndex);

    // Frame rate: explicit override, else the container value, else measured from sample times.
    float fpsFloat = 0.0f;
    int32_t fpsInt = 0;
    int64_t firstPts = 0;
    if (fpsOverride.num > 0 && fpsOverride.den > 0) {
        d->info_.fps = fpsOverride;
        firstPts = std::max<int64_t>(AMediaExtractor_getSampleTime(d->extractor_), 0);
    } else if (AMediaFormat_getFloat(format, AMEDIAFORMAT_KEY_FRAME_RATE, &fpsFloat) && fpsFloat > 1.0f) {
        d->info_.fps = snapFrameRate(static_cast<double>(fpsFloat));
        firstPts = std::max<int64_t>(AMediaExtractor_getSampleTime(d->extractor_), 0);
    } else if (AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_FRAME_RATE, &fpsInt) && fpsInt > 1) {
        d->info_.fps = snapFrameRate(static_cast<double>(fpsInt));
        firstPts = std::max<int64_t>(AMediaExtractor_getSampleTime(d->extractor_), 0);
    } else {
        d->info_.fps = estimateFrameRate(d->extractor_, &firstPts);
        AMediaExtractor_seekTo(d->extractor_, 0, AMEDIAEXTRACTOR_SEEK_PREVIOUS_SYNC);
    }
    d->startPtsUs_ = firstPts;
    d->info_.durationFrames = hasDuration ? ptsUsToFrame(durationUs, d->info_.fps) : kUnknownDuration;
    d->lastFrame_ = d->info_.durationFrames > 0 ? d->info_.durationFrames - 1 : 0;

    // Decoder output goes to an AImageReader so every frame arrives as an AHardwareBuffer.
    constexpr int32_t kMaxImages = 8;
    media_status_t st = AImageReader_newWithUsage(width, height, AIMAGE_FORMAT_PRIVATE,
                                                  AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE, kMaxImages, &d->reader_);
    if (st != AMEDIA_OK || d->reader_ == nullptr) {
        AMediaFormat_delete(format);
        return Error{Status::CodecError, "AImageReader_newWithUsage failed (" + std::to_string(st) + ")"};
    }
    AImageReader_ImageListener listener{};
    listener.context = d.get();
    listener.onImageAvailable = [](void* context, AImageReader*) {
        auto* self = static_cast<VideoDecoder*>(context);
        if (self->callbacks_.onImageAvailable) self->callbacks_.onImageAvailable();
    };
    AImageReader_setImageListener(d->reader_, &listener);

    ANativeWindow* window = nullptr;
    AImageReader_getWindow(d->reader_, &window);

    d->codec_ = AMediaCodec_createDecoderByType(mimeCopy.c_str());
    if (d->codec_ == nullptr) {
        AMediaFormat_delete(format);
        return Error{Status::UnsupportedFormat, "no decoder available for " + mimeCopy};
    }
    st = AMediaCodec_configure(d->codec_, format, window, nullptr, 0);
    AMediaFormat_delete(format);
    if (st != AMEDIA_OK) return Error{Status::CodecError, "AMediaCodec_configure failed (" + std::to_string(st) + ")"};
    st = AMediaCodec_start(d->codec_);
    if (st != AMEDIA_OK) return Error{Status::CodecError, "AMediaCodec_start failed (" + std::to_string(st) + ")"};

    UV_LOGI("opened %s %dx%d %lld/%lld fps, %lld frames, transfer=%d", mimeCopy.c_str(), width, height,
            static_cast<long long>(d->info_.fps.num), static_cast<long long>(d->info_.fps.den),
            static_cast<long long>(d->info_.durationFrames), d->info_.colorTransfer);

    d->thread_ = std::thread([raw = d.get()] { raw->threadMain(); });
    return d;
}

VideoDecoder::~VideoDecoder() { shutdown(); }

void VideoDecoder::shutdown() {
    {
        std::lock_guard<std::mutex> lock(mu_);
        stop_ = true;
    }
    cv_.notify_all();
    if (thread_.joinable()) thread_.join();

    std::lock_guard<std::mutex> readerLock(readerMu_);
    if (codec_ != nullptr) {
        AMediaCodec_stop(codec_);
        AMediaCodec_delete(codec_);
        codec_ = nullptr;
    }
    if (reader_ != nullptr) {
        AImageReader_delete(reader_);
        reader_ = nullptr;
    }
    if (extractor_ != nullptr) {
        AMediaExtractor_delete(extractor_);
        extractor_ = nullptr;
    }
    if (fd_ >= 0) {
        close(fd_);
        fd_ = -1;
    }
}

void VideoDecoder::setTarget(int64_t frame) {
    target_.store(frame);
    {
        std::lock_guard<std::mutex> lock(mu_);
        dirty_ = true;
    }
    cv_.notify_all();
}

void VideoDecoder::setWindow(int32_t lookBehind, int32_t lookAhead) {
    lookBehind_.store(std::max(lookBehind, 0));
    lookAhead_.store(std::max(lookAhead, 0));
    setTarget(target_.load());
}

int64_t VideoDecoder::ptsToFrame(int64_t ptsUs) const { return ptsUsToFrame(ptsUs - startPtsUs_, info_.fps); }

void VideoDecoder::drainImages(const std::function<void(int64_t, AHardwareBuffer*)>& fn) {
    std::lock_guard<std::mutex> lock(readerMu_);
    if (reader_ == nullptr) return;
    for (;;) {
        AImage* image = nullptr;
        if (AImageReader_acquireNextImage(reader_, &image) != AMEDIA_OK || image == nullptr) break;
        int64_t timestampNs = 0;
        AHardwareBuffer* buffer = nullptr;
        if (AImage_getTimestamp(image, &timestampNs) == AMEDIA_OK &&
            AImage_getHardwareBuffer(image, &buffer) == AMEDIA_OK && buffer != nullptr) {
            fn(ptsToFrame(timestampNs / 1000), buffer);
        } else {
            reportError(Status::CodecError, "decoded image has no hardware buffer or timestamp");
        }
        AImage_delete(image);
    }
}

void VideoDecoder::markResolved(int64_t frame) {
    {
        std::lock_guard<std::mutex> lock(pendingMu_);
        pending_.erase(frame);
    }
    pendingCv_.notify_all();
}

size_t VideoDecoder::inFlightCount() {
    std::lock_guard<std::mutex> lock(pendingMu_);
    const int64_t now = nowMs();
    for (auto it = pending_.begin(); it != pending_.end();) {
        it = (now - it->second > kPendingTimeoutMs) ? pending_.erase(it) : std::next(it);
    }
    return pending_.size();
}

void VideoDecoder::reportError(Status code, const std::string& message) {
    UV_LOGE("decoder error %d: %s", static_cast<int>(code), message.c_str());
    if (callbacks_.onError) callbacks_.onError(Error{code, message});
}

bool VideoDecoder::needsFrame(int64_t frame) {
    if (unavailable_.count(frame) != 0) return false;
    if (callbacks_.isCached && callbacks_.isCached(frame)) return false;
    std::lock_guard<std::mutex> lock(pendingMu_);
    auto it = pending_.find(frame);
    if (it == pending_.end()) return true;
    if (nowMs() - it->second > kPendingTimeoutMs) {  // released but never delivered: retry
        pending_.erase(it);
        return true;
    }
    return false;
}

bool VideoDecoder::findMissing(int64_t target, int64_t* missing) {
    const int64_t clamped = std::clamp<int64_t>(target, 0, lastFrame_);
    const int64_t lo = std::max<int64_t>(0, clamped - lookBehind_.load());
    const int64_t hi = std::min<int64_t>(lastFrame_, clamped + lookAhead_.load());
    for (int64_t f = clamped; f <= hi; ++f) {
        if (needsFrame(f)) {
            *missing = f;
            return true;
        }
    }
    for (int64_t f = clamped - 1; f >= lo; --f) {
        if (needsFrame(f)) {
            *missing = f;
            return true;
        }
    }
    return false;
}

void VideoDecoder::seekTo(int64_t frame) {
    inputEos_ = false;
    const int64_t ptsUs = startPtsUs_ + frameToPtsUs(frame, info_.fps);
    if (AMediaExtractor_seekTo(extractor_, ptsUs, AMEDIAEXTRACTOR_SEEK_PREVIOUS_SYNC) != AMEDIA_OK) {
        failed_ = true;
        reportError(Status::IoError, "AMediaExtractor_seekTo failed");
        return;
    }
    if (AMediaCodec_flush(codec_) != AMEDIA_OK) {
        failed_ = true;
        reportError(Status::CodecError, "AMediaCodec_flush failed");
        return;
    }
    decoderPrimed_ = true;
    awaitingFirstOutput_ = true;
    seekGoal_ = frame;
}

void VideoDecoder::pump(int64_t lo, int64_t hi) {
    // Feed as much compressed input as the codec accepts without blocking.
    while (!inputEos_) {
        const ssize_t index = AMediaCodec_dequeueInputBuffer(codec_, 0);
        if (index < 0) break;
        size_t capacity = 0;
        uint8_t* buffer = AMediaCodec_getInputBuffer(codec_, static_cast<size_t>(index), &capacity);
        const ssize_t size = buffer != nullptr ? AMediaExtractor_readSampleData(extractor_, buffer, capacity) : -1;
        if (size < 0) {
            AMediaCodec_queueInputBuffer(codec_, static_cast<size_t>(index), 0, 0, 0,
                                         AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
            inputEos_ = true;
            break;
        }
        const int64_t sampleTime = AMediaExtractor_getSampleTime(extractor_);
        if (AMediaCodec_queueInputBuffer(codec_, static_cast<size_t>(index), 0, static_cast<size_t>(size),
                                         static_cast<uint64_t>(std::max<int64_t>(sampleTime, 0)), 0) != AMEDIA_OK) {
            failed_ = true;
            reportError(Status::CodecError, "AMediaCodec_queueInputBuffer failed");
            return;
        }
        AMediaExtractor_advance(extractor_);
    }

    if (inFlightCount() >= kMaxInFlight) {  // backpressure: let the render thread catch up
        std::unique_lock<std::mutex> lock(pendingMu_);
        pendingCv_.wait_for(lock, std::chrono::milliseconds(2));
        return;
    }

    AMediaCodecBufferInfo info{};
    const ssize_t out = AMediaCodec_dequeueOutputBuffer(codec_, &info, 5000);
    if (out == AMEDIACODEC_INFO_TRY_AGAIN_LATER || out == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED ||
        out == AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED) {
        return;
    }
    if (out < 0) {
        failed_ = true;
        reportError(Status::CodecError, "AMediaCodec_dequeueOutputBuffer failed (" + std::to_string(out) + ")");
        return;
    }

    bool render = false;
    if (info.size > 0) {
        const int64_t frame = ptsToFrame(info.presentationTimeUs);
        if (seekGoal_ >= 0 && frame >= seekGoal_) {
            if (frame > seekGoal_) unavailable_.insert(seekGoal_);  // the stream skipped it
            seekGoal_ = -1;
        }
        awaitingFirstOutput_ = false;
        decodePos_ = frame + 1;
        if (frame >= lo && frame <= hi && needsFrame(frame)) {
            render = true;
            std::lock_guard<std::mutex> lock(pendingMu_);
            pending_[frame] = nowMs();
        }
    }
    if (render) framesDecoded_.fetch_add(1);
    AMediaCodec_releaseOutputBuffer(codec_, static_cast<size_t>(out), render);

    if ((info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) != 0) {
        decoderPrimed_ = false;  // next work needs a flush + seek
        if (decodePos_ > 0) lastFrame_ = std::min(lastFrame_, decodePos_ - 1);
    }
}

bool VideoDecoder::step(int64_t target) {
    if (failed_) return true;
    int64_t missing = 0;
    if (!findMissing(target, &missing)) return true;

    const int64_t clamped = std::clamp<int64_t>(target, 0, lastFrame_);
    const int64_t lo = std::max<int64_t>(0, clamped - lookBehind_.load());
    const int64_t hi = std::min<int64_t>(lastFrame_, clamped + lookAhead_.load());

    const bool needSeek = !decoderPrimed_ ||
                          (!awaitingFirstOutput_ && (decodePos_ > missing || missing - decodePos_ > kMaxForwardSkipFrames));
    if (needSeek) {
        seekTo(missing);
        if (failed_) return true;
    }
    pump(lo, hi);
    return failed_;
}

void VideoDecoder::threadMain() {
    for (;;) {
        {
            std::unique_lock<std::mutex> lock(mu_);
            // The timeout re-checks frames that were released but never arrived.
            cv_.wait_for(lock, std::chrono::milliseconds(250), [this] { return stop_ || dirty_; });
            if (stop_) return;
            dirty_ = false;
        }
        for (;;) {
            {
                std::lock_guard<std::mutex> lock(mu_);
                if (stop_) return;
                if (dirty_) break;  // new target: restart with fresh window
            }
            if (step(target_.load())) break;
        }
    }
}

}  // namespace uv::decode
