#pragma once

#include <cstdint>
#include <unordered_map>
#include <vector>

#include "core/layer_fx.h"
#include "decode/gpu_frame.h"
#include "decode/status.h"
#include "render/color_space.h"
#include "render/gl_context.h"
#include "render/layout_math.h"

namespace uv::render {

// One layer of a composited frame. `frame` must stay alive for the duration of the draw call.
// A title layer has no frame: `titleKey` names a texture from uploadTitle().
struct LayerDraw {
    const decode::GpuFrame* frame = nullptr;
    ColorMode mode = ColorMode::Sdr709;
    int turns = 0;  // clockwise quarter turns for display, from the container rotation
    LayerTransform transform;
    uint32_t titleKey = 0;  // != 0: draw this title texture instead of `frame`
    core::LayerFx fx = {};       // effects, blend mode and mask; neutral by default
};

// GLES programs used by the preview. Render thread only, with the EGL context current.
class GlPipeline {
public:
    explicit GlPipeline(EglContext& egl) : egl_(egl) {}
    ~GlPipeline();
    GlPipeline(const GlPipeline&) = delete;
    GlPipeline& operator=(const GlPipeline&) = delete;

    decode::Status init(decode::Error* error);

    // The colour space of the target that drawScene renders into. Sdr709 (default) keeps RGBA8
    // intermediates; Hlg2020 uses a half-float effect chain and places title graphics at reference
    // white. Layer modes must come from colorModeFor(source, space). Render thread only.
    void setOutputSpace(OutputSpace space);
    OutputSpace outputSpace() const { return outputSpace_; }

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

    // Stores a rasterised title: premultiplied RGBA8, `width` x `height` canvas pixels, top row
    // first. Replaces any texture under `key`. Keys are chosen by the caller and are never 0.
    decode::Status uploadTitle(uint32_t key, int width, int height, const uint8_t* rgba, decode::Error* error);
    void releaseTitle(uint32_t key);

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

    // An RGBA8 intermediate for effect chains, stored with row 0 = image bottom.
    struct FxTarget {
        unsigned texture = 0;
        int width = 0;
        int height = 0;
        bool hdr = false;  // true: half-float storage (HLG target); false: fixed point
    };
    decode::Status ensureFxTarget(FxTarget& target, int width, int height, decode::Error* error);
    // Runs `layer`'s effects over its source texture; `*result` is the texture holding the outcome
    // (premultiplied RGBA, row 0 = image bottom). Leaves the framebuffer, viewport and program for the
    // caller to restore.
    decode::Status runEffectChain(const LayerDraw& layer, unsigned sourceTexture, int layerWidth, int layerHeight,
                                  unsigned* result, decode::Error* error);
    void effectPass(unsigned sourceTexture, const FxTarget& destination, const core::EffectOp& op, float dirX,
                    float dirY, float sigma, float step);
    void snapshotDestination(const Viewport& vp);

    ColorMode titleMode() const { return outputSpace_ == OutputSpace::Hlg2020 ? ColorMode::Sdr709ToHlg2020 : ColorMode::Sdr709; }

    EglContext& egl_;
    OutputSpace outputSpace_ = OutputSpace::Sdr709;
    unsigned blitProgram_ = 0;
    unsigned compositeProgram_ = 0;
    unsigned vao_ = 0;
    unsigned fbo_ = 0;
    int compositeModeLoc_ = -1;
    int compositeTurnsLoc_ = -1;
    int compositeXformLoc_ = -1;
    int compositeOpacityLoc_ = -1;
    int compositePremulLoc_ = -1;
    int compositeSrcGlLoc_ = -1;
    int compositeOutPremulLoc_ = -1;
    int compositeMaskShapeLoc_ = -1;
    int compositeMaskLoc_ = -1;
    int compositeMaskSoftLoc_ = -1;
    int compositeBlendLoc_ = -1;
    int compositeDstRectLoc_ = -1;
    unsigned effectProgram_ = 0;
    int effectTypeLoc_ = -1;
    int effectParamsLoc_ = -1;
    int effectTexelLoc_ = -1;
    int effectDirLoc_ = -1;
    int effectSigmaLoc_ = -1;
    int effectStepLoc_ = -1;
    unsigned fxFbo_ = 0;
    FxTarget fxTargets_[2];
    FxTarget dstSnapshot_;  // copy of the target under a blended layer
    struct TitleTexture {
        unsigned texture = 0;
        int width = 0;
        int height = 0;
    };
    std::unordered_map<uint32_t, TitleTexture> titleTextures_;
    std::unordered_map<uint64_t, ImageTexture> frameTextures_;       // GpuFrame::id -> GL_TEXTURE_2D
    std::unordered_map<AHardwareBuffer*, ImageTexture> sourceTextures_;  // decoder buffer -> external texture
};

}  // namespace uv::render
