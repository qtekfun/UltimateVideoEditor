#include "stabilise/stab_service.h"

#include <android/log.h>
#include <sys/resource.h>
#include <unistd.h>

#include <algorithm>
#include <vector>

#include "stabilise/luma_decoder.h"
#include "stabilise/motion_analyser.h"
#include "stabilise/stab_cache.h"

#define LOG_TAG "uv_stab"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace uv::stab {

using core::Status;

namespace {
constexpr int kBackgroundNice = 10;     // ANDROID_PRIORITY_BACKGROUND
constexpr int kAnalysisDimension = 480; // long side of the analysis frames
constexpr size_t kMinSamples = 8;       // fewer frames than this carry no usable camera path
}  // namespace

StabService::~StabService() {
    cancel_.store(true);
    if (worker_.joinable()) worker_.join();
}

Status StabService::start(int fd, int64_t startUs, int64_t endUs, std::string cachePath) {
    std::lock_guard<std::mutex> lock(mu_);
    if (state_ == State::Running || fd < 0 || cachePath.empty()) {
        if (fd >= 0) ::close(fd);
        return Status::InvalidArgument;
    }
    if (worker_.joinable()) worker_.join();  // the previous job is over
    state_ = State::Running;
    error_ = Status::Ok;
    permille_.store(0);
    cancel_.store(false);
    worker_ = std::thread([this, fd, startUs, endUs, path = std::move(cachePath)]() mutable { run(fd, startUs, endUs, std::move(path)); });
    return Status::Ok;
}

void StabService::cancel() { cancel_.store(true); }

StabService::Poll StabService::poll() const {
    std::lock_guard<std::mutex> lock(mu_);
    Poll p;
    p.state = state_;
    p.permille = state_ == State::Done ? 1000 : permille_.load();
    p.error = error_;
    return p;
}

void StabService::finish(State state, Status error) {
    std::lock_guard<std::mutex> lock(mu_);
    state_ = state;
    error_ = error;
}

void StabService::run(int fd, int64_t startUs, int64_t endUs, std::string cachePath) {
    setpriority(PRIO_PROCESS, 0, kBackgroundNice);
    Status status = Status::Ok;
    std::vector<MotionSample> samples;
    CacheHeader header;
    {
        std::unique_ptr<LumaDecoder> decoder = LumaDecoder::open(fd, &status);
        if (decoder == nullptr) {
            ::close(fd);
            finish(State::Failed, status);
            return;
        }
        const int64_t duration = decoder->durationUs();
        const int64_t to = endUs > 0 ? (duration > 0 ? std::min(endUs, duration) : endUs) : duration;
        const int64_t span = std::max<int64_t>(1, to - startUs);
        MotionAnalyser analyser;
        int width = 0, height = 0;
        status = decoder->run(startUs, to, kAnalysisDimension, cancel_, [&](int64_t pts, const Gray& luma) {
            const FrameMotion motion = analyser.feed(luma);
            width = luma.w;
            height = luma.h;
            samples.push_back({pts, motion});
            permille_.store(static_cast<int>(std::clamp<int64_t>((pts - startUs) * 1000 / span, 0, 999)));
            return true;
        });
        header.analysisWidth = width;
        header.analysisHeight = height;
        header.aspect = height > 0 ? static_cast<float>(width) / static_cast<float>(height) : 16.0f / 9.0f;
        // `decoder` is destroyed here, before the file descriptor is closed and before the cache is written.
    }
    ::close(fd);
    if (status == Status::Cancelled) {
        finish(State::Cancelled, Status::Cancelled);
        return;
    }
    if (status != Status::Ok) {
        finish(State::Failed, status);
        return;
    }
    if (samples.size() < kMinSamples) {
        LOGE("analysis produced only %zu frames", samples.size());
        finish(State::Failed, Status::UnsupportedFormat);
        return;
    }
    header.rangeStartUs = samples.front().ptsUs;
    header.rangeEndUs = samples.back().ptsUs;
    const Status written = writeCache(cachePath, header, samples);
    if (written != Status::Ok) {
        finish(State::Failed, written);
        return;
    }
    LOGI("analysed %zu frames (%lld..%lld us) into %s", samples.size(), static_cast<long long>(header.rangeStartUs),
         static_cast<long long>(header.rangeEndUs), cachePath.c_str());
    finish(State::Done, Status::Ok);
}

}  // namespace uv::stab
