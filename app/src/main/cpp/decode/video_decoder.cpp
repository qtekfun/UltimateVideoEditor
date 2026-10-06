#include "decode/video_decoder.h"

#include <media/NdkMediaFormat.h>
#include <sys/system_properties.h>
#include <unistd.h>

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "core/codec_config.h"
#include "decode/decoder_ladder.h"
#include "decode/log.h"
#include "decode/pending_policy.h"
#include "decode/seek_policy.h"

namespace uv::decode {

namespace {


constexpr int64_t kPendingTimeoutMs = 400;       // a frame still missing this long after a drain that followed its release is retried
constexpr int64_t kPendingHardTimeoutMs = 5000;  // and one nobody drained for this long is retried anyway
constexpr size_t kMaxInFlight = kMaxInFlightFrames;  // see decode/pending_policy.h
constexpr int64_t kMaxGapFrames = 8;  // a larger jump is not a frame-rate difference: leave it to the seek logic
constexpr int64_t kUnknownDuration = INT64_MAX / 4;

// Debug switch for A/B runs: `adb shell setprop debug.uveditor.decode_gap 0` turns the gap marking below off for new decoders.
bool gapMarking() {
    static const bool on = [] {
        char value[PROP_VALUE_MAX] = {};
        return !(__system_property_get("debug.uveditor.decode_gap", value) > 0 && value[0] == '0');
    }();
    return on;
}

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
    // Every extractor call that reads the file holds the file's lock (core/file_lock.h): readers sharing a file offset
    // must not interleave.
    d->fileLock_ = core::fileLockFor(fd);
    std::unique_lock<std::mutex> ioLock(*d->fileLock_);

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
    int32_t rotation = 0;
    if (AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_ROTATION, &rotation)) d->info_.rotationDegrees = rotation;

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
    d->sharedLastFrame_.store(d->lastFrame_);
    ioLock.unlock();

