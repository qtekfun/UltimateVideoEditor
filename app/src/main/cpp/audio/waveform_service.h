#pragma once

#include <atomic>
#include <condition_variable>
#include <deque>
#include <functional>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <thread>

#include "audio/waveform_peaks.h"
#include "core/error.h"

namespace uv::audio {

// Background waveform extraction with an on-disk cache. One worker thread, FIFO queue.
class WaveformService {
public:
    // Invoked on the worker thread when a job ends. Status::IoError with peaks available means
    // the cache file could not be written (the waveform is still usable this session).
    using Callback = std::function<void(int64_t assetKey, core::Status status)>;

    explicit WaveformService(Callback onDone);
    ~WaveformService();
    WaveformService(const WaveformService&) = delete;
    WaveformService& operator=(const WaveformService&) = delete;

    // Takes ownership of fd (closed after the job). A cache hit at cachePath skips decoding.
    void request(int64_t assetKey, int fd, std::string cachePath);

    std::shared_ptr<const PeakPyramid> get(int64_t assetKey) const;

private:
    struct Job {
        int64_t assetKey;
        int fd;
        std::string cachePath;
    };
    void run();

    Callback onDone_;
    mutable std::mutex mu_;
    std::condition_variable cv_;
    std::deque<Job> queue_;
    std::map<int64_t, std::shared_ptr<const PeakPyramid>> ready_;
    std::map<int64_t, bool> inFlight_;
    std::atomic<bool> stop_{false};
    std::thread worker_;
};

}  // namespace uv::audio
