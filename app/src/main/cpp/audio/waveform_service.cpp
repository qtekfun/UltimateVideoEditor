#include "audio/waveform_service.h"

#include <android/log.h>
#include <unistd.h>

#include "audio/waveform_extractor.h"

#define LOG_TAG "uv_waveform"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace uv::audio {

using core::Status;

WaveformService::WaveformService(Callback onDone) : onDone_(std::move(onDone)) {
    worker_ = std::thread([this] { run(); });
}

WaveformService::~WaveformService() {
    stop_.store(true);
    cv_.notify_all();
    if (worker_.joinable()) worker_.join();
    for (auto& job : queue_) close(job.fd);
}

void WaveformService::request(int64_t assetKey, int fd, std::string cachePath) {
    {
        std::lock_guard<std::mutex> lock(mu_);
        if (ready_.count(assetKey) != 0 || inFlight_.count(assetKey) != 0) {
            close(fd);  // already known; the caller's duplicate request is a no-op
            return;
        }
        inFlight_[assetKey] = true;
        queue_.push_back({assetKey, fd, std::move(cachePath)});
    }
    cv_.notify_one();
}

std::shared_ptr<const PeakPyramid> WaveformService::get(int64_t assetKey) const {
    std::lock_guard<std::mutex> lock(mu_);
    auto it = ready_.find(assetKey);
    return it == ready_.end() ? nullptr : it->second;
}

void WaveformService::run() {
    while (true) {
        Job job;
        {
            std::unique_lock<std::mutex> lock(mu_);
            cv_.wait(lock, [this] { return stop_.load() || !queue_.empty(); });
            if (stop_.load()) return;
            job = std::move(queue_.front());
            queue_.pop_front();
        }

        auto pyramid = std::make_shared<PeakPyramid>();
        Status status = loadPeaks(job.cachePath, pyramid.get());
        if (status != Status::Ok) {
            // Missing or unreadable cache: decode and rebuild it.
            status = extractWaveform(job.fd, stop_, pyramid.get());
            if (status == Status::Ok && savePeaks(job.cachePath, *pyramid) != Status::Ok) {
                LOGW("could not write waveform cache %s", job.cachePath.c_str());
                status = Status::IoError;  // usable in memory, surfaced to the caller
            }
        }
        close(job.fd);

        const bool usable = status == Status::Ok || (status == Status::IoError && !pyramid->levels.empty());
        {
            std::lock_guard<std::mutex> lock(mu_);
            inFlight_.erase(job.assetKey);
            if (usable) ready_[job.assetKey] = pyramid;
        }
        if (onDone_) onDone_(job.assetKey, status);
    }
}

}  // namespace uv::audio
