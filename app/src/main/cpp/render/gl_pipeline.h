#pragma once

#include <cstdint>
#include <unordered_map>
#include <vector>

#include "decode/gpu_frame.h"
#include "decode/status.h"
#include "render/gl_context.h"
#include "render/layout_math.h"

namespace uv::render {

// Mirrors com.ultimatevideo.uveditor.engine.preview.ColorMode.
enum class ColorMode : int {
    Sdr709 = 0,
    Hlg2020ToSdr709 = 1,
};

// One layer of a composited frame. `frame` must stay alive for the duration of the draw call.
struct LayerDraw {
    const decode::GpuFrame* frame = nullptr;
    ColorMode mode = ColorMode::Sdr709;
    int turns = 0;  // clockwise quarter turns for display, from the container rotation
    LayerTransform transform;
};

// GLES programs used by the preview. Render thread only, with the EGL context current.
class GlPipeline {
public:
    explicit GlPipeline(EglContext& egl) : egl_(egl) {}
    ~GlPipeline();
    GlPipeline(const GlPipeline&) = delete;
    GlPipeline& operator=(const GlPipeline&) = delete;

    decode::Status init(decode::Error* error);

    // Copies a decoder output buffer (any YUV/RGB format) into `dst`. Does not wait for the GPU:
    // `*releaseFenceFd` receives a native fence fd (or -1 when nothing is pending) that the caller
    // must hand to the decoder with the buffer release, which then waits for it before reuse.
    decode::Status blitToFrame(AHardwareBuffer* src, const decode::GpuFrame& dst, int* releaseFenceFd,
                               decode::Error* error);

    // Letterboxes `frame` into the current window surface (must be current), rotated clockwise by
    // `turns` quarter turns, and applies the colour mode. Does not swap.
    decode::Status draw(const decode::GpuFrame& frame, ColorMode mode, int turns, int surfaceWidth,
                        int surfaceHeight, decode::Error* error);

    // Composites `layers` (bottom to top) over black into the currently bound framebuffer, which is
    // `surfaceWidth` x `surfaceHeight`. The project canvas (`canvasWidth` x `canvasHeight`) is
    // letterboxed into it and every layer is clipped to the canvas. Pass the framebuffer size as the
    // canvas size to fill it, which is what an offscreen (export) render does after binding its own
    // framebuffer. Does not bind a framebuffer, swap or wait for the GPU.
    decode::Status drawScene(const std::vector<LayerDraw>& layers, int canvasWidth, int canvasHeight,
                             int surfaceWidth, int surfaceHeight, decode::Error* error);

    // Drops GL objects cached for decoder buffers (call when a decoder goes away).
    void clearSourceCache();

    // Clears the current window surface to black.
    void clear(int surfaceWidth, int surfaceHeight);

private:
    // An AHardwareBuffer wrapped once as EGLImage + texture. Creating these per frame costs more
    // than the copy itself, so they live as long as the buffer does.
    struct ImageTexture {
        EGLImageKHR image = EGL_NO_IMAGE_KHR;
        unsigned texture = 0;
    };

    decode::Status sourceTexture(AHardwareBuffer* buffer, unsigned* texture, decode::Error* error);
    decode::Status frameTexture(const decode::GpuFrame& frame, unsigned* texture, bool* created, decode::Error* error);
    void destroy(ImageTexture& entry);
    void releaseRetired();

    decode::Status buildProgram(const char* vertex, const char* fragment, unsigned* program, decode::Error* error);

    EglContext& egl_;
    unsigned blitProgram_ = 0;
    unsigned compositeProgram_ = 0;
    unsigned vao_ = 0;
    unsigned fbo_ = 0;
    int compositeModeLoc_ = -1;
    int compositeTurnsLoc_ = -1;
    int compositeXformLoc_ = -1;
    int compositeOpacityLoc_ = -1;
    std::unordered_map<uint64_t, ImageTexture> frameTextures_;       // GpuFrame::id -> GL_TEXTURE_2D
    std::unordered_map<AHardwareBuffer*, ImageTexture> sourceTextures_;  // decoder buffer -> external texture
};

}  // namespace uv::render
