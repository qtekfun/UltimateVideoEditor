#pragma once

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl32.h>
#include <GLES2/gl2ext.h>
#include <android/hardware_buffer.h>
#include <android/native_window.h>

#include "decode/status.h"

namespace uv::render {

// Owns the EGL display/context for the render thread. All methods must be called from that thread.
class EglContext {
public:
    EglContext() = default;
    ~EglContext();
    EglContext(const EglContext&) = delete;
    EglContext& operator=(const EglContext&) = delete;

    // `recordable` asks for a config usable with MediaCodec input surfaces (export). `tenBit` asks for
    // an RGB10_A2 colour buffer (needed for HLG output); when none exists the context falls back to
    // RGBA8 and tenBit() reports false.
    decode::Status init(decode::Error* error, bool recordable = false, bool tenBit = false);
    bool tenBit() const { return tenBit_; }

    // The window is retained (acquired) until detachWindow(). `hlg` asks for the surface to be tagged
    // BT.2020 HLG (EGL_EXT_gl_colorspace_bt2020_hlg, else the window's data space); hdrSurface()
    // tells whether the tag took effect. HLG needs a ten-bit context, see init().
    decode::Status attachWindow(ANativeWindow* window, decode::Error* error, bool hlg = false);
    // Recreates the surface of the attached window, e.g. to switch its colour tag.
    decode::Status reattachWindow(decode::Error* error, bool hlg);
    bool hdrSurface() const { return hdrSurface_; }
    void detachWindow();
    bool hasWindow() const { return window_ != EGL_NO_SURFACE; }

    decode::Status makeCurrentWindow(decode::Error* error);
    decode::Status makeCurrentOffscreen(decode::Error* error);
    decode::Status swap(decode::Error* error);
    int windowWidth() const;
    int windowHeight() const;

    // Wraps an AHardwareBuffer as an EGLImage. Caller destroys with destroyImage().
    EGLImageKHR createImage(AHardwareBuffer* buffer) const;
    void destroyImage(EGLImageKHR image) const;
    void bindImageToTexture(GLenum target, EGLImageKHR image) const;

    // Fence that signals when the GPU work submitted so far is done. Returns a native fence fd
    // (owned by the caller, -1 when the work already finished) or -2 if fences are unsupported.
    static constexpr int kFenceUnsupported = -2;
    int createReleaseFence() const;
    bool supportsFences() const { return createSync_ != nullptr; }

    bool supportsPresentationTime() const { return presentationTime_ != nullptr; }

    // Requests that the next swapped buffer is shown no earlier than `ns` (CLOCK_MONOTONIC); 0 = now.
    void setPresentationTime(int64_t ns) const;
    // Like setPresentationTime but 0 means exactly 0 (export timestamps), not "now".
    void setPresentationTimeExact(int64_t ns) const;

private:
    EGLDisplay display_ = EGL_NO_DISPLAY;
    EGLContext context_ = EGL_NO_CONTEXT;
    EGLConfig config_ = nullptr;
    EGLSurface pbuffer_ = EGL_NO_SURFACE;
    EGLSurface window_ = EGL_NO_SURFACE;
    ANativeWindow* nativeWindow_ = nullptr;
    bool tenBit_ = false;
    bool hdrSurface_ = false;
    bool hlgColorspaceExt_ = false;

    PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC getNativeClientBuffer_ = nullptr;
    PFNEGLCREATEIMAGEKHRPROC createImageKhr_ = nullptr;
    PFNEGLDESTROYIMAGEKHRPROC destroyImageKhr_ = nullptr;
    PFNGLEGLIMAGETARGETTEXTURE2DOESPROC imageTargetTexture_ = nullptr;
    PFNEGLCREATESYNCKHRPROC createSync_ = nullptr;
    PFNEGLDESTROYSYNCKHRPROC destroySync_ = nullptr;
    PFNEGLDUPNATIVEFENCEFDANDROIDPROC dupNativeFence_ = nullptr;
    PFNEGLPRESENTATIONTIMEANDROIDPROC presentationTime_ = nullptr;
};

}  // namespace uv::render
