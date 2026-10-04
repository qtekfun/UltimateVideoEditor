#pragma once

#include <GLES3/gl32.h>

#include "decode/status.h"
#include "render/scope_math.h"

namespace uv::render {

// Draws the video scopes on the GPU without reading any pixel back: the preview picture is blitted
// down to a small texture, every pixel of it becomes one point accumulated (additive blending) into a
// half-float texture, and a display pass turns the counts into the scope picture on another surface.
// Render thread only, with the EGL context current. The maths is in render/scope_math.h.
class ScopeRenderer {
public:
    ScopeRenderer() = default;
    ~ScopeRenderer();
    ScopeRenderer(const ScopeRenderer&) = delete;
    ScopeRenderer& operator=(const ScopeRenderer&) = delete;

    decode::Status init(decode::Error* error);

    // Copies the rectangle (`x`, `y`, `w`, `h`; bottom-left origin) of the window framebuffer (0), which
    // must be the bound read framebuffer's surface, into the source texture. Call before swapping.
    void capture(int x, int y, int w, int h);

    bool hasFrame() const { return captured_; }

    // Accumulates the last captured frame for `mode` and draws the scope over the whole of framebuffer 0
    // of the current surface (`surfaceW` x `surfaceH`). The vectorscope is drawn in a centred square.
    void render(scope::Mode mode, int surfaceW, int surfaceH);

private:
    void accumulate(scope::Mode mode);
    void release();

    unsigned srcTexture_ = 0;
    unsigned srcFbo_ = 0;
    unsigned accumTexture_ = 0;
    unsigned accumFbo_ = 0;
    unsigned vao_ = 0;
    unsigned pointProgram_ = 0;
    unsigned displayProgram_ = 0;
    int pointSrcLoc_ = -1;
    int pointSrcSizeLoc_ = -1;
    int pointModeLoc_ = -1;
    int pointChannelLoc_ = -1;
    int displayAccumLoc_ = -1;
    int displayModeLoc_ = -1;
    int displayWaveGainLoc_ = -1;
    int displayVectorGainLoc_ = -1;
    int displayHistFullLoc_ = -1;
    bool captured_ = false;
};

}  // namespace uv::render
