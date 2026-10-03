#pragma once

#include <atomic>
#include <cstdint>

namespace uv::audio {

// Single-producer (decode worker) / single-consumer (audio callback) window of decoded stereo
// float frames for one clip, addressed by clip-local output sample index. Reads are lock-free
// and validated seqlock-style, so a read that races a reposition or overwrite simply fails.
// Storage is allocated lazily by the worker; the audio thread never allocates.
class ClipBuffer {
public:
    explicit ClipBuffer(int32_t capacityFrames);  // must be a power of two
    ~ClipBuffer();
    ClipBuffer(const ClipBuffer&) = delete;
    ClipBuffer& operator=(const ClipBuffer&) = delete;

    // --- worker side ---
    bool allocated() const { return data_.load(std::memory_order_acquire) != nullptr; }
    void allocate();
    // Withdraws the storage from readers and returns it; the caller frees it (via release)
    // once the audio thread can no longer hold a pointer to it.
    float* detach();
    static void release(float* storage);
    void reset(int64_t localStart);
    void append(const float* stereo, int32_t frames);
    int64_t windowStart() const { return validStart_.load(std::memory_order_acquire); }
    int64_t writeEnd() const { return validEnd_.load(std::memory_order_acquire); }
    int32_t capacity() const { return capacity_; }

    // --- audio thread side ---
    bool covers(int64_t pos, int32_t frames) const;
    bool read(int64_t pos, int32_t frames, float* dst) const;

private:
    const int32_t capacity_;
    const int64_t mask_;
    std::atomic<float*> data_{nullptr};
    std::atomic<int64_t> validStart_{0};
    std::atomic<int64_t> validEnd_{0};
    std::atomic<uint32_t> epoch_{0};  // odd while the worker is repositioning/detaching
};

}  // namespace uv::audio