    // Opening walks the ladder in decode/decoder_ladder.h: the first configuration whose codec starts wins and every
    // failed rung is logged with its reason, so an unknown device degrades to a working decoder instead of showing a
    // black preview.
    const std::vector<DecoderRung> ladder = buildDecoderLadder(mimeCopy);
    std::string failures;
    bool started = false;
    for (const DecoderRung& rung : ladder) {
        const char* label = rung.label.c_str();
        const media_status_t readerStatus = AImageReader_newWithUsage(
            width, height, rung.format == ReaderFormat::Private ? AIMAGE_FORMAT_PRIVATE : AIMAGE_FORMAT_YUV_420_888,
            AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE, rung.maxImages, &d->reader_);
        if (readerStatus != AMEDIA_OK || d->reader_ == nullptr) {
            d->reader_ = nullptr;
            UV_LOGW("decoder rung '%s': AImageReader_newWithUsage failed (%d)", label, static_cast<int>(readerStatus));
            failures += rung.label + ": reader " + std::to_string(readerStatus) + "; ";
            continue;
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

        d->codec_ = rung.software() ? AMediaCodec_createCodecByName(rung.codecName.c_str())
                                    : AMediaCodec_createDecoderByType(mimeCopy.c_str());
        if (d->codec_ == nullptr) {
            UV_LOGW("decoder rung '%s': no codec", label);
            failures += rung.label + ": no codec; ";
        } else {
            const media_status_t configured = AMediaCodec_configure(d->codec_, format, window, nullptr, 0);
            const media_status_t result = configured == AMEDIA_OK ? AMediaCodec_start(d->codec_) : configured;
            if (result == AMEDIA_OK) {
                UV_LOGI("decoder rung '%s' started", label);
                d->softwareDecoder_ = rung.software();
                d->rungLabel_ = rung.label;
                started = true;
                break;
            }
            UV_LOGW("decoder rung '%s': %s failed (%d)", label, configured != AMEDIA_OK ? "configure" : "start",
                    static_cast<int>(result));
            failures += rung.label + (configured != AMEDIA_OK ? ": configure " : ": start ") + std::to_string(result) + "; ";
            AMediaCodec_delete(d->codec_);
            d->codec_ = nullptr;
        }
        AImageReader_delete(d->reader_);
        d->reader_ = nullptr;
    }
    if (started) d->codecConfig_ = core::captureCodecConfig(format);  // queued again after every flush
    AMediaFormat_delete(format);
    if (!started) {
        return Error{Status::CodecError, "this device could not start a " + mimeCopy + " decoder for " +
                                             std::to_string(width) + "x" + std::to_string(height) +
                                             " video, hardware or software (" + failures + ")"};
    }

    UV_LOGI("opened %s %dx%d %lld/%lld fps, %lld frames, transfer=%d rotation=%d%s", mimeCopy.c_str(), width, height,
            static_cast<long long>(d->info_.fps.num), static_cast<long long>(d->info_.fps.den),
            static_cast<long long>(d->info_.durationFrames), d->info_.colorTransfer, d->info_.rotationDegrees,
            d->softwareDecoder_ ? " (software decoder)" : "");

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

void VideoDecoder::drainImages(const std::function<int(int64_t, AHardwareBuffer*)>& fn) {
    std::lock_guard<std::mutex> lock(readerMu_);
    if (reader_ == nullptr) return;
    // Anything released more than the timeout before this moment and still absent after it is lost.
    struct StampOnExit {
        std::atomic<int64_t>& slot;
        ~StampOnExit() { slot.store(nowMs()); }
    } stamp{lastDrainMs_};
    for (;;) {
        AImage* image = nullptr;
        if (AImageReader_acquireNextImage(reader_, &image) != AMEDIA_OK || image == nullptr) break;
        int64_t timestampNs = 0;
        AHardwareBuffer* buffer = nullptr;
        int releaseFence = -1;
        if (AImage_getTimestamp(image, &timestampNs) == AMEDIA_OK &&
            AImage_getHardwareBuffer(image, &buffer) == AMEDIA_OK && buffer != nullptr) {
            releaseFence = fn(ptsToFrame(timestampNs / 1000), buffer);
        } else {
            reportError(Status::CodecError, "decoded image has no hardware buffer or timestamp");
        }
        AImage_deleteAsync(image, releaseFence);
    }
}

void VideoDecoder::markResolved(int64_t frame) {
    {
        std::lock_guard<std::mutex> lock(pendingMu_);
        pending_.erase(frame);
    }
    pendingCv_.notify_all();
}

bool VideoDecoder::isUnavailable(int64_t frame) const {
    if (frame > sharedLastFrame_.load()) return true;
    std::lock_guard<std::mutex> lock(unavailableMu_);
    return unavailable_.count(frame) != 0;
}

void VideoDecoder::recover(int64_t frame) {
    {
        std::lock_guard<std::mutex> lock(pendingMu_);
        pending_.clear();
    }
    pendingCv_.notify_all();
    UV_LOGW("recovering the decoder for frame %lld (%s)", static_cast<long long>(frame), describe().c_str());
    forceSeek_.store(true);
    setTarget(frame);
}

std::string VideoDecoder::describe() const {
    char buf[320];
    std::snprintf(buf, sizeof(buf),
                  "target=%lld decodePos=%lld lastOut=%lld seekGoal=%lld lastFrame=%lld seeks=%lld unavailable=%lld "
                  "primed=%d inputEos=%d failed=%d window=-%d/+%d decoded=%lld",
                  static_cast<long long>(target_.load()), static_cast<long long>(sharedDecodePos_.load()),
                  static_cast<long long>(sharedLastOutFrame_.load()), static_cast<long long>(sharedSeekGoal_.load()),
                  static_cast<long long>(sharedLastFrame_.load()), static_cast<long long>(sharedSeeks_.load()),
                  static_cast<long long>(sharedMarkedUnavailable_.load()), sharedPrimed_.load(), sharedInputEos_.load(),
                  sharedFailed_.load(), lookBehind_.load(), lookAhead_.load(),
                  static_cast<long long>(framesDecoded_.load()));
    return buf;
}

bool VideoDecoder::pendingExpired(int64_t releasedMs) const {
    return pendingExpiredAfterDrain(releasedMs, lastDrainMs_.load(), nowMs(), kPendingTimeoutMs, kPendingHardTimeoutMs);
}

size_t VideoDecoder::inFlightCount() {
    std::lock_guard<std::mutex> lock(pendingMu_);
    for (auto it = pending_.begin(); it != pending_.end();) {
        it = pendingExpired(it->second) ? pending_.erase(it) : std::next(it);
    }
    return pending_.size();
}

void VideoDecoder::reportError(Status code, const std::string& message) {
    UV_LOGE("decoder error %d: %s", static_cast<int>(code), message.c_str());
    if (callbacks_.onError) callbacks_.onError(Error{code, message});
}

bool VideoDecoder::needsFrame(int64_t frame) {
    {
        std::lock_guard<std::mutex> lock(unavailableMu_);
        if (unavailable_.count(frame) != 0) return false;
    }
    if (callbacks_.isCached && callbacks_.isCached(frame)) return false;
    std::lock_guard<std::mutex> lock(pendingMu_);
    auto it = pending_.find(frame);
    if (it == pending_.end()) return true;
    if (pendingExpired(it->second)) {  // released, the consumer drained since, and it never showed up: retry
        pending_.erase(it);
        return true;
    }
    return false;
}

bool VideoDecoder::nothingNeededBefore(int64_t target, int64_t frame) {
    for (int64_t f = std::clamp<int64_t>(target, 0, lastFrame_); f < frame; ++f) {
        if (needsFrame(f)) return false;
    }
    return true;
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
    // Every frame of the window is known to be absent from the stream (a seek landed just before an open GOP, or the clip's
    // rate is lower than the timeline's): the consumer then has nothing to show but a neighbour. Fetch the next real frame
    // beyond the window, or the consumer waits for ever while this thread idles (the stall of the failed 4K export).
    {
        std::lock_guard<std::mutex> lock(unavailableMu_);
        for (int64_t f = clamped; f <= hi; ++f) {
            if (unavailable_.count(f) == 0) return false;
        }
    }
    const int64_t reach = std::min<int64_t>(lastFrame_, hi + kMaxGapFrames);
    for (int64_t f = hi + 1; f <= reach; ++f) {
        {
            std::lock_guard<std::mutex> lock(unavailableMu_);
            if (unavailable_.count(f) != 0) continue;
        }
        if (needsFrame(f)) {
            *missing = f;
            return true;
        }
        break;
    }
    return false;
}

void VideoDecoder::logDiag() {
    const auto now = std::chrono::steady_clock::now();
    if (diag_.last.time_since_epoch().count() == 0) diag_.last = now;
    if (now - diag_.last < std::chrono::seconds(1)) return;
    UV_LOGI("decode/s: rendered %lld dropped %lld seeks %lld backpressure %lld dequeue wait %.1f ms (%lld empty) [%s]",
            static_cast<long long>(diag_.rendered), static_cast<long long>(diag_.dropped),
            static_cast<long long>(diag_.seeks), static_cast<long long>(diag_.backpressure),
            static_cast<double>(diag_.dequeueNs) / 1e6, static_cast<long long>(diag_.dequeueEmpty), rungLabel_.c_str());
    diag_ = Diag{};
    diag_.last = now;
}

void VideoDecoder::seekTo(int64_t frame) {
    ++diag_.seeks;
    inputEos_ = false;
    const int64_t ptsUs = startPtsUs_ + frameToPtsUs(frame, info_.fps);
    media_status_t sought;
    {
        core::FileGuard io(*fileLock_);
        sought = AMediaExtractor_seekTo(extractor_, ptsUs, AMEDIAEXTRACTOR_SEEK_PREVIOUS_SYNC);
    }
    if (sought != AMEDIA_OK) {
        failed_ = true;
        sharedFailed_.store(true);
        reportError(Status::IoError, "AMediaExtractor_seekTo failed");
        return;
    }
    if (AMediaCodec_flush(codec_) != AMEDIA_OK) {
        failed_ = true;
        sharedFailed_.store(true);
        reportError(Status::CodecError, "AMediaCodec_flush failed (" + describe() + ")");
        return;
    }
    resubmitCodecConfig();
    decoderPrimed_ = true;
    awaitingFirstOutput_ = true;
    seekGoal_ = frame;
    sharedSeekGoal_.store(frame);
    sharedPrimed_.store(true);
    sharedInputEos_.store(false);
    sharedSeeks_.fetch_add(1);
}

void VideoDecoder::resubmitCodecConfig() {
    if (!core::queueCodecConfig(codec_, codecConfig_)) UV_LOGW("could not queue the codec config again after a flush");
}

void VideoDecoder::pump(int64_t lo, int64_t hi) {
    // Feed as much compressed input as the codec accepts without blocking.
    while (!inputEos_) {
        const ssize_t index = AMediaCodec_dequeueInputBuffer(codec_, 0);
        if (index < 0) break;
        size_t capacity = 0;
        uint8_t* buffer = AMediaCodec_getInputBuffer(codec_, static_cast<size_t>(index), &capacity);
        ssize_t size = -1;
        int64_t sampleTime = 0;
        if (buffer != nullptr) {
            core::FileGuard io(*fileLock_);
            size = AMediaExtractor_readSampleData(extractor_, buffer, capacity);
            if (size >= 0) {
                sampleTime = AMediaExtractor_getSampleTime(extractor_);
                AMediaExtractor_advance(extractor_);
            }
        }
        if (size < 0) {
            AMediaCodec_queueInputBuffer(codec_, static_cast<size_t>(index), 0, 0, 0,
                                         AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
            inputEos_ = true;
            sharedInputEos_.store(true);
            break;
        }
        if (AMediaCodec_queueInputBuffer(codec_, static_cast<size_t>(index), 0, static_cast<size_t>(size),
                                         static_cast<uint64_t>(std::max<int64_t>(sampleTime, 0)), 0) != AMEDIA_OK) {
            failed_ = true;
            sharedFailed_.store(true);
            reportError(Status::CodecError, "AMediaCodec_queueInputBuffer failed (" + describe() + ")");
            return;
        }
    }

    if (inFlightCount() >= kMaxInFlight) {  // backpressure: let the render thread catch up
        ++diag_.backpressure;
        std::unique_lock<std::mutex> lock(pendingMu_);
        pendingCv_.wait_for(lock, std::chrono::milliseconds(2));
        return;
    }

    AMediaCodecBufferInfo info{};
    const auto dequeueStart = std::chrono::steady_clock::now();
    const ssize_t out = AMediaCodec_dequeueOutputBuffer(codec_, &info, 5000);
    diag_.dequeueNs +=
        std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now() - dequeueStart).count();
    if (out == AMEDIACODEC_INFO_TRY_AGAIN_LATER) ++diag_.dequeueEmpty;
    logDiag();
    if (out == AMEDIACODEC_INFO_TRY_AGAIN_LATER || out == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED ||
        out == AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED) {
        return;
    }
    if (out < 0) {
        failed_ = true;
        sharedFailed_.store(true);
        reportError(Status::CodecError,
                    "AMediaCodec_dequeueOutputBuffer failed (" + std::to_string(out) + "; " + describe() + ")");
        return;
    }

    bool render = false;
    if (info.size > 0) {
        const int64_t frame = ptsToFrame(info.presentationTimeUs);
        if (seekGoal_ >= 0 && frame >= seekGoal_) {
            if (frame > seekGoal_) {  // the stream skipped it
                {
                    std::lock_guard<std::mutex> lock(unavailableMu_);
                    unavailable_.insert(seekGoal_);
                }
                sharedMarkedUnavailable_.fetch_add(1);
                UV_LOGW("frame %lld never produced: first output after the seek was %lld (pts %lld us, start %lld us)",
                        static_cast<long long>(seekGoal_), static_cast<long long>(frame),
                        static_cast<long long>(info.presentationTimeUs), static_cast<long long>(startPtsUs_));
            }
            seekGoal_ = -1;
            sharedSeekGoal_.store(-1);
        }
        // Outputs come in presentation order, so within one decode run a jump in frame numbers names frames the stream does not
        // have (a 30 fps clip on a 60 fps grid skips every other one). Say so now: waiting for such a frame would otherwise end
        // in a seek back to the previous key frame (needsSeek: the decoder is already past it).
        if (gapMarking() && !awaitingFirstOutput_ && decodePos_ > 0 && frame > decodePos_ && frame - decodePos_ <= kMaxGapFrames) {
            std::lock_guard<std::mutex> lock(unavailableMu_);
            for (int64_t gap = decodePos_; gap < frame; ++gap) unavailable_.insert(gap);
        }
        awaitingFirstOutput_ = false;
        decodePos_ = frame + 1;
        sharedDecodePos_.store(decodePos_);
        sharedLastOutFrame_.store(frame);
        // The window counts timeline frames, so over a stretch the stream lacks (a 27 fps clip on a 60 fps grid has gaps of up to
        // three) the first real frame can lie just beyond it. Dropping it would mean a seek back to the key frame to get it, so it
        // is kept when nothing nearer is still needed.
        bool inWindow = frame >= lo && frame <= hi;
        if (!inWindow && frame > hi && frame - hi <= kMaxGapFrames && nothingNeededBefore(target_.load(), frame)) inWindow = true;
        if (inWindow && needsFrame(frame)) {
            render = true;
            std::lock_guard<std::mutex> lock(pendingMu_);
            pending_[frame] = nowMs();
        }
    }
    if (render) {
        framesDecoded_.fetch_add(1);
        ++diag_.rendered;
    } else if (info.size > 0) {
        ++diag_.dropped;
    }
    AMediaCodec_releaseOutputBuffer(codec_, static_cast<size_t>(out), render);

    if ((info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) != 0) {
        decoderPrimed_ = false;  // next work needs a flush + seek
        sharedPrimed_.store(false);
        const int64_t declaredLast = lastFrame_;
        if (decodePos_ > 0) lastFrame_ = std::min(lastFrame_, decodePos_ - 1);
        sharedLastFrame_.store(lastFrame_);
        if (seekGoal_ >= 0) {
            // The stream ended before the frame the last seek was after: it will never come. Without this the same frame
            // stays "missing" and every call seeks to it again (one seek per frame past the end of a clip).
            {
                std::lock_guard<std::mutex> lock(unavailableMu_);
                unavailable_.insert(seekGoal_);
            }
            sharedMarkedUnavailable_.fetch_add(1);
            UV_LOGW("frame %lld is past the end of the stream (last frame %lld)", static_cast<long long>(seekGoal_),
                    static_cast<long long>(lastFrame_));
            seekGoal_ = -1;
            sharedSeekGoal_.store(-1);
        }
        // A stream that ends well before the length its container declares was cut short by a read error, not by the end
        // of the file (MP4 reader errors end the stream like a real end). Say so: playing on with the last frame would make
        // an export silently wrong.
        const int64_t shortBy = declaredLast - lastFrame_ - 1;
        if (info_.durationFrames > 0 && shortBy > earlyEndToleranceFrames(info_.fps, declaredLast + 1) && !earlyEndReported_) {
            earlyEndReported_ = true;
            sharedEarlyEnd_.store(true);
            reportError(Status::IoError, "the video stream ends at frame " + std::to_string(lastFrame_ + 1) + " of " +
                                             std::to_string(declaredLast + 1) + ": the file could not be read to its end");
        }
    }
}

bool VideoDecoder::step(int64_t target) {
    if (failed_) return true;
    int64_t missing = 0;
    if (!findMissing(target, &missing)) return true;

    const int64_t clamped = std::clamp<int64_t>(target, 0, lastFrame_);
    const int64_t lo = std::max<int64_t>(0, clamped - lookBehind_.load());
    const int64_t hi = std::min<int64_t>(lastFrame_, clamped + lookAhead_.load());

    const bool forced = forceSeek_.exchange(false);
    const bool needSeek = needsSeek(forced, decoderPrimed_, awaitingFirstOutput_, decodePos_, missing, seekGoal_);
    if (needSeek) {
        UV_LOGI("seek: missing=%lld decodePos=%lld target=%lld primed=%d awaiting=%d", static_cast<long long>(missing),
                static_cast<long long>(decodePos_), static_cast<long long>(target), decoderPrimed_, awaitingFirstOutput_);
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
