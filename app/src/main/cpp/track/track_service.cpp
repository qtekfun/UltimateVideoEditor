#include "track/track_service.h"

#include <android/log.h>
#include <sys/resource.h>
#include <unistd.h>

#include <algorithm>
#include <deque>
#include <vector>

#include "stabilise/luma_decoder.h"

#define LOG_TAG "uv_track"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace uv::track {

using core::Status;

namespace {
constexpr int kBackgroundNice = 10;      // ANDROID_PRIORITY_BACKGROUND
constexpr int kTrackDimension = 320;     // long side of the tracked frames: enough for a box, light on memory
constexpr size_t kMaxFramesBefore = 900; // frames kept to track backward (about 30 s at 30 fps, ~50 MB)
}  // namespace

TrackService::~TrackService() {
    cancel_.store(true);
    if (worker_.joinable()) worker_.join();
}

Status TrackService::start(int fd, int64_t startUs, int64_t endUs, int64_t seedUs, int64_t halfFrameUs, NormBox box, std::string cachePath) {
    std::lock_guard<std::mutex> lock(mu_);
    if (state_ == State::Running || fd < 0 || cachePath.empty() || seedUs < startUs || box.w <= 0.0f || box.h <= 0.0f) {
        if (fd >= 0) ::close(fd);
        return Status::InvalidArgument;
    }
    if (worker_.joinable()) worker_.join();  // the previous job is over
    state_ = State::Running;
    error_ = Status::Ok;
    permille_.store(0);
    cancel_.store(false);
    worker_ = std::thread([this, fd, startUs, endUs, seedUs, halfFrameUs, box, path = std::move(cachePath)]() mutable {
        run(fd, startUs, endUs, seedUs, halfFrameUs, box, std::move(path));
    });
    return Status::Ok;
}

void TrackService::cancel() { cancel_.store(true); }

TrackService::Poll TrackService::poll() const {
    std::lock_guard<std::mutex> lock(mu_);
    Poll p;
    p.state = state_;
    p.permille = state_ == State::Done ? 1000 : permille_.load();
    p.error = error_;
    return p;
}

void TrackService::finish(State state, Status error) {
    std::lock_guard<std::mutex> lock(mu_);
    state_ = state;
    error_ = error;
}

void TrackService::run(int fd, int64_t startUs, int64_t endUs, int64_t seedUs, int64_t halfFrameUs, NormBox box, std::string cachePath) {
    setpriority(PRIO_PROCESS, 0, kBackgroundNice);
    Status status = Status::Ok;
    TrackHeader header;
    std::vector<TrackSample> path;
    bool seeded = false;
    {
        std::unique_ptr<stab::LumaDecoder> decoder = stab::LumaDecoder::open(fd, &status);
        if (decoder == nullptr) {
            ::close(fd);
            finish(State::Failed, status);
            return;
        }
        const int64_t duration = decoder->durationUs();
        const int64_t to = endUs > 0 ? (duration > 0 ? std::min(endUs, duration) : endUs) : duration;
        const int64_t span = std::max<int64_t>(1, to - startUs);
        std::deque<PackedFrame> before;
        TrackRunner forward;
        std::vector<TrackSample> backward;
        status = decoder->run(startUs, to, kTrackDimension, cancel_, [&](int64_t pts, const stab::Gray& luma) {
            if (!seeded) {
                if (pts + halfFrameUs >= seedUs) {
                    seeded = true;
                    header.seedUs = pts;
                    header.aspect = luma.h > 0 ? static_cast<float>(luma.w) / static_cast<float>(luma.h) : 16.0f / 9.0f;
                    forward.begin(pts, luma, box);
                    TrackRunner back;
                    back.begin(pts, luma, box);
                    for (auto it = before.rbegin(); it != before.rend(); ++it) back.step(it->ptsUs, unpackFrame(*it));
                    backward = back.samples();
                    before.clear();
                } else {
                    before.push_back(packFrame(pts, luma));
                    if (before.size() > kMaxFramesBefore) before.pop_front();
                }
            } else {
                forward.step(pts, luma);
            }
            permille_.store(static_cast<int>(std::clamp<int64_t>((pts - startUs) * 1000 / span, 0, 999)));
            return true;
        });
        if (seeded) path = mergeRuns(backward, forward.samples());
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
    if (!seeded || path.size() < 2) {
        LOGE("tracking produced %zu samples (seeded=%d)", path.size(), seeded ? 1 : 0);
        finish(State::Failed, seeded ? Status::UnsupportedFormat : Status::InvalidArgument);
        return;
    }
    header.rangeStartUs = path.front().ptsUs;
    header.rangeEndUs = path.back().ptsUs;
    const Status written = writeTrackCache(cachePath, header, path);
    if (written != Status::Ok) {
        finish(State::Failed, written);
        return;
    }
    size_t lost = 0;
    for (const TrackSample& s : path) lost += s.lost ? 1 : 0;
    LOGI("tracked %zu frames (%lld..%lld us, %zu lost) into %s", path.size(), static_cast<long long>(header.rangeStartUs),
         static_cast<long long>(header.rangeEndUs), lost, cachePath.c_str());
    finish(State::Done, Status::Ok);
}

}  // namespace uv::track
