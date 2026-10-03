#include "audio/clip_buffer.h"

#include <algorithm>
#include <cstring>

namespace uv::audio {

ClipBuffer::ClipBuffer(int32_t capacityFrames) : capacity_(capacityFrames), mask_(capacityFrames - 1) {}

ClipBuffer::~ClipBuffer() { release(data_.exchange(nullptr)); }

void ClipBuffer::allocate() {
    if (allocated()) return;
    auto* storage = new float[static_cast<size_t>(capacity_) * 2]();
    reset(0);
    data_.store(storage, std::memory_order_release);
}

float* ClipBuffer::detach() {
    epoch_.fetch_add(1, std::memory_order_acq_rel);
    float* old = data_.exchange(nullptr, std::memory_order_acq_rel);
    validStart_.store(0, std::memory_order_release);
    validEnd_.store(0, std::memory_order_release);
    epoch_.fetch_add(1, std::memory_order_release);
    return old;
}

void ClipBuffer::release(float* storage) { delete[] storage; }

void ClipBuffer::reset(int64_t localStart) {
    epoch_.fetch_add(1, std::memory_order_acq_rel);
    validStart_.store(localStart, std::memory_order_release);
    validEnd_.store(localStart, std::memory_order_release);
    epoch_.fetch_add(1, std::memory_order_release);
}

void ClipBuffer::append(const float* stereo, int32_t frames) {
    float* d = data_.load(std::memory_order_acquire);
    if (d == nullptr || frames <= 0) return;
    frames = std::min(frames, capacity_);
    const int64_t end = validEnd_.load(std::memory_order_relaxed);
    const int64_t newEnd = end + frames;
    // Advance the window start first so readers never trust frames about to be overwritten.
    if (newEnd - validStart_.load(std::memory_order_relaxed) > capacity_) {
        validStart_.store(newEnd - capacity_, std::memory_order_release);
    }
    const int64_t first = end & mask_;
    const int64_t run = std::min<int64_t>(frames, capacity_ - first);
    std::memcpy(d + first * 2, stereo, static_cast<size_t>(run) * 2 * sizeof(float));
    if (run < frames) {
        std::memcpy(d, stereo + run * 2, static_cast<size_t>(frames - run) * 2 * sizeof(float));
    }
    validEnd_.store(newEnd, std::memory_order_release);
}

bool ClipBuffer::covers(int64_t pos, int32_t frames) const {
    if (data_.load(std::memory_order_acquire) == nullptr) return false;
    return pos >= validStart_.load(std::memory_order_acquire) &&
           pos + frames <= validEnd_.load(std::memory_order_acquire);
}

bool ClipBuffer::read(int64_t pos, int32_t frames, float* dst) const {
    const uint32_t e0 = epoch_.load(std::memory_order_acquire);
    if (e0 & 1u) return false;
    const float* d = data_.load(std::memory_order_acquire);
    if (d == nullptr) return false;
    const int64_t s = validStart_.load(std::memory_order_acquire);
    const int64_t e = validEnd_.load(std::memory_order_acquire);
    if (pos < s || pos + frames > e) return false;

    const int64_t first = pos & mask_;
    const int64_t run = std::min<int64_t>(frames, capacity_ - first);
    std::memcpy(dst, d + first * 2, static_cast<size_t>(run) * 2 * sizeof(float));
    if (run < frames) {
        std::memcpy(dst + run * 2, d, static_cast<size_t>(frames - run) * 2 * sizeof(float));
    }

    std::atomic_thread_fence(std::memory_order_acquire);
    if (validStart_.load(std::memory_order_relaxed) > pos) return false;  // overwritten mid-copy
    return epoch_.load(std::memory_order_relaxed) == e0;
}

}  // namespace uv::audio
