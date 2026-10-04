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
    // Smooth slow motion: when `blendWith` is set the layer shows the moment `blendMix` (0..1) of the way from
    // `frame` to `blendWith` (the next source frame in the direction of play), made by optical-flow
    // interpolation (or plain blending at quality 0 or where the motion cannot be measured).
    const decode::GpuFrame* blendWith = nullptr;
    float blendMix = 0.0f;
    // Neighbouring source frames for the repair effects (noise reduction reads `prev`, flicker removal reads
    // both); null when they are not decoded, in which case those effects use what they have.
    const decode::GpuFrame* prev = nullptr;
    const decode::GpuFrame* next = nullptr;
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
    // `displayWidth` x `displayHeight` (canvas pixels) is the size the picture is drawn at; 0 means the texture's own
    // size (titles are rasterised 1:1). Stills are stored at their native size and drawn scaled to their fit.
    decode::Status uploadTitle(uint32_t key, int width, int height, const uint8_t* rgba, decode::Error* error,
                               int displayWidth = 0, int displayHeight = 0);
    void releaseTitle(uint32_t key);

    // Stores a 3D LUT for the LUT effect: `size`^3 RGB float triples, red varying fastest (the .cube
    // order), as a filterable half-float 3D texture sampled trilinearly. Replaces any LUT under `key`.
    decode::Status uploadLut(uint32_t key, int size, const float* rgb, decode::Error* error);
    void releaseLut(uint32_t key);
    bool hasLut(uint32_t key) const { return lutTextures_.count(key) != 0; }

    // How hard smooth slow motion works: 0 plain frame blending, 1 optical flow on a 160 px field (the
    // preview), 2 on a 320 px field with a wider search (the exporter). Render thread only.
    void setInterpolationQuality(int quality) { interpQuality_ = quality < 0 ? 0 : (quality > 2 ? 2 : quality); }
    int interpolationQuality() const { return interpQuality_; }

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

    // ---- smooth slow motion and repair effects (render/gl_repair.cpp) ----
    struct SizedTexture {
        unsigned id = 0;
        int width = 0;
        int height = 0;
    };
    struct FlowEntry {
        uint64_t a = 0;
        uint64_t b = 0;
        int quality = 0;
        SizedTexture texture;
        uint64_t stamp = 0;
    };
    decode::Status ensureRepairResources(decode::Error* error);
    decode::Status ensureTexture(SizedTexture& tex, int width, int height, int internalFormat, int format, int type,
                                 int filter, decode::Error* error);
    // The picture `blendMix` of the way from texA to texB (`*result` is a texture in the frames' own row order
    // and colour state, so it stands in for the layer's frame texture).
    decode::Status interpolateFrames(const LayerDraw& layer, unsigned texA, unsigned texB, size_t slot, unsigned* result,
                                     decode::Error* error);
    decode::Status flowBetween(const LayerDraw& layer, unsigned texA, unsigned texB, int quality, SizedTexture** flow,
                               decode::Error* error);
    // Mean luma of `texture` (a width x height effect intermediate) into the 1 x 1 texture `out`.
    decode::Status reduceMeanLuma(unsigned texture, SizedTexture& out, decode::Error* error);
    void releaseRepairResources();

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
    decode::Status runEffectChain(const LayerDraw& layer, unsigned sourceTexture, unsigned prevTexture, unsigned nextTexture,
                                  int layerWidth, int layerHeight, unsigned* result, decode::Error* error);
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
    int effectLutSizeLoc_ = -1;
    int effectGradeLoc_ = -1;
    int effectCurveLoc_ = -1;
    // What the next noise-reduction or flicker-removal pass reads besides its source (see effectPass).
    struct RepairBinds {
        unsigned prev = 0;  // previous frame after pass 0
        unsigned meanPrev = 0;
        unsigned meanCur = 0;
        unsigned meanNext = 0;
        bool hasPrev = false;
        bool hasNext = false;
    } repairBinds_;
    int effectHasPrevLoc_ = -1;
    int effectHasNextLoc_ = -1;
    unsigned fxFbo_ = 0;
    // Repair programs and targets, created lazily.
    unsigned lumaProgram_ = 0;
    unsigned flowProgram_ = 0;
    unsigned interpProgram_ = 0;
    unsigned reduceProgram_ = 0;
    int lumaSizeLoc_ = -1;
    int lumaTapsLoc_ = -1;
    int flowSizeLoc_ = -1;
    int flowRadiusLoc_ = -1;
    int interpFlowSizeLoc_ = -1;
    int interpTLoc_ = -1;
    int interpUseFlowLoc_ = -1;
    int reduceModeLoc_ = -1;
    bool repairReady_ = false;
    int interpQuality_ = 1;
    SizedTexture lumaA_;
    SizedTexture lumaB_;
    std::vector<SizedTexture> interpOuts_;  // one result texture per interpolated layer of a scene
    SizedTexture reduceCells_;   // 16 x 16 mean-luma cells
    SizedTexture means_[3];      // 1 x 1 mean luma of the previous, current and next frame
    static constexpr int kFlowCacheSize = 4;
    FlowEntry flowCache_[kFlowCacheSize];
    uint64_t flowStamp_ = 0;
    FxTarget fxTargets_[4];  // [0] and [1] ping-pong; [2] and [3] hold the previous and next frame through pass 0
    FxTarget dstSnapshot_;  // copy of the target under a blended layer
    struct TitleTexture {
        unsigned texture = 0;
        int width = 0;
        int height = 0;
        int displayWidth = 0;   // canvas pixels the picture covers at scale 1
        int displayHeight = 0;
    };
    std::unordered_map<uint32_t, TitleTexture> titleTextures_;
    struct LutTexture {
        unsigned texture = 0;
        int size = 0;
    };
    std::unordered_map<uint32_t, LutTexture> lutTextures_;
    std::unordered_map<uint64_t, ImageTexture> frameTextures_;       // GpuFrame::id -> GL_TEXTURE_2D
    std::unordered_map<AHardwareBuffer*, ImageTexture> sourceTextures_;  // decoder buffer -> external texture
};

}  // namespace uv::render
