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

    decode::Status init(decode::Error* error);

    // The window is retained (acquired) until detachWindow().
    decode::Status attachWindow(ANativeWindow* window, decode::Error* error);
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

private:
    EGLDisplay display_ = EGL_NO_DISPLAY;
    EGLContext context_ = EGL_NO_CONTEXT;
    EGLConfig config_ = nullptr;
    EGLSurface pbuffer_ = EGL_NO_SURFACE;
    EGLSurface window_ = EGL_NO_SURFACE;
    ANativeWindow* nativeWindow_ = nullptr;

    PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC getNativeClientBuffer_ = nullptr;
    PFNEGLCREATEIMAGEKHRPROC createImageKhr_ = nullptr;
    PFNEGLDESTROYIMAGEKHRPROC destroyImageKhr_ = nullptr;
    PFNGLEGLIMAGETARGETTEXTURE2DOESPROC imageTargetTexture_ = nullptr;
};

}  // namespace uv::render
