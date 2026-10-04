#pragma once

#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>
#include <thread>

#include "core/error.h"

namespace uv::stab {

// Background analysis of one stretch of one media file: a low-priority worker decodes it sequentially (one
// hardware decoder, released when the job ends), tracks the camera motion frame to frame and writes the
// result to a cache file (stab_cache.h). One job at a time; the caller polls for progress, which keeps JNI
// free of callbacks.
class StabService {
public:
    enum class State : int { Idle = 0, Running = 1, Done = 2, Failed = 3, Cancelled = 4 };

    struct Poll {
        State state = State::Idle;
        int permille = 0;  // 0..1000 while running
        core::Status error = core::Status::Ok;  // meaningful when Failed
    };

    StabService() = default;
    ~StabService();
    StabService(const StabService&) = delete;
    StabService& operator=(const StabService&) = delete;

    // Takes ownership of `fd` (closed when the job ends or fails to start). Analyses [startUs, endUs] measured
    // from the first frame of the media (endUs <= 0: to the end) and writes `cachePath`. InvalidArgument when a
    // job is already running.
    core::Status start(int fd, int64_t startUs, int64_t endUs, std::string cachePath);

    // Asks the running job to stop; poll() then reports Cancelled. No effect when idle.
    void cancel();

    Poll poll() const;

private:
    void run(int fd, int64_t startUs, int64_t endUs, std::string cachePath);
    void finish(State state, core::Status error);

    mutable std::mutex mu_;
    std::thread worker_;
    State state_ = State::Idle;
    core::Status error_ = core::Status::Ok;
    std::atomic<int> permille_{0};
    std::atomic<bool> cancel_{false};
};

}  // namespace uv::stab
