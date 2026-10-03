#pragma once

#include <android/hardware_buffer.h>

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <vector>

#include "decode/status.h"

namespace uv::decode {

// Ids of destroyed frames, drained by the render thread to free GL objects it cached for them.
// Frames can be destroyed on any thread, but GL objects may only be deleted on the render thread.
inline std::vector<uint64_t> takeRetiredFrameIds();
inline void retireFrameId(uint64_t id);

// A decoded frame resident in GPU-shareable memory: RGB, 10 bit per channel, still in the
// source colour space (colour conversion happens when compositing). Owns one buffer reference.
class GpuFrame {
public:
    GpuFrame(AHardwareBuffer* buffer, uint32_t width, uint32_t height, size_t bytes)
        : buffer_(buffer), width_(width), height_(height), bytes_(bytes), id_(nextId()) {}
    ~GpuFrame() {
        AHardwareBuffer_release(buffer_);
        retireFrameId(id_);
    }
    GpuFrame(const GpuFrame&) = delete;
    GpuFrame& operator=(const GpuFrame&) = delete;

    AHardwareBuffer* buffer() const { return buffer_; }
    uint32_t width() const { return width_; }
    uint32_t height() const { return height_; }
    size_t bytes() const { return bytes_; }
    uint64_t id() const { return id_; }

private:
    static uint64_t nextId() {
        static std::atomic<uint64_t> counter{1};
        return counter.fetch_add(1);
    }

    AHardwareBuffer* buffer_;
    uint32_t width_;
    uint32_t height_;
    size_t bytes_;
    uint64_t id_;
};

namespace detail {
struct RetiredIds {
    std::mutex mu;
    std::vector<uint64_t> ids;
};
inline RetiredIds& retiredIds() {
    static RetiredIds instance;
    return instance;
}
}  // namespace detail

inline void retireFrameId(uint64_t id) {
    auto& r = detail::retiredIds();
    std::lock_guard<std::mutex> lock(r.mu);
    r.ids.push_back(id);
}

inline std::vector<uint64_t> takeRetiredFrameIds() {
    auto& r = detail::retiredIds();
    std::lock_guard<std::mutex> lock(r.mu);
    std::vector<uint64_t> out;
    out.swap(r.ids);
    return out;
}

inline Result<std::shared_ptr<GpuFrame>> allocateGpuFrame(uint32_t width, uint32_t height) {
    AHardwareBuffer_Desc desc{};
    desc.width = width;
    desc.height = height;
    desc.layers = 1;
    desc.format = AHARDWAREBUFFER_FORMAT_R10G10B10A2_UNORM;
    desc.usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_GPU_FRAMEBUFFER;
    AHardwareBuffer* buffer = nullptr;
    if (AHardwareBuffer_allocate(&desc, &buffer) != 0 || buffer == nullptr) {
        return Error{Status::OutOfMemory, "AHardwareBuffer_allocate failed"};
    }
    AHardwareBuffer_Desc actual{};
    AHardwareBuffer_describe(buffer, &actual);
    const size_t bytes = static_cast<size_t>(actual.stride) * actual.height * 4;
    return std::make_shared<GpuFrame>(buffer, width, height, bytes);
}

}  // namespace uv::decode
