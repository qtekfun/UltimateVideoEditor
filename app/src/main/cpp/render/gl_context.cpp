#include "render/gl_context.h"

#include <string>

namespace uv::render {

using decode::Error;
using decode::Status;

namespace {

Status fail(Error* error, Status code, const std::string& what) {
    if (error != nullptr) *error = Error{code, what + " (egl error 0x" + std::to_string(eglGetError()) + ")"};
    return code;
}

}  // namespace

EglContext::~EglContext() {
    detachWindow();
    if (display_ != EGL_NO_DISPLAY) {
        eglMakeCurrent(display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (pbuffer_ != EGL_NO_SURFACE) eglDestroySurface(display_, pbuffer_);
        if (context_ != EGL_NO_CONTEXT) eglDestroyContext(display_, context_);
        eglTerminate(display_);
    }
}

Status EglContext::init(Error* error, bool recordable) {
    display_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (display_ == EGL_NO_DISPLAY) return fail(error, Status::EglError, "eglGetDisplay");
    if (!eglInitialize(display_, nullptr, nullptr)) return fail(error, Status::EglError, "eglInitialize");

    const EGLint configAttribs[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT | EGL_PBUFFER_BIT,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
        recordable ? EGL_RECORDABLE_ANDROID : EGL_NONE, recordable ? 1 : EGL_NONE,
        EGL_NONE,
    };
    EGLint numConfigs = 0;
    if (!eglChooseConfig(display_, configAttribs, &config_, 1, &numConfigs) || numConfigs < 1) {
        return fail(error, Status::EglError, "eglChooseConfig");
    }

    const EGLint contextAttribs[] = {EGL_CONTEXT_MAJOR_VERSION, 3, EGL_CONTEXT_MINOR_VERSION, 2, EGL_NONE};
    context_ = eglCreateContext(display_, config_, EGL_NO_CONTEXT, contextAttribs);
    if (context_ == EGL_NO_CONTEXT) return fail(error, Status::EglError, "eglCreateContext (GLES 3.2)");

    const EGLint pbufferAttribs[] = {EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
    pbuffer_ = eglCreatePbufferSurface(display_, config_, pbufferAttribs);
    if (pbuffer_ == EGL_NO_SURFACE) return fail(error, Status::EglError, "eglCreatePbufferSurface");

    getNativeClientBuffer_ = reinterpret_cast<PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC>(
        eglGetProcAddress("eglGetNativeClientBufferANDROID"));
    createImageKhr_ = reinterpret_cast<PFNEGLCREATEIMAGEKHRPROC>(eglGetProcAddress("eglCreateImageKHR"));
    destroyImageKhr_ = reinterpret_cast<PFNEGLDESTROYIMAGEKHRPROC>(eglGetProcAddress("eglDestroyImageKHR"));
    imageTargetTexture_ = reinterpret_cast<PFNGLEGLIMAGETARGETTEXTURE2DOESPROC>(
        eglGetProcAddress("glEGLImageTargetTexture2DOES"));
    if (getNativeClientBuffer_ == nullptr || createImageKhr_ == nullptr || destroyImageKhr_ == nullptr ||
        imageTargetTexture_ == nullptr) {
        return fail(error, Status::EglError, "missing EGLImage/AHardwareBuffer extensions");
    }

    // Optional: without them release fences fall back to glFinish and swaps present immediately.
    const char* extensions = eglQueryString(display_, EGL_EXTENSIONS);
    const std::string ext = extensions != nullptr ? extensions : "";
    if (ext.find("EGL_ANDROID_native_fence_sync") != std::string::npos) {
        createSync_ = reinterpret_cast<PFNEGLCREATESYNCKHRPROC>(eglGetProcAddress("eglCreateSyncKHR"));
        destroySync_ = reinterpret_cast<PFNEGLDESTROYSYNCKHRPROC>(eglGetProcAddress("eglDestroySyncKHR"));
        dupNativeFence_ =
            reinterpret_cast<PFNEGLDUPNATIVEFENCEFDANDROIDPROC>(eglGetProcAddress("eglDupNativeFenceFDANDROID"));
        if (destroySync_ == nullptr || dupNativeFence_ == nullptr) createSync_ = nullptr;
    }
    if (ext.find("EGL_ANDROID_presentation_time") != std::string::npos) {
        presentationTime_ =
            reinterpret_cast<PFNEGLPRESENTATIONTIMEANDROIDPROC>(eglGetProcAddress("eglPresentationTimeANDROID"));
    }
    return makeCurrentOffscreen(error);
}

Status EglContext::attachWindow(ANativeWindow* window, Error* error) {
    detachWindow();
    ANativeWindow_acquire(window);
    nativeWindow_ = window;
    window_ = eglCreateWindowSurface(display_, config_, window, nullptr);
    if (window_ == EGL_NO_SURFACE) {
        ANativeWindow_release(nativeWindow_);
        nativeWindow_ = nullptr;
        return fail(error, Status::EglError, "eglCreateWindowSurface");
    }
    return Status::Ok;
}

void EglContext::detachWindow() {
    if (window_ != EGL_NO_SURFACE) {
        eglMakeCurrent(display_, pbuffer_, pbuffer_, context_);
        eglDestroySurface(display_, window_);
        window_ = EGL_NO_SURFACE;
    }
    if (nativeWindow_ != nullptr) {
        ANativeWindow_release(nativeWindow_);
        nativeWindow_ = nullptr;
    }
}

Status EglContext::makeCurrentWindow(Error* error) {
    if (!eglMakeCurrent(display_, window_, window_, context_)) {
        return fail(error, Status::EglError, "eglMakeCurrent(window)");
    }
    return Status::Ok;
}

Status EglContext::makeCurrentOffscreen(Error* error) {
    if (!eglMakeCurrent(display_, pbuffer_, pbuffer_, context_)) {
        return fail(error, Status::EglError, "eglMakeCurrent(pbuffer)");
    }
    return Status::Ok;
}

Status EglContext::swap(Error* error) {
    if (!eglSwapBuffers(display_, window_)) return fail(error, Status::EglError, "eglSwapBuffers");
    return Status::Ok;
}

int EglContext::windowWidth() const {
    EGLint w = 0;
    eglQuerySurface(display_, window_, EGL_WIDTH, &w);
    return w;
}

int EglContext::windowHeight() const {
    EGLint h = 0;
    eglQuerySurface(display_, window_, EGL_HEIGHT, &h);
    return h;
}

EGLImageKHR EglContext::createImage(AHardwareBuffer* buffer) const {
    EGLClientBuffer clientBuffer = getNativeClientBuffer_(buffer);
    if (clientBuffer == nullptr) return EGL_NO_IMAGE_KHR;
    const EGLint attribs[] = {EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};
    return createImageKhr_(display_, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, clientBuffer, attribs);
}

void EglContext::destroyImage(EGLImageKHR image) const {
    if (image != EGL_NO_IMAGE_KHR) destroyImageKhr_(display_, image);
}

int EglContext::createReleaseFence() const {
    if (createSync_ == nullptr) return kFenceUnsupported;
    const EGLint attribs[] = {EGL_SYNC_NATIVE_FENCE_FD_ANDROID, EGL_NO_NATIVE_FENCE_FD_ANDROID, EGL_NONE};
    EGLSyncKHR sync = createSync_(display_, EGL_SYNC_NATIVE_FENCE_ANDROID, attribs);
    if (sync == EGL_NO_SYNC_KHR) return kFenceUnsupported;
    glFlush();  // the native fence only exists once the sync command has been submitted
    const int fd = dupNativeFence_(display_, sync);
    destroySync_(display_, sync);
    return fd == EGL_NO_NATIVE_FENCE_FD_ANDROID ? -1 : fd;
}

void EglContext::setPresentationTime(int64_t ns) const {
    if (ns > 0 && presentationTime_ != nullptr && window_ != EGL_NO_SURFACE) presentationTime_(display_, window_, ns);
}

void EglContext::setPresentationTimeExact(int64_t ns) const {
    if (ns >= 0 && presentationTime_ != nullptr && window_ != EGL_NO_SURFACE) presentationTime_(display_, window_, ns);
}

void EglContext::bindImageToTexture(GLenum target, EGLImageKHR image) const {
    imageTargetTexture_(target, static_cast<GLeglImageOES>(image));
}

}  // namespace uv::render
