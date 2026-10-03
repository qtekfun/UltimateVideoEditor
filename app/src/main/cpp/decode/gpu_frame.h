#pragma once

#include <android/hardware_buffer.h>

#include <cstdint>
#include <memory>

#include "decode/status.h"

namespace uv::decode {

// A decoded frame resident in GPU-shareable memory: RGB, 10 bit per channel, still in the
// source colour space (colour conversion happens when compositing). Owns one buffer reference.
class GpuFrame {
public:
    GpuFrame(AHardwareBuffer* buffer, uint32_t width, uint32_t height, size_t bytes)
        : buffer_(buffer), width_(width), height_(height), bytes_(bytes) {}
    ~GpuFrame() { AHardwareBuffer_release(buffer_); }
    GpuFrame(const GpuFrame&) = delete;
    GpuFrame& operator=(const GpuFrame&) = delete;

    AHardwareBuffer* buffer() const { return buffer_; }
    uint32_t width() const { return width_; }
    uint32_t height() const { return height_; }
    size_t bytes() const { return bytes_; }

private:
    AHardwareBuffer* buffer_;
    uint32_t width_;
    uint32_t height_;
    size_t bytes_;
};

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
