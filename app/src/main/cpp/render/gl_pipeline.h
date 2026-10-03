#pragma once

#include "decode/gpu_frame.h"
#include "decode/status.h"
#include "render/gl_context.h"

namespace uv::render {

// Mirrors com.ultimatevideo.uveditor.engine.preview.ColorMode.
enum class ColorMode : int {
    Sdr709 = 0,
    Hlg2020ToSdr709 = 1,
};

// GLES programs used by the preview. Render thread only, with the EGL context current.
class GlPipeline {
public:
    explicit GlPipeline(EglContext& egl) : egl_(egl) {}
    ~GlPipeline();
    GlPipeline(const GlPipeline&) = delete;
    GlPipeline& operator=(const GlPipeline&) = delete;

    decode::Status init(decode::Error* error);

    // Copies a decoder output buffer (any YUV/RGB format) into `dst`. Blocks until the GPU is
    // done reading `src` so the caller may release it immediately afterwards.
    decode::Status blitToFrame(AHardwareBuffer* src, const decode::GpuFrame& dst, decode::Error* error);

    // Letterboxes `frame` into the current window surface (must be current) and applies the
    // colour mode. Does not swap.
    decode::Status draw(const decode::GpuFrame& frame, ColorMode mode, int surfaceWidth, int surfaceHeight,
                        decode::Error* error);

    // Clears the current window surface to black.
    void clear(int surfaceWidth, int surfaceHeight);

private:
    decode::Status buildProgram(const char* vertex, const char* fragment, unsigned* program, decode::Error* error);

    EglContext& egl_;
    unsigned blitProgram_ = 0;
    unsigned compositeProgram_ = 0;
    unsigned vao_ = 0;
    unsigned fbo_ = 0;
};

}  // namespace uv::render
